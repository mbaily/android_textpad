"""Optional packaged-client smoke test over verified local HTTPS (needs openssl)."""
from pathlib import Path
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
            '-keyout', str(key), '-out', str(cert), '-subj', '/CN=localhost',
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
            def run(*args, expected=0):
                result = subprocess.run(command + list(args), env=env, capture_output=True, text=True)
                assert result.returncode == expected, result.stderr
                return result.stdout
            run('init', str(root / 'untrusted-workspace'), '--server', base, expected=1)
            assert not (root / 'untrusted-workspace/.textpad').exists()
            run('init', str(local), '--server', base, '--ca-file', str(cert))
            content = ''.join(f'Line {n}: retained content\r\n' for n in range(200)).encode()
            file = local / 'notes.txt'
            file.write_bytes(content)
            run('-C', str(local), 'add', 'notes.txt')
            run('-C', str(local), 'sync', '--settle', '0.05')
            api = Api(base, TOKEN, 'cli-https-verification', cert)
            remote = next(d for d in api.list() if d.name == 'notes.txt')
            assert api.get(remote.id).content == content
            with (root / 'watch.log').open('w') as log:
                watcher = subprocess.Popen(command + ['-C', str(local), 'watch', '--settle', '0.3',
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
            run('-C', str(local), 'history', 'notes.txt')
            run('-C', str(local), 'remove', 'notes.txt')
            assert file.read_bytes() == remote_final
            assert api.get(remote.id).content == remote_final
            print('Packaged HTTPS client passed: setup, local upload, foreground monitor, blank-file hold, external-editor save, remote pull, Ctrl+C, history, snapshots, and untracking.')
        finally:
            if watcher is not None and watcher.poll() is None:
                watcher.terminate()
                watcher.wait(timeout=10)
            server.shutdown()
            thread.join()
            server.server_close()


if __name__ == '__main__':
    main()
