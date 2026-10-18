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
            tag_file = root / 'errands tags.txt'
            many_tags = [f'shop{number:04d}' for number in range(900)]
            tag_file.write_text('\n'.join(many_tags), encoding='utf-8')
            saved = json.loads(run('-C', str(verified), 'errands', 'tags', '--set-file', str(tag_file), '--json'))
            assert saved['hashtags'] == api.errands()['hashtags'] == many_tags
            tail_id = str(uuid.uuid4())
            api.put(tail_id, 'last-tag.txt', f'[ ] Last tag #{many_tags[-1]}\n'.encode(), None, str(uuid.uuid4()))
            matches = json.loads(run('-C', str(verified), 'errands', '--json'))['results']
            assert len(matches) == 1 and matches[0]['id'] == tail_id
            assert matches[0]['matched_tags'] == [many_tags[-1]]
            tag_file.write_bytes(b'\xef\xbb\xbf#Chemist\r\ngroceries\r\n\r\n')
            run('-C', str(verified), 'errands', 'tags', '--set-file', str(tag_file))
            tag_file.write_text('#postoffice\n#CHEMIST\n', encoding='utf-8')
            run('-C', str(verified), 'errands', 'tags', '--add-file', str(tag_file))
            assert api.errands()['hashtags'] == ['chemist', 'groceries', 'postoffice']
            tag_file.write_text('#postoffice\n', encoding='utf-8')
            run('-C', str(verified), 'errands', 'tags', '--remove-file', str(tag_file))
            assert api.errands()['hashtags'] == ['chemist', 'groceries']
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
                    '--interval', '0.05', '--remote-interval', '120'], env=env, stdout=log, stderr=subprocess.STDOUT)
                wait_for(lambda: 'live:' in (root / 'watch.log').read_text())
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
            run('-C', str(verified), 'get', remote.id, 'notes.txt')
            with (root / 'verified-watch.log').open('w') as log:
                watcher = subprocess.Popen(command + ['-C', str(verified), 'watch', '--settle', '3',
                    '--interval', '5', '--remote-interval', '120'], env=env, stdout=log, stderr=subprocess.STDOUT)
                wait_for(lambda: 'live:' in (root / 'verified-watch.log').read_text())
                # Let the initial ready reconciliation finish, then a later save
                # must be delivered through the socket, not the 120-second timer.
                wait_for(lambda: 'synced:' in (root / 'verified-watch.log').read_text())
                saved = api.get(remote.id)
                verified_final = remote_final + b'Live update with a trusted CA\r\n'
                api.put(remote.id, remote.name, verified_final, saved.revision, str(uuid.uuid4()))
                wait_for(lambda: (verified / 'notes.txt').read_bytes() == verified_final, timeout=2)
                saved = api.get(remote.id)
                verified_next = verified_final + b'Another immediate live update\r\n'
                api.put(remote.id, remote.name, verified_next, saved.revision, str(uuid.uuid4()))
                # A completed download must not restart the 3-second editor
                # settling wait or defer a socket hint to the 5-second local tick.
                wait_for(lambda: (verified / 'notes.txt').read_bytes() == verified_next, timeout=2)
                watcher.send_signal(signal.SIGINT)
                assert watcher.wait(timeout=10) == 130
            # Concurrent edits must merge through the actual packaged watcher,
            # retaining both changes, while overlapping edits remain held.
            merge_path = verified / 'notes.txt'
            local_merge = verified_next.replace(b'Line 0:', b'Line ZERO:')
            merge_path.write_bytes(local_merge)
            remote_addition = b'Remote addition to merge with local edits\r\n'
            saved = api.get(remote.id)
            api.put(remote.id, remote.name, verified_next + remote_addition, saved.revision, str(uuid.uuid4()))
            expected_merge = local_merge + remote_addition
            with (root / 'merge-watch.log').open('w') as log:
                watcher = subprocess.Popen(command + ['-C', str(verified), 'watch', '--settle', '0.1',
                    '--interval', '0.05', '--remote-interval', '120'], env=env, stdout=log, stderr=subprocess.STDOUT)
                wait_for(lambda: 'merged:' in (root / 'merge-watch.log').read_text())
                assert merge_path.read_bytes() == api.get(remote.id).content == expected_merge
                local_overlap = expected_merge.replace(b'Line ZERO:', b'Line LOCAL:')
                remote_overlap = expected_merge.replace(b'Line ZERO:', b'Line REMOTE:')
                merge_path.write_bytes(local_overlap)
                saved = api.get(remote.id)
                api.put(remote.id, remote.name, remote_overlap, saved.revision, str(uuid.uuid4()))
                wait_for(lambda: 'conflict:' in (root / 'merge-watch.log').read_text())
                assert merge_path.read_bytes() == local_overlap
                assert api.get(remote.id).content == remote_overlap
                watcher.send_signal(signal.SIGINT)
                assert watcher.wait(timeout=10) == 130
            print('Packaged HTTPS client passed: verified defaults, explicit certificate/hostname bypass, per-invocation flags, setup, upload, errands settings and tag-file imports with 900 tags, line/file matching, closed-task filters, local overrides, offline search without a token, foreground monitor, blank-file hold, external-editor save, live WebSocket remote pulls with trusted CA and insecure modes, three-way watch merges, overlapping conflict holds, Ctrl+C, history, snapshots, and untracking.')
        finally:
            if watcher is not None and watcher.poll() is None:
                watcher.terminate()
                watcher.wait(timeout=10)
            server.shutdown()
            thread.join()
            server.server_close()


if __name__ == '__main__':
    main()
