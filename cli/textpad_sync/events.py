"""Bounded RFC 6455 change hints, using only the standard library.

This is a receive-only notification client, not a document transport. TLS and
credentials match the HTTPS API; redirects and WebSocket extensions are refused.
"""
import base64
from contextlib import suppress
import hashlib
from http.client import HTTPConnection, HTTPSConnection, HTTPException, HTTPResponse
import json
import os
import socket
import threading
from urllib.parse import unquote, urlsplit
from urllib.request import proxy_bypass

from . import SyncError

MAX_MESSAGE = 4096
GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
PING_INTERVAL = 5
PONG_TIMEOUT = 5
MAX_RECONNECT_DELAY = 10


class EventSocket:
    def __init__(self, api):
        self.api = api
        self.connection = self.response = self.stream = None
        self.send_lock, self.ping_lock = threading.Lock(), threading.Lock()
        self.stopped, self.pong = threading.Event(), threading.Event()
        self.pending_ping = self.heartbeat = None

    def connect(self):
        parts = urlsplit(self.api.server)
        secure = parts.scheme == 'https'
        host, port = parts.hostname, parts.port or (443 if secure else 80)
        proxy = self.api.proxies.get(parts.scheme) if not proxy_bypass(parts.netloc) else None
        target_host, target_port, tunnel_headers = host, port, {}
        if proxy:
            address = urlsplit(proxy if '://' in proxy else 'http://' + proxy)
            if address.scheme != 'http' or not address.hostname:
                raise SyncError('Live updates require an HTTP CONNECT proxy.')
            target_host, target_port = address.hostname, address.port or 80
            if address.username is not None:
                credentials = unquote(address.username) + ':' + unquote(address.password or '')
                tunnel_headers['Proxy-Authorization'] = 'Basic ' + base64.b64encode(credentials.encode()).decode()
        options = {'timeout': min(self.api.timeout, MAX_RECONNECT_DELAY)}
        if secure:
            options['context'] = self.api.ssl_context
        connection = (HTTPSConnection if secure else HTTPConnection)(target_host, target_port, **options)
        self.connection = connection
        if proxy:
            connection.set_tunnel(host, port, headers=tunnel_headers)
        key = base64.b64encode(os.urandom(16)).decode('ascii')
        connection.request('GET', parts.path + '/api/v1/events', headers={
            'Authorization': 'Bearer ' + self.api.token, 'X-Textpad-Client': self.api.client_id,
            'Upgrade': 'websocket', 'Connection': 'Upgrade',
            'Sec-WebSocket-Key': key, 'Sec-WebSocket-Version': '13',
        })
        # Read the upgrade directly: the HTTP body API treats status 101 as empty.
        self.response = response = HTTPResponse(connection.sock)
        response.begin()
        expected = base64.b64encode(hashlib.sha1((key + GUID).encode('ascii')).digest()).decode('ascii')
        if (response.status != 101 or response.getheader('Upgrade', '').lower() != 'websocket'
                or 'upgrade' not in response.getheader('Connection', '').lower().replace(' ', '').split(',')
                or response.getheader('Sec-WebSocket-Accept', '').strip() != expected
                or response.getheader('Sec-WebSocket-Extensions') is not None
                or response.getheader('Sec-WebSocket-Protocol') is not None):
            raise SyncError('The server refused the live-update connection.')
        self.stream = response.fp
        # A Wi-Fi outage need not deliver a close/error to an established TCP
        # socket. Probe the full WebSocket path, including quiet connections.
        # A separate watchdog can interrupt a buffered read even mid-frame;
        # arbitrary incoming traffic cannot postpone a missing pong's deadline.
        connection.sock.settimeout(PING_INTERVAL + PONG_TIMEOUT)
        self.heartbeat = threading.Thread(target=self.keepalive, name='textpad-cli-heartbeat', daemon=True)
        self.heartbeat.start()

    def keepalive(self):
        while not self.stopped.wait(PING_INTERVAL):
            with self.ping_lock:
                self.pending_ping = os.urandom(8)
                self.pong.clear()
            try:
                self.control(9, self.pending_ping)
                if self.pong.wait(PONG_TIMEOUT):
                    continue
            except OSError:
                pass
            self.interrupt()
            return

    def read(self, size):
        data = self.stream.read(size)
        if len(data) != size:
            raise SyncError('The live-update connection closed.')
        return data

    def control(self, opcode, payload):
        mask = os.urandom(4)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        # The listener answers server pings while the watchdog sends its own.
        # Serialize complete frames so concurrent writes cannot interleave.
        with self.send_lock:
            self.connection.sock.sendall(bytes((0x80 | opcode, 0x80 | len(payload))) + mask + masked)

    def receive(self):
        message = bytearray()
        fragmented = False
        while True:
            first, second = self.read(2)
            final, opcode, length = bool(first & 0x80), first & 15, second & 127
            if first & 0x70 or second & 0x80 or opcode not in (0, 1, 8, 9, 10):
                raise SyncError('Invalid live-update WebSocket frame.')
            if opcode >= 8 and (not final or length > 125):
                raise SyncError('Invalid live-update control frame.')
            if length == 126:
                length = int.from_bytes(self.read(2), 'big')
                if length < 126:
                    raise SyncError('Invalid live-update frame length.')
            elif length == 127:
                # Even the smallest legal 64-bit frame exceeds our message limit.
                raise SyncError('The live-update message is too large.')
            if length > MAX_MESSAGE or (opcode < 8 and len(message) + length > MAX_MESSAGE):
                raise SyncError('The live-update message is too large.')
            payload = self.read(length)
            if opcode == 8:
                if length == 1:
                    raise SyncError('Invalid live-update close frame.')
                if length >= 2:
                    code = int.from_bytes(payload[:2], 'big')
                    if code < 1000 or code >= 5000 or code in (1004, 1005, 1006, 1015):
                        raise SyncError('Invalid live-update close code.')
                    payload[2:].decode('utf-8')
                self.control(8, payload)
                raise SyncError('The live-update connection closed.')
            if opcode == 9:
                self.control(10, payload)
                continue
            if opcode == 10:
                with self.ping_lock:
                    if payload == self.pending_ping:
                        self.pong.set()
                continue
            if (opcode == 0) != fragmented:
                raise SyncError('Invalid live-update message fragmentation.')
            message.extend(payload)
            if final:
                value = json.loads(message.decode('utf-8'))
                if not isinstance(value, dict):
                    raise SyncError('Invalid live-update notification.')
                return value
            fragmented = True

    def interrupt(self):
        self.stopped.set()
        self.pong.set()
        if self.connection is not None:
            sock = self.connection.sock
            if sock is not None:
                # Shutdown first, so closing the buffered reader cannot wait on
                # a receive in the listener thread when the user presses Ctrl+C.
                with suppress(OSError):
                    sock.shutdown(socket.SHUT_RDWR)

    def close(self):
        self.interrupt()
        if self.heartbeat is not None:
            self.heartbeat.join(timeout=2)
        if self.connection is not None:
            if self.response is not None:
                self.response.close()
            self.connection.close()


class ChangeEvents:
    """Reconnect in the background; only wake the caller, never touch its SQLite."""
    def __init__(self, api, changed, wake):
        self.api, self.changed, self.wake = api, changed, wake
        self.stopped = threading.Event()
        self.lock = threading.Lock()
        self.socket = self.pending_status = self.last_status = None
        self.thread = threading.Thread(target=self.run, name='textpad-cli-events', daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.stopped.set()
        with self.lock:
            connection = self.socket
        if connection is not None:
            connection.interrupt()
        self.thread.join(timeout=2)

    def status(self):
        with self.lock:
            value, self.pending_status = self.pending_status, None
            return value

    def set_status(self, value):
        with self.lock:
            if value == self.last_status:
                return
            self.last_status = self.pending_status = value
        self.wake()

    def run(self):
        delay = 1
        while not self.stopped.is_set():
            connection = EventSocket(self.api)
            with self.lock:
                if self.stopped.is_set():
                    return
                self.socket = connection
            try:
                connection.connect()
                while not self.stopped.is_set():
                    value = connection.receive()
                    if self.stopped.is_set():
                        return
                    if (value.get('type') in ('ready', 'changed')
                            and isinstance(value.get('cursor'), str)
                            and value['cursor'].isascii() and value['cursor'].isdigit()):
                        delay = 1
                        self.set_status('live')
                        self.changed()
            except (SyncError, OSError, HTTPException, ValueError, RecursionError):
                if not self.stopped.is_set():
                    self.set_status('polling')
            finally:
                connection.close()
                with self.lock:
                    self.socket = None
            if self.stopped.wait(delay):
                return
            delay = min(MAX_RECONNECT_DELAY, delay * 2)
