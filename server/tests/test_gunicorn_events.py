"""Real two-worker tests for commit signals, including a crash before publication."""
import base64
import http.client
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
import urllib.error
import urllib.request

from simple_websocket import ConnectionError
from ws_client import ClosingClient


class GunicornEventsTests(unittest.TestCase):
    def setUp(self):
        import socket
        self.folder = tempfile.TemporaryDirectory(prefix='textpad-event-workers-')
        root = Path(self.folder.name)
        self.root = root
        self.token = 'isolated-test-api-token-at-least-32-characters'
        (root/'token').write_text(self.token)
        (root/'session').write_text('isolated-test-session-secret-32-characters')
        (root/'event_fixture.py').write_text(textwrap.dedent('''
            import os
            from pathlib import Path
            from flask import request, jsonify
            from textpad_server import create_app
            app = create_app()
            store = app.extensions['store']
            signal = app.extensions['changes']
            subscribe = signal.subscribe
            publish = store.on_commit
            def record_subscription():
                with open(Path(os.environ['TEST_EVENTS_DIRECTORY'])/'subscribers','a') as output:
                    output.write(str(os.getpid())+'\\n')
                return subscribe()
            signal.subscribe = record_subscription
            def after_commit():
                if request.headers.get('X-Test-Exit-After-Commit')=='yes':
                    os._exit(0)
                publish()
            store.on_commit = after_commit
            @app.before_request
            def target_worker():
                target=request.headers.get('X-Test-Target-Worker')
                if target is not None and target!=str(os.getpid()):
                    return jsonify(error='Try another worker'),409
            @app.after_request
            def identify_worker(response):
                response.headers['X-Test-Worker']=str(os.getpid())
                return response
        '''))
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0))
            port = sock.getsockname()[1]
        self.base = f'http://127.0.0.1:{port}'
        server_root = str(Path(__file__).resolve().parents[1])
        environment = dict(os.environ, PYTHONPATH=os.pathsep.join([str(root),server_root]),
                           TEXTPAD_DATABASE=str(root/'database.sqlite3'),TEXTPAD_TOKEN_FILE=str(root/'token'),
                           TEXTPAD_SESSION_FILE=str(root/'session'),TEXTPAD_PUBLIC_URL=self.base,
                           TEST_EVENTS_DIRECTORY=str(root),PYTHONDONTWRITEBYTECODE='1')
        self.log = open(root/'gunicorn.log','w+')
        self.sockets = []
        self.process = subprocess.Popen([sys.executable,'-m','gunicorn','--config','python:textpad_server.gunicorn_config',
                                         '--bind',f'127.0.0.1:{port}','--workers','2','--threads','4',
                                         '--graceful-timeout','1','--timeout','15','event_fixture:app'],
                                        env=environment,stdout=self.log,stderr=self.log,start_new_session=True)
        try:
            self.wait_until(lambda:self.ready(),8)
        except BaseException:
            self.tearDown()
            raise

    def tearDown(self):
        for ws in self.sockets:
            if ws.connected:
                ws.close()
        if self.process.poll() is None:
            os.killpg(self.process.pid,signal.SIGTERM)
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid,signal.SIGKILL)
                self.process.wait(timeout=5)
        self.log.close()
        self.folder.cleanup()

    def workers(self):
        return set(map(int,Path(f'/proc/{self.process.pid}/task/{self.process.pid}/children').read_text().split()))

    def ready(self):
        if self.process.poll() is not None:
            self.log.flush()
            raise AssertionError((self.root/'gunicorn.log').read_text())
        try:
            with urllib.request.urlopen(self.base+'/health',timeout=0.2) as response:
                return response.status==200 and len(self.workers())==2
        except (OSError,ValueError):
            return False

    def wait_until(self,predicate,timeout=5):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if predicate():
                return
            time.sleep(0.02)
        self.fail('Timed out waiting for Gunicorn worker state')

    def connect(self,worker=None):
        headers={'Authorization':'Bearer '+self.token}
        if worker is not None:
            headers['X-Test-Target-Worker']=str(worker)
        deadline=time.monotonic()+5
        while True:
            try:
                ws=ClosingClient.connect(self.base.replace('http:','ws:')+'/api/v1/events',headers=headers)
                break
            except ConnectionError:
                if time.monotonic()>deadline:
                    raise
        self.sockets.append(ws)
        ready=json.loads(ws.receive(timeout=3))
        self.assertEqual('ready',ready['type'])
        subscribed=int((self.root/'subscribers').read_text().splitlines()[-1])
        return ws,subscribed,ready

    def put(self,worker,request_id,content,crash=False):
        payload=json.dumps({'name':'workers.txt','content_base64':base64.b64encode(content).decode(),
                            'base_revision':None,'request_id':request_id}).encode()
        headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/json',
                 'X-Test-Target-Worker':str(worker)}
        if crash:
            headers['X-Test-Exit-After-Commit']='yes'
        deadline=time.monotonic()+5
        while True:
            request=urllib.request.Request(self.base+'/api/v1/documents/worker-file',data=payload,headers=headers,method='PUT')
            try:
                with urllib.request.urlopen(request,timeout=2) as response:
                    self.assertEqual(str(worker),response.headers['X-Test-Worker'])
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code!=409 or time.monotonic()>deadline:
                    raise
            except (http.client.RemoteDisconnected,ConnectionResetError):
                if crash:
                    return None
                raise

    def test_a_commit_wakes_clients_in_both_workers(self):
        first,first_worker,_=self.connect()
        second_worker=(self.workers()-{first_worker}).pop()
        second,_,_=self.connect(second_worker)
        latest=self.put(first_worker,'cross-worker',b'latest contents')
        for ws in [first,second]:
            self.assertEqual({'type':'changed','cursor':latest['revision']},json.loads(ws.receive(timeout=3)))
        with urllib.request.urlopen(urllib.request.Request(self.base+'/api/v1/documents/worker-file',
                headers={'Authorization':'Bearer '+self.token}),timeout=2) as response:
            self.assertEqual(b'latest contents',base64.b64decode(json.load(response)['content_base64']))

    def test_worker_crash_after_commit_refreshes_survivors_and_replacement_workers(self):
        survivor,survivor_pid,_=self.connect()
        producer=(self.workers()-{survivor_pid}).pop()
        self.put(producer,'commit-before-crash',b'committed before worker exit',crash=True)
        event=json.loads(survivor.receive(timeout=3))
        self.assertEqual('changed',event['type'])
        self.assertEqual('1',event['cursor'])
        self.wait_until(lambda:len(self.workers())==2 and producer not in self.workers())
        replacement=(self.workers()-{survivor_pid}).pop()
        replaced,_,ready=self.connect(replacement)
        self.assertEqual('1',ready['cursor'])
        latest=self.put(survivor_pid,'after-replacement',b'newest after replacement')
        # The child-exit signal may also reach a newly connected socket; only the
        # newest cursor matters, rather than replaying intermediate notifications.
        for ws in [survivor,replaced]:
            deadline=time.monotonic()+3
            while True:
                message=json.loads(ws.receive(timeout=3))
                if message['cursor']==latest['revision']:
                    break
                self.assertLess(time.monotonic(),deadline)
