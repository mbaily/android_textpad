from contextlib import closing
import json
from pathlib import Path
import re
import socket
import tempfile
import threading
import unittest
from unittest.mock import patch
import sqlite3

from simple_websocket import ConnectionError
from ws_client import ClosingClient
from werkzeug.serving import make_server, WSGIRequestHandler
from textpad_server import create_app
from textpad_server.store import Store


class QuietHandler(WSGIRequestHandler):
    def log(self, *args, **kwargs):
        pass


class EventsTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        self.base = f'http://127.0.0.1:{port}'
        self.token = 'test-websocket-credential-at-least-32-characters'
        self.app = create_app({'TESTING':True, 'DATABASE':str(Path(self.folder.name)/'db.sqlite3'),
                               'API_TOKEN':self.token, 'SECRET_KEY':'test-websocket-session-secret-32-characters',
                               'PUBLIC_URL':self.base, 'SESSION_COOKIE_SECURE':False})
        self.server = make_server('127.0.0.1',port,self.app,threaded=True,request_handler=QuietHandler)
        self.thread = threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.sockets = []

    def tearDown(self):
        for ws in self.sockets:
            if ws.connected:
                ws.close()
        self.app.extensions['changes'].close()
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.folder.cleanup()

    def connect(self, path='/api/v1/events', headers=None):
        ws = ClosingClient.connect(self.base.replace('http:','ws:')+path,
                            headers=headers or {'Authorization':'Bearer '+self.token})
        self.sockets.append(ws)
        return ws

    def browser_cookie(self):
        client = self.app.test_client()
        page = client.get('/editor/login')
        csrf = re.search(rb'name="csrf" value="([a-f0-9]+)"',page.data).group(1).decode()
        response = client.post('/editor/login',data={'csrf':csrf,'credential':self.token})
        self.assertEqual(303,response.status_code)
        return response.headers['Set-Cookie'].split(';',1)[0]

    def test_notifications_observe_commits_from_an_independent_store(self):
        ws = self.connect()
        self.assertEqual({'type':'ready','cursor':'0'},json.loads(ws.receive(timeout=3)))
        other = Store(self.app.config['DATABASE'],on_commit=self.app.extensions['changes'].publish)
        accepted = other.put('event-file','event.txt',b'private contents',None,'write-1')
        self.assertEqual({'type':'changed','cursor':accepted['revision']},json.loads(ws.receive(timeout=3)))
        other.put('event-file','event.txt',b'private contents',None,'write-1')
        self.assertIsNone(ws.receive(timeout=1.2))  # Retry receipts are not new changes.
        reconnect = self.connect()
        self.assertEqual(accepted['revision'],json.loads(reconnect.receive(timeout=3))['cursor'])

    def test_idle_connections_do_not_repeatedly_read_sqlite(self):
        store = self.app.extensions['store']
        with patch.object(store,'change_cursor',wraps=store.change_cursor) as read:
            ws = self.connect()
            self.assertEqual('ready',json.loads(ws.receive(timeout=3))['type'])
            self.assertIsNone(ws.receive(timeout=2.2))
            self.assertEqual(1,read.call_count)

    def test_signals_follow_commit_and_failed_transactions_do_not_notify(self):
        ws = self.connect()
        ws.receive(timeout=3)
        store = self.app.extensions['store']
        changes = self.app.extensions['changes']
        committed = []

        def after_commit():
            committed.append(Store(store.path).get('event-file')[1])
            changes.publish()

        store.on_commit = after_commit
        first = store.put('event-file','event.txt',b'committed bytes',None,'success')
        self.assertEqual([b'committed bytes'],committed)
        self.assertEqual(first['revision'],json.loads(ws.receive(timeout=3))['cursor'])
        with closing(store.connect()) as db:
            db.executescript("CREATE TRIGGER reject_receipt BEFORE INSERT ON receipts BEGIN SELECT RAISE(ABORT,'forced rollback'); END;")
        with self.assertRaises(sqlite3.IntegrityError):
            store.put('event-file','event.txt',b'must roll back',first['revision'],'failure')
        self.assertEqual([b'committed bytes'],committed)
        self.assertEqual(b'committed bytes',store.get('event-file')[1])
        self.assertIsNone(ws.receive(timeout=0.2))

    def test_rapid_saves_coalesce_to_the_latest_committed_revision(self):
        ws = self.connect()
        ws.receive(timeout=3)
        store = self.app.extensions['store']
        changes = self.app.extensions['changes']
        with changes.condition:
            for index in range(20):
                accepted = store.put('event-file','event.txt',str(index).encode(),None,f'burst-{index}')
        self.assertEqual({'type':'changed','cursor':accepted['revision']},json.loads(ws.receive(timeout=3)))
        self.assertIsNone(ws.receive(timeout=0.2))
        self.assertEqual(b'19',store.get('event-file')[1])

    def test_a_save_during_notification_delivery_is_not_lost(self):
        store = self.app.extensions['store']
        original = store.change_cursor
        entered, release = threading.Event(),threading.Event()
        calls = []

        def delay_first_change():
            value = original()
            calls.append(value)
            if len(calls)==2:
                entered.set()
                release.wait(timeout=5)
            return value

        with patch.object(store,'change_cursor',side_effect=delay_first_change):
            ws = self.connect()
            ws.receive(timeout=3)
            first = store.put('event-file','event.txt',b'first',None,'one')
            try:
                self.assertTrue(entered.wait(timeout=3))
                latest = store.put('event-file','event.txt',b'latest',first['revision'],'two')
            finally:
                release.set()
            messages=[json.loads(ws.receive(timeout=3)),json.loads(ws.receive(timeout=3))]
            self.assertEqual(latest['revision'],messages[-1]['cursor'])

    def test_disconnect_releases_the_waiting_subscription(self):
        ws = self.connect()
        ws.receive(timeout=3)
        changes = self.app.extensions['changes']
        with changes.condition:
            self.assertEqual(1,len(changes.subscriptions))
        ws.close()
        with changes.condition:
            self.assertTrue(changes.condition.wait_for(lambda:not changes.subscriptions,timeout=3))

    def test_websocket_authentication_and_browser_origin(self):
        for path,headers in [('/api/v1/events',{'Authorization':'Bearer incorrect'}),
                             ('/editor/api/events',{'Origin':self.base}),
                             ('/editor/api/events',{'Cookie':self.browser_cookie(),'Origin':'https://other.example'})]:
            with self.subTest(path=path,headers=list(headers)):
                with self.assertRaises(ConnectionError):
                    self.connect(path,headers)
        ws = self.connect('/editor/api/events',{'Cookie':self.browser_cookie(),'Origin':self.base})
        self.assertEqual('ready',json.loads(ws.receive(timeout=3))['type'])

    def test_change_cursor_survives_history_removal_and_retry(self):
        store = self.app.extensions['store']
        first = store.put('event-file','event.txt',b'first',None,'one')
        second = store.put('event-file','event.txt',b'second',first['revision'],'two')
        store.remove_revision('event-file',first['revision'])
        store.put('event-file','event.txt',b'first',None,'one')
        self.assertEqual(second['revision'],store.change_cursor())
