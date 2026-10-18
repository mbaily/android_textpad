"""Opt-in filesystem-client writes must check revisions inside the transaction."""
from base64 import b64encode
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import unittest
import uuid

from textpad_server import create_app
from textpad_server.store import RevisionConflict, Store


class ConditionalStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name) / 'server.sqlite3')

    def tearDown(self):
        self.temp.cleanup()

    def put(self, content, base=None, request=None, guarded=True):
        return self.store.put('doc', 'notes.txt', content, base, request or str(uuid.uuid4()),
                              require_base_revision=guarded)

    def test_only_one_concurrent_writer_can_accept_the_same_base(self):
        first = self.put(b'Original\n')
        def save(number):
            try:
                return self.put(f'Writer {number}\n'.encode(), first['revision'])
            except RevisionConflict:
                return None
        with ThreadPoolExecutor(max_workers=8) as workers:
            results = list(workers.map(save, range(8)))
        accepted = [result for result in results if result is not None]
        self.assertEqual(1, len(accepted))
        self.assertEqual(2, len(self.store.revisions('doc')))
        self.assertEqual(accepted[0]['revision'], self.store.get('doc')[0]['revision'])

    def test_rejected_write_has_no_receipt_or_revision_or_notification(self):
        first = self.put(b'First\n')
        self.put(b'Latest\n', first['revision'])
        notifications = []
        self.store.on_commit = notifications.append
        with self.assertRaises(RevisionConflict):
            self.put(b'Stale\n', first['revision'], request='stale')
        self.assertFalse(self.store.has_receipt('doc', 'stale'))
        self.assertEqual(2, len(self.store.revisions('doc')))
        self.assertEqual([], notifications)

    def test_lost_response_retry_precedes_revision_check(self):
        first = self.put(b'First\n', request='accepted')
        newer = self.put(b'Latest\n', first['revision'])
        self.assertEqual(first, self.put(b'First\n', request='accepted'))
        self.assertEqual(newer['revision'], self.store.get('doc')[0]['revision'])

    def test_existing_clients_keep_last_write_wins_behavior(self):
        first = self.put(b'First\n')
        self.put(b'Second\n', first['revision'])
        accepted = self.put(b'Offline replacement\n', first['revision'], guarded=False)
        self.assertEqual(accepted['revision'], self.store.get('doc')[0]['revision'])
        self.assertEqual(b'Offline replacement\n', self.store.get('doc')[1])


class ConditionalApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.token = 'conditional-test-token-at-least-32-characters'
        self.app = create_app({'TESTING': True, 'DATABASE': str(Path(self.temp.name) / 'server.sqlite3'),
            'API_TOKEN': self.token, 'SECRET_KEY': 'conditional-test-session-secret',
            'PUBLIC_URL': 'http://localhost', 'SESSION_COOKIE_SECURE': False})
        self.client = self.app.test_client()
        self.headers = {'Authorization': 'Bearer ' + self.token}

    def tearDown(self):
        self.temp.cleanup()

    def test_capability_requires_authentication(self):
        self.assertEqual(401, self.client.get('/api/v1/capabilities').status_code)
        response = self.client.get('/api/v1/capabilities', headers=self.headers)
        self.assertEqual({'conditional_put': True, 'max_document_bytes': 10485760}, response.json)

    def test_flag_validation_and_conflict_code(self):
        payload = {'name': 'notes.txt', 'content_base64': b64encode(b'Content\n').decode(),
                   'request_id': 'first', 'base_revision': None, 'require_base_revision': True}
        first = self.client.put('/api/v1/documents/doc', headers=self.headers, json=payload)
        self.assertEqual(200, first.status_code)
        for invalid in ('true', 1, None, []):
            payload.update(request_id='invalid', require_base_revision=invalid)
            self.assertEqual(400, self.client.put('/api/v1/documents/doc', headers=self.headers, json=payload).status_code)
        payload.update(request_id='stale', require_base_revision=True)
        rejected = self.client.put('/api/v1/documents/doc', headers=self.headers, json=payload)
        self.assertEqual(409, rejected.status_code)
        self.assertEqual('revision_conflict', rejected.json['code'])
        payload.update(request_id='second', base_revision=first.json['revision'])
        self.assertEqual(200, self.client.put('/api/v1/documents/doc', headers=self.headers, json=payload).status_code)
