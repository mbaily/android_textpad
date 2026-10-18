"""Real upgrade/framing checks for the dependency-free notification transport."""
import base64
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import socket
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from textpad_sync import SyncError
from textpad_sync.api import Api
from textpad_sync.events import ChangeEvents, EventSocket, GUID, MAX_MESSAGE

TOKEN = 'disposable-event-test-token-32-characters'


def frame(payload, opcode=1, final=True):
    header = bytes(((0x80 if final else 0) | opcode,))
    if len(payload) < 126:
        return header + bytes((len(payload),)) + payload
    return header + b'\x7e' + len(payload).to_bytes(2, 'big') + payload


def client_frame(handler):
    header = handler.rfile.read(2)
    if len(header) != 2 or not header[1] & 128 or header[1] & 127 > 125:
        raise OSError('Expected a masked client control frame')
    mask = handler.rfile.read(4)
    payload = handler.rfile.read(header[1] & 127)
    return header[0] & 15, bytes(value ^ mask[index % 4] for index, value in enumerate(payload))


@contextmanager
def upgrade_server(script=lambda handler: None, status=101, **headers):
    requests = []
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def do_GET(self):
            self.connection.settimeout(3)
            requests.append((self.path, self.headers))
            key = self.headers.get('Sec-WebSocket-Key', '')
            accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
            self.send_response(status)
            response_headers = {'Upgrade': 'websocket', 'Connection': 'Upgrade',
                                'Sec-WebSocket-Accept': accept, **headers}
            for name, value in response_headers.items():
                self.send_header(name, value)
            self.send_header('Content-Length', '0')
            self.end_headers()
            self.wfile.flush()
            script(self)
            self.close_connection = True
        def log_message(self, *args):
            pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', requests
    finally:
        server.shutdown()
        thread.join()
        server.server_close()


class EventTransportTests(unittest.TestCase):
    def connect(self, base):
        api = Api(base, TOKEN, 'notification-test', local_test=True)
        connection = EventSocket(api)
        self.addCleanup(connection.close)
        connection.connect()
        return connection

    def buffered(self, data):
        connection = EventSocket(None)
        connection.stream = io.BytesIO(data)
        return connection

    def test_upgrade_authenticates_the_prefixed_endpoint_and_receives_buffered_events(self):
        def script(handler):
            handler.connection.sendall(frame(b'{"type":"ready","cursor":"12"}'))
        with upgrade_server(script) as (base, requests):
            connection = self.connect(base + '/textpad')
            self.assertEqual({'type': 'ready', 'cursor': '12'}, connection.receive())
            path, headers = requests[0]
            self.assertEqual('/textpad/api/v1/events', path)
            self.assertEqual('Bearer ' + TOKEN, headers['Authorization'])
            self.assertEqual('notification-test', headers['X-Textpad-Client'])
            self.assertNotIn(TOKEN, path)
            self.assertEqual(16, len(base64.b64decode(headers['Sec-WebSocket-Key'])))

    def test_bad_upgrades_and_redirects_are_refused_without_forwarding_credentials(self):
        for status, headers in ((401, {}), (302, {'Location': '/credential-trap'}),
                                (101, {'Sec-WebSocket-Accept': 'incorrect'}),
                                (101, {'Connection': 'keep-alive'}),
                                (101, {'Upgrade': 'other'}),
                                (101, {'Sec-WebSocket-Extensions': 'permessage-deflate'}),
                                (101, {'Sec-WebSocket-Protocol': 'unexpected'})):
            with self.subTest(status=status, headers=headers):
                with upgrade_server(status=status, **headers) as (base, requests):
                    with self.assertRaises(SyncError):
                        self.connect(base)
                    self.assertEqual(1, len(requests))

    def test_fragmented_text_and_interleaved_ping_produce_a_masked_pong(self):
        received, complete = [], threading.Event()
        def script(handler):
            try:
                handler.connection.sendall(frame(b'{"type":"changed",', final=False)
                    + frame(b'ping bytes', opcode=9) + frame(b'"cursor":"13"}', opcode=0))
                header = handler.rfile.read(2)
                mask = handler.rfile.read(4)
                payload = handler.rfile.read(header[1] & 127)
                received.append((header, bytes(value ^ mask[index % 4] for index, value in enumerate(payload))))
            finally:
                complete.set()
        with upgrade_server(script) as (base, _):
            connection = self.connect(base)
            self.assertEqual({'type': 'changed', 'cursor': '13'}, connection.receive())
            self.assertTrue(complete.wait(3))
            self.assertEqual([(b'\x8a\x8a', b'ping bytes')], received)

    def test_extended_length_and_fragmented_unicode_are_supported(self):
        data = b'{"type":"ready","cursor":"1","padding":"' + b'x' * 200 + b'"}'
        self.assertEqual('ready', self.buffered(frame(data)).receive()['type'])
        self.assertEqual({'text': '😀'}, self.buffered(frame(b'{"text":"\xf0\x9f', final=False)
                         + frame(b'\x98\x80"}', opcode=0)).receive())

    def test_client_heartbeats_keep_a_quiet_connection_live(self):
        received, release = [], threading.Event()
        def script(handler):
            for _ in range(3):
                opcode, payload = client_frame(handler)
                received.append((opcode, payload))
                handler.connection.sendall(frame(payload, opcode=10))
            handler.connection.sendall(frame(b'{"type":"changed","cursor":"2"}'))
            release.wait(3)
        with (patch('textpad_sync.events.PING_INTERVAL', 0.02),
              patch('textpad_sync.events.PONG_TIMEOUT', 0.5),
              upgrade_server(script) as (base, _)):
            connection = self.connect(base)
            try:
                self.assertEqual({'type': 'changed', 'cursor': '2'}, connection.receive())
                self.assertEqual([9, 9, 9], [opcode for opcode, _ in received])
                self.assertEqual(3, len({payload for _, payload in received}))
                self.assertFalse(connection.stopped.is_set())
            finally:
                connection.close()
                release.set()
            self.assertFalse(connection.heartbeat.is_alive())

    def test_missing_matching_pong_interrupts_even_an_incomplete_frame(self):
        for response in (b'', frame(b'wrong pong', opcode=10),
                         frame(b'server ping', opcode=9), b'\x81\x7e\x01\x00{'):
            with self.subTest(response=response):
                release, finished = threading.Event(), threading.Event()
                errors = []
                def script(handler):
                    client_frame(handler)
                    handler.connection.sendall(response)
                    # Leave TCP open: no close frame, EOF, or socket error.
                    release.wait(3)
                with (patch('textpad_sync.events.PING_INTERVAL', 0.02),
                      patch('textpad_sync.events.PONG_TIMEOUT', 0.1),
                      upgrade_server(script) as (base, _)):
                    connection = self.connect(base)
                    def receive():
                        try:
                            connection.receive()
                        except (SyncError, OSError) as error:
                            errors.append(error)
                        finally:
                            finished.set()
                    listener = threading.Thread(target=receive, daemon=True)
                    listener.start()
                    try:
                        self.assertTrue(finished.wait(2), 'Silent connection did not fail promptly')
                        self.assertTrue(errors, 'Receive accepted a connection without a matching pong')
                        self.assertTrue(connection.stopped.wait(1), 'Heartbeat did not interrupt the socket')
                    finally:
                        connection.close()
                        release.set()
                        listener.join(2)
                    self.assertFalse(listener.is_alive())
                    self.assertFalse(connection.heartbeat.is_alive())

    def test_silent_connection_reports_polling_then_reconnects_and_reconciles(self):
        release, ready, polling = threading.Event(), threading.Event(), threading.Event()
        hints, statuses, connections = [], [], []
        def script(handler):
            connections.append(handler.connection)
            handler.connection.sendall(frame(b'{"type":"ready","cursor":"2"}'))
            # The first socket stays open but silently discards client pings.
            release.wait(4)
        def changed():
            hints.append(True)
            ready.set()
        def wake():
            status = events.status()
            statuses.append(status)
            if status == 'polling':
                polling.set()
        with (patch('textpad_sync.events.PING_INTERVAL', 0.02),
              patch('textpad_sync.events.PONG_TIMEOUT', 0.1),
              upgrade_server(script) as (base, _)):
            events = ChangeEvents(Api(base, TOKEN, 'silent-reconnect-test', local_test=True), changed, wake)
            try:
                with events:
                    self.assertTrue(ready.wait(3))
                    ready.clear()
                    self.assertTrue(polling.wait(3))
                    self.assertEqual(1, len(connections))
                    self.assertTrue(ready.wait(3), 'Reconnection did not request a fresh reconciliation')
                    self.assertEqual(2, len(connections))
                    self.assertEqual(2, len(hints))
                    self.assertEqual(['live', 'polling', 'live'], statuses)
            finally:
                release.set()
            self.assertFalse(events.thread.is_alive())

    def test_close_stops_a_heartbeat_waiting_for_pong(self):
        pinged, release = threading.Event(), threading.Event()
        def script(handler):
            client_frame(handler)
            pinged.set()
            release.wait(3)
        with (patch('textpad_sync.events.PING_INTERVAL', 0.02),
              upgrade_server(script) as (base, _)):
            connection = self.connect(base)
            try:
                self.assertTrue(pinged.wait(3))
                connection.close()
                self.assertFalse(connection.heartbeat.is_alive())
            finally:
                release.set()

    def test_server_close_is_acknowledged_and_invalid_close_payloads_are_refused(self):
        closing = b'\x03\xe8done'
        connection = self.buffered(frame(closing, opcode=8))
        with patch.object(connection, 'control') as control:
            with self.assertRaises(SyncError):
                connection.receive()
        control.assert_called_once_with(8, closing)
        for closing in (b'x', b'\x03\xed', b'\x03\xe8\xff'):
            with self.subTest(closing=closing):
                connection = self.buffered(frame(closing, opcode=8))
                with patch.object(connection, 'control') as control:
                    with self.assertRaises((SyncError, ValueError)):
                        connection.receive()
                control.assert_not_called()

    def test_malformed_frames_and_unbounded_messages_are_rejected(self):
        invalid = [b'\x81\x80', b'\xc1\x00', b'\x82\x00', b'\x09\x00', b'\x89\x7e',
                   b'\x81\x7f', b'\x81\x7e\x00\x01x', b'\x81\x7e\x10\x01',
                   frame(b'x' * MAX_MESSAGE, final=False) + frame(b'x', opcode=0),
                   frame(b'{}', opcode=0), frame(b'{', final=False) + frame(b'}'),
                   frame(b'\xff'), frame(b'[]'), frame(b'{invalid'), b'\x81\x02x', b'']
        for data in invalid:
            with self.subTest(data=data[:10]):
                with self.assertRaises((SyncError, ValueError)):
                    self.buffered(data).receive()

    def test_interrupt_unblocks_a_listener_waiting_for_server_frames(self):
        left, right = socket.socketpair()
        stream = left.makefile('rb')
        connection = EventSocket(None)
        connection.connection = SimpleNamespace(sock=left, close=left.close)
        connection.stream = stream
        finished = threading.Event()
        def receive():
            try:
                connection.receive()
            except (OSError, SyncError):
                pass
            finally:
                finished.set()
        thread = threading.Thread(target=receive, daemon=True)
        thread.start()
        try:
            connection.interrupt()
            self.assertTrue(finished.wait(2))
        finally:
            right.close()
            connection.close()
            stream.close()
            thread.join(2)

    def test_http_connect_proxy_keeps_api_credentials_out_of_the_tunnel_request(self):
        api = Api('https://example.invalid/textpad', TOKEN, 'notification-test')
        api.proxies = {'https': 'http://proxy-user:proxy-password@proxy.invalid:8080'}
        response = SimpleNamespace(status=101, begin=lambda: None, close=lambda: None, fp=io.BytesIO())
        def header(name, default=None):
            headers = {'Upgrade': 'websocket', 'Connection': 'Upgrade',
                'Sec-WebSocket-Accept': base64.b64encode(hashlib.sha1((base64.b64encode(b'x' * 16).decode() + GUID).encode()).digest()).decode()}
            return headers.get(name, default)
        response.getheader = header
        with (patch('textpad_sync.events.HTTPSConnection') as https,
              patch('textpad_sync.events.HTTPResponse', return_value=response),
              patch('textpad_sync.events.proxy_bypass', return_value=False),
              patch('textpad_sync.events.os.urandom', return_value=b'x' * 16)):
            connection = EventSocket(api)
            self.addCleanup(connection.close)
            connection.connect()
        self.assertIs(api.ssl_context, https.call_args.kwargs['context'])
        self.assertEqual(('proxy.invalid', 8080), https.call_args.args)
        tunnel = https.return_value.set_tunnel
        self.assertEqual(('example.invalid', 443), tunnel.call_args.args)
        self.assertEqual(['Proxy-Authorization'], list(tunnel.call_args.kwargs['headers']))
        self.assertNotIn(TOKEN, str(tunnel.call_args))


if __name__ == '__main__':
    unittest.main()
