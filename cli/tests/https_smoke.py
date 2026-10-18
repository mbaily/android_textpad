"""Packaged HTTPS smoke test for verified and explicit insecure modes (needs openssl)."""
from pathlib import Path
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid

from werkzeug.serving import make_server
from textpad_server import create_app
from textpad_sync.api import Api

TOKEN = 'disposable-https-client-token-32-characters'
REPO = Path(__file__).resolve().parents[2]
ARCHIVE = REPO / 'server/textpad_server/static/textpad-sync.pyz'


def wait_for(check, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.05)
    raise AssertionError('Timed out waiting for the foreground monitor')


def main():
    with tempfile.TemporaryDirectory(prefix='textpad-cli-https-') as folder:
        root = Path(folder)
        cert, key = root / 'ca.pem', root / 'server.key'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
            '-keyout', str(key), '-out', str(cert), '-subj', '/CN=wrong-host.invalid',
            '-addext', 'subjectAltName=IP:127.0.0.1', '-addext', 'basicConstraints=critical,CA:TRUE'],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        app = create_app({'TESTING': True, 'DATABASE': str(root / 'server.sqlite3'), 'API_TOKEN': TOKEN,
            'SECRET_KEY': 'disposable-https-client-session-secret', 'PUBLIC_URL': 'https://127.0.0.1'})
        server = make_server('127.0.0.1', 0, app, threaded=True, ssl_context=(str(cert), str(key)))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = 'https://127.0.0.1:' + str(server.server_port)
        env = dict(os.environ, TEXTPAD_TOKEN=TOKEN)
        local = root / 'workspace'
        command = [sys.executable, str(ARCHIVE)]
        watcher = None
        try:
            def run(*args, expected=0, environment=None):
                result = subprocess.run(command + list(args), env=env if environment is None else environment,
                                        capture_output=True, text=True)
                assert result.returncode == expected, result.stderr
                return result.stdout
            run('init', str(root / 'untrusted-workspace'), '--server', base, expected=1)
            assert not (root / 'untrusted-workspace/.textpad').exists()
            verified = root / 'verified-workspace'
            run('init', str(verified), '--server', base, '--ca-file', str(cert))
            run('-C', str(verified), 'remote')
            wrong_host = base.replace('127.0.0.1', 'localhost')
            run('init', str(root / 'wrong-host-verified'), '--server', wrong_host,
                '--ca-file', str(cert), expected=1)
            run('init', str(root / 'wrong-host-insecure'), '--server', wrong_host,
                '--ca-file', str(cert), '--insecure')
            run('--insecure', 'init', str(local), '--server', base)
            run('-C', str(local), 'remote', expected=1)
            run('-C', str(local), 'remote', '--insecure')
            run('--insecure', '-C', str(local), 'remote')
            content = ''.join(f'Line {n}: retained content\r\n' for n in range(200)).encode()
            file = local / 'notes.txt'
            file.write_bytes(content)
            run('-C', str(local), 'add', 'notes.txt')
            run('-C', str(local), 'sync', '--settle', '0.05', '--insecure')
            api = Api(base, TOKEN, 'cli-https-verification', cert)
            remote = next(d for d in api.list() if d.name == 'notes.txt')
            assert api.get(remote.id).content == content
            errands_id = str(uuid.uuid4())
            errands_content = b'[ ] Prescription #chemist\r\n- [c] Cancelled #chemist\r\n'
            api.put(errands_id, 'errands.txt', errands_content, None, str(uuid.uuid4()))
            saved_tags = json.loads(run('-C', str(verified), 'errands', 'tags', '--set', '#chemist #groceries', '--json'))
            assert saved_tags['hashtags'] == api.errands()['hashtags'] == ['chemist', 'groceries']
            matches = json.loads(run('-C', str(verified), 'errands', '--json'))['results']
            assert len(matches) == 1 and matches[0]['id'] == errands_id
            assert [line['line_number'] for line in matches[0]['lines']] == [1]
            files = json.loads(run('-C', str(verified), 'errands', '--files', '--json'))['results']
            assert len(files) == 1 and files[0]['line_count'] == 1 and 'lines' not in files[0]
            matches = json.loads(run('-C', str(verified), 'errands', '--show-completed', '--json'))['results']
            assert [line['line_number'] for line in matches[0]['lines']] == [1, 2]
            run('-C', str(verified), 'get', errands_id)
            (verified / 'errands.txt').write_text('[ ] Saved local edit #chemist\n', encoding='utf-8')
            matches = json.loads(run('-C', str(verified), 'errands', '--json'))['results']
            assert len(matches) == 1 and matches[0]['source'] == 'local'
            assert matches[0]['lines'][0]['text'] == '[ ] Saved local edit #chemist'
            offline_env = {key: value for key, value in env.items() if key != 'TEXTPAD_TOKEN'}
            assert 'Saved local edit' in run('-C', str(verified), 'errands', '--offline', environment=offline_env)
            run('-C', str(local), 'errands', 'tags', '--insecure')
            with (root / 'watch.log').open('w') as log:
                watcher = subprocess.Popen(command + ['-C', str(local), 'watch', '--insecure', '--settle', '0.3',
                    '--interval', '0.05', '--remote-interval', '0.5'], env=env, stdout=log, stderr=subprocess.STDOUT)
                file.write_bytes(b'')
                wait_for(lambda: 'held:' in (root / 'watch.log').read_text())
                assert api.get(remote.id).content == content
                final = content + 'Saved through an external editor 😀\r\n'.encode()
                file.write_bytes(final)
                wait_for(lambda: api.get(remote.id).content == final)
                saved = api.get(remote.id)
                remote_final = final + b'Change from another device\r\n'
                api.put(remote.id, remote.name, remote_final, saved.revision, str(uuid.uuid4()))
                wait_for(lambda: file.read_bytes() == remote_final)
                watcher.send_signal(signal.SIGINT)
                assert watcher.wait(timeout=10) == 130
            run('-C', str(local), 'backups', '--json')
            run('-C', str(local), 'history', 'notes.txt', '--insecure')
            run('-C', str(local), 'download', remote.id, 'inspection.txt', '--insecure')
            assert (local / 'inspection.txt').read_bytes() == remote_final
            run('-C', str(local), 'remote', expected=1)
            run('-C', str(local), 'remove', 'notes.txt')
            assert file.read_bytes() == remote_final
            assert api.get(remote.id).content == remote_final
            print('Packaged HTTPS client passed: verified defaults, explicit certificate/hostname bypass, per-invocation flags, setup, upload, errands settings, line/file matching, closed-task filters, local overrides, offline search without a token, foreground monitor, blank-file hold, external-editor save, remote pull, Ctrl+C, history, snapshots, and untracking.')
        finally:
            if watcher is not None and watcher.poll() is None:
                watcher.terminate()
                watcher.wait(timeout=10)
            server.shutdown()
            thread.join()
            server.server_close()


if __name__ == '__main__':
    main()
