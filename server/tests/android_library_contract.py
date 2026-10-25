"""Run the native HTTPS library contract on an emulator against a temporary database.

Install the debug APK and instrumentation APK first. Requires openssl and adb.
"""
import argparse
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import json

from textpad_server import create_app
from werkzeug.serving import make_server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5556')
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path.home() / '.cache/android-textpad/sdk/platform-tools/adb'))
    args = parser.parse_args()
    command = [args.adb, '-s', args.serial]
    package = 'org.hopto.git23069.textpad'
    with tempfile.TemporaryDirectory(prefix='textpad-native-library-') as directory:
        directory = Path(directory)
        cert, key = directory / 'ca.crt', directory / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
            '-subj', '/CN=Textpad isolated Android test', '-addext', 'subjectAltName=IP:10.0.2.2,IP:127.0.0.1',
            '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign',
            '-keyout', str(key), '-out', str(cert)], check=True, capture_output=True)
        token = secrets.token_urlsafe(32)
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        endpoint = f'https://10.0.2.2:{port}'
        app = create_app({'DATABASE': str(directory / 'library.sqlite3'), 'PUBLIC_URL': endpoint,
            'API_TOKEN': token, 'SECRET_KEY': secrets.token_urlsafe(32)})
        server = make_server('127.0.0.1', port, app, threaded=True, ssl_context=(str(cert), str(key)))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for name, data in [('library-http-test.json', json.dumps({'endpoint': endpoint, 'token': token}).encode()),
                               ('library-http-ca.crt', cert.read_bytes())]:
                subprocess.run([*command, 'shell', 'run-as', package, 'tee', 'files/' + name], input=data,
                               stdout=subprocess.DEVNULL, check=True)
            result = subprocess.run([*command, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', package + '.LibraryHttpInstrumentedTest', '-e', 'libraryHttpFixture', 'true',
                package + '.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, timeout=120)
            print(result.stdout)
            if result.returncode or 'OK (1 test)' not in result.stdout:
                raise RuntimeError('Native HTTPS library contract failed')
        finally:
            subprocess.run([*command, 'shell', 'run-as', package, 'rm', '-f', 'files/library-http-test.json', 'files/library-http-ca.crt'],
                           capture_output=True)
            server.shutdown()
            thread.join()
            server.server_close()
    print('Native HTTPS library contract passed with an isolated database and CA; hostname verification remained enabled.')


if __name__ == '__main__':
    main()
