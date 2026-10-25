"""Logical folders, hierarchy integrity, additive migration, and API compatibility."""
from base64 import b64encode
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

from textpad_server import create_app
from textpad_server.models import Folder
from textpad_server.store import NotFound, RequestConflict, SCHEMA_VERSION, Store


class FolderStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'library.sqlite3'
        self.store = Store(self.path)

    def file(self, document_id='doc', content=b'Original\r\n'):
        return self.store.put(document_id, 'notes.txt', content, None, 'first-' + document_id)

    def test_models_and_sibling_names_normalize_without_changing_folder_ids(self):
        folder = self.store.put_folder('home', ' Cafe\u0301 ')
        self.assertIsInstance(folder, Folder)
        self.assertEqual('Café', folder.name)
        self.assertIsNone(folder.parent_id)
        self.assertEqual(folder, self.store.put_folder('home', 'Café'))
        with self.assertRaises(RequestConflict):
            self.store.put_folder('duplicate', 'CAFÉ')
        self.store.put_folder('parent', 'Parent')
        child = self.store.put_folder('child', 'Café', 'parent')
        renamed = self.store.put_folder('child', 'Renamed')
        self.assertEqual('parent', renamed.parent_id)  # An omitted parent preserves placement.
        self.assertEqual(child.id, renamed.id)
        self.assertEqual(child.created_at, renamed.created_at)
        self.assertNotEqual(child.updated_at, renamed.updated_at)
        self.assertEqual(['home', 'parent'], [f.id for f in self.store.folders(None)])
        self.assertEqual(['child'], [f.id for f in self.store.folders('parent')])
        for name in ('', '  ', '.', '..', 'a/b', 'a\\b', 'line\nbreak', 'a' * 256, '\ud800'):
            with self.assertRaises(ValueError, msg=repr(name)):
                self.store.put_folder('invalid', name)

    def test_files_and_children_browse_one_level_and_moves_preserve_bytes_and_history(self):
        raw = b'\xff\xfe' + 'Notes 😀\r\n'.encode('utf-16-le')
        first = self.file(content=raw)
        self.file('top')
        self.store.put_folder('home', 'Home')
        self.store.put_folder('child', 'Child', 'home')
        moved = self.store.move_document('doc', 'child')
        self.assertEqual(first['revision'], moved['revision'])
        self.assertEqual(first['sha256'], moved['sha256'])
        self.assertEqual('child', moved['folder_id'])
        self.assertEqual(['top'], [d['id'] for d in self.store.folder_contents()['documents']])
        self.assertEqual(['home'], [f['id'] for f in self.store.folder_contents()['folders']])
        self.assertEqual([], self.store.folder_contents('home')['documents'])
        self.assertEqual(['child'], [f['id'] for f in self.store.folder_contents('home')['folders']])
        self.assertEqual(['doc'], [d['id'] for d in self.store.folder_contents('child')['documents']])
        self.store.put_folder('home', 'Renamed')
        self.store.put_folder('child', 'Child', None)
        self.assertEqual('child', self.store.get('doc')[0]['folder_id'])
        self.assertEqual(raw, self.store.get('doc')[1])
        self.assertEqual(1, len(self.store.revisions('doc')))
        self.store.move_document('doc', None)
        self.assertIsNone(self.store.get('doc')[0]['folder_id'])

    def test_old_content_saves_restores_and_receipt_replays_keep_current_placement(self):
        first = self.file()
        self.store.put_folder('folder', 'Folder')
        self.store.move_document('doc', 'folder')
        # Replaying an upload receipt must not move the document back to root.
        self.assertEqual(first, self.file())
        second = self.store.put('doc', 'renamed.txt', b'Edited\r\n', first['revision'], 'second')
        self.assertEqual('folder', second['folder_id'])
        restored = self.store.put('doc', 'notes.txt', b'Original\r\n', second['revision'], 'restore', source='restore')
        self.assertEqual('folder', restored['folder_id'])
        self.assertEqual({'folder'}, {d['folder_id'] for d in self.store.revisions('doc')})
        self.assertEqual('folder', self.store.get_meta('doc', first['revision'])['folder_id'])
        self.assertEqual('folder', self.store.list()[0]['folder_id'])

    def test_cycles_missing_parents_and_nonempty_deletion_are_rejected_atomically(self):
        self.store.put_folder('a', 'A')
        self.store.put_folder('b', 'B', 'a')
        self.store.put_folder('c', 'C', 'b')
        before = self.store.folders()
        for parent in ('a', 'b', 'c'):
            with self.assertRaises(ValueError):
                self.store.put_folder('a', 'A', parent)
        with self.assertRaises(NotFound):
            self.store.put_folder('missing', 'Missing', 'unknown')
        self.assertEqual(before, self.store.folders())
        with self.assertRaises(RequestConflict):
            self.store.delete_folder('a')
        self.file()
        self.store.move_document('doc', 'c')
        with self.assertRaises(RequestConflict):
            self.store.delete_folder('c')
        with self.assertRaises(NotFound):
            self.store.move_document('doc', 'unknown')
        self.assertEqual('c', self.store.get('doc')[0]['folder_id'])
        self.store.move_document('doc', None)
        for folder_id in ('c', 'b', 'a'):
            self.store.delete_folder(folder_id)
            self.store.delete_folder(folder_id)  # Retrying deletion is harmless.
        self.assertEqual([], self.store.folders())
        self.assertEqual(b'Original\r\n', self.store.get('doc')[1])

    def test_database_enforces_references_cycles_names_and_stable_ids(self):
        self.store.put_folder('a', 'A')
        self.store.put_folder('b', 'B', 'a')
        self.file()
        self.store.move_document('doc', 'b')
        statements = [
            ("UPDATE folders SET parent_id='b' WHERE id='a'", ()),
            ("UPDATE folders SET parent_id='a' WHERE id='a'", ()),
            ("UPDATE folders SET parent_id='missing' WHERE id='b'", ()),
            ("UPDATE documents SET folder_id='missing' WHERE id='doc'", ()),
            ("DELETE FROM folders WHERE id='a'", ()),
            ("DELETE FROM folders WHERE id='b'", ()),
            ("UPDATE folders SET id='new-id' WHERE id='b'", ()),
            ("INSERT INTO folders VALUES('other','A','a',NULL,'c','u')", ()),
            ("INSERT INTO folders VALUES('x','X','x','y','c','u'),('y','Y','y','x','c','u')", ()),
        ]
        with closing(self.store.connect()) as db:
            for sql, params in statements:
                with self.assertRaises(sqlite3.IntegrityError, msg=sql):
                    db.execute(sql, params)
            self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
        self.assertEqual(['a', 'b'], [f.id for f in self.store.folder_path('b')])

    def test_more_than_a_thousand_levels_use_flat_models_and_iterative_paths(self):
        parent = None
        for depth in range(1100):
            folder = self.store.put_folder('depth-' + str(depth), 'Folder', parent)
            parent = folder.id
        path = self.store.folder_path(parent)
        self.assertEqual(1100, len(path))
        self.assertEqual('depth-0', path[0].id)
        self.assertEqual('depth-1099', path[-1].id)
        self.file()
        self.store.move_document('doc', parent)
        self.assertEqual(1100, len(self.store.folders()))
        self.assertEqual('doc', self.store.folder_contents(parent)['documents'][0]['id'])
        with self.assertRaises(ValueError):
            self.store.put_folder('depth-0', 'Folder', parent)

    def test_concurrent_opposite_moves_cannot_create_a_cycle(self):
        self.store.put_folder('a', 'A')
        self.store.put_folder('b', 'B')
        def move(pair):
            try:
                self.store.put_folder(pair[0], pair[0].upper(), pair[1])
                return True
            except ValueError:
                return False
        with ThreadPoolExecutor(max_workers=2) as workers:
            accepted = list(workers.map(move, [('a', 'b'), ('b', 'a')]))
        self.assertEqual(1, sum(accepted))
        self.assertEqual(1, len(self.store.folders(None)))

    def test_only_successful_changed_operations_notify_and_advance_numeric_cursor(self):
        calls = []
        self.store.on_commit = lambda: calls.append(self.store.change_cursor())
        self.store.put_folder('home', 'Home')
        cursor = self.store.change_cursor()
        self.store.put_folder('home', 'Home')
        with self.assertRaises(ValueError):
            self.store.put_folder('home', 'Home', 'home')
        self.assertEqual([cursor], calls)
        self.file()
        self.store.move_document('doc', 'home')
        self.store.move_document('doc', 'home')
        with self.assertRaises(RequestConflict):
            self.store.delete_folder('home')
        self.store.move_document('doc', None)
        self.store.delete_folder('home')
        self.store.delete_folder('home')
        self.assertEqual(5, len(calls))
        self.assertEqual(sorted(set(map(int, calls))), list(map(int, calls)))

    def test_legacy_database_migrates_without_changing_contents_history_settings_or_receipts(self):
        path = Path(self.temp.name) / 'legacy.sqlite3'
        raw = b'\xef\xbb\xbf' + 'Keep 😀\r\n'.encode()
        digest = hashlib.sha256(raw).hexdigest()
        receipt = dict(id='legacy', name='old.txt', revision='1', sha256=digest, size=len(raw), encoding='utf-8')
        request_hash = hashlib.sha256(b'old.txt\0' + raw).hexdigest()
        with closing(sqlite3.connect(path)) as db:
            db.executescript('''
                CREATE TABLE documents(id TEXT PRIMARY KEY,current_revision INTEGER);
                CREATE TABLE revisions(sequence INTEGER PRIMARY KEY AUTOINCREMENT,document_id TEXT NOT NULL,
                    name TEXT NOT NULL,content BLOB NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL,
                    encoding TEXT NOT NULL,created_at TEXT NOT NULL);
                CREATE TABLE receipts(document_id TEXT,request_id TEXT,request_hash TEXT,response TEXT,
                    PRIMARY KEY(document_id,request_id));
                CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL,updated_at TEXT NOT NULL);
                INSERT INTO documents VALUES('legacy',1);
                INSERT INTO settings VALUES('errands','["chemist"]','original-time');
                PRAGMA user_version=1;
            ''')
            db.execute('INSERT INTO revisions VALUES(1,?,?,?,?,?,?,?)', ('legacy', 'old.txt', raw, digest, len(raw), 'utf-8', 'original-time'))
            db.execute('INSERT INTO receipts VALUES(?,?,?,?)', ('legacy', 'original', request_hash, json.dumps(receipt)))
            db.commit()
        # Multiple workers starting together serialize the additive upgrade.
        with ThreadPoolExecutor(max_workers=3) as workers:
            stores = list(workers.map(lambda _: Store(path), range(3)))
        migrated = stores[0]
        self.assertIsNone(migrated.get('legacy')[0]['folder_id'])
        self.assertEqual(raw, migrated.get('legacy')[1])
        self.assertEqual(1, len(migrated.revisions('legacy')))
        self.assertEqual(receipt, migrated.put('legacy', 'old.txt', raw, None, 'original'))
        self.assertEqual(dict(hashtags=['chemist'], updated_at='original-time'), migrated.get_errands())
        with closing(migrated.connect()) as db:
            self.assertEqual(SCHEMA_VERSION, db.execute('PRAGMA user_version').fetchone()[0])
            self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
        migrated.put_folder('saved', 'Saved')
        migrated.move_document('legacy', 'saved')
        backup = Path(self.temp.name) / 'backup.sqlite3'
        migrated.backup(backup)
        reopened = Store(backup)
        self.assertEqual(migrated.folders(), reopened.folders())
        self.assertEqual('saved', reopened.get('legacy')[0]['folder_id'])
        self.assertEqual(raw, reopened.get('legacy')[1])

    def test_unknown_newer_schema_is_not_modified(self):
        with closing(self.store.connect()) as db:
            db.execute('PRAGMA user_version=' + str(SCHEMA_VERSION + 1))
        with self.assertRaises(RuntimeError):
            Store(self.path)
        with closing(self.store.connect()) as db:
            self.assertEqual(SCHEMA_VERSION + 1, db.execute('PRAGMA user_version').fetchone()[0])

    def test_creation_in_a_folder_is_atomic_and_receipts_include_initial_placement(self):
        self.store.put_folder('a', 'A')
        self.store.put_folder('b', 'B')
        first = self.store.put('new', 'new.txt', b'New\r\n', None, 'create', folder_id='a')
        self.assertEqual('a', first['folder_id'])
        self.assertEqual(1, len(self.store.revisions('new')))
        with self.assertRaises(RequestConflict):
            self.store.put('new', 'new.txt', b'New\r\n', None, 'create', folder_id='b')
        self.store.move_document('new', 'b')
        self.store.delete_folder('a')
        self.assertEqual(first, self.store.put('new', 'new.txt', b'New\r\n', None, 'create', folder_id='a'))
        self.assertEqual('b', self.store.get('new')[0]['folder_id'])
        with self.assertRaises(NotFound):
            self.store.put('missing', 'missing.txt', b'Keep edits', None, 'missing-request', folder_id='gone')
        self.assertFalse(self.store.has_receipt('missing', 'missing-request'))
        with self.assertRaises(NotFound):
            self.store.get('missing')
        with self.assertRaises(RequestConflict):
            self.store.put('new', 'new.txt', b'Updated', first['revision'], 'change-folder', folder_id=None)
        self.assertEqual(b'New\r\n', self.store.get('new')[1])


class FolderApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.token = 'folder-api-test-token-with-at-least-32-characters'
        self.app = create_app(dict(TESTING=True, DATABASE=str(Path(self.temp.name) / 'api.sqlite3'),
            API_TOKEN=self.token, SECRET_KEY='folder-api-test-session-secret', PUBLIC_URL='http://localhost', SESSION_COOKIE_SECURE=False))
        self.addCleanup(self.app.extensions['changes'].close)
        self.client = self.app.test_client()
        self.headers = {'Authorization': 'Bearer ' + self.token}
        page = self.client.get('/editor/login')
        csrf = re.search(rb'name="csrf" value="([a-f0-9]+)"', page.data).group(1).decode()
        self.client.post('/editor/login', data={'csrf': csrf, 'credential': self.token})
        with self.client.session_transaction(path='/editor/') as session:
            self.csrf = {'X-CSRF-Token': session['csrf']}

    def test_folder_endpoints_require_api_auth_and_browser_csrf(self):
        for method, path, data in [('get', '/folders', None), ('get', '/library/root', None),
                ('put', '/folders/new', {'name': 'New'}), ('delete', '/folders/new', None),
                ('put', '/documents/doc/folder', {'folder_id': None})]:
            self.assertEqual(401, getattr(self.client, method)('/api/v1' + path, json=data).status_code)
        self.assertEqual(403, self.client.put('/editor/api/folders/new', json={'name': 'New'}).status_code)
        self.assertEqual(403, self.client.delete('/editor/api/folders/new').status_code)
        self.assertEqual(403, self.client.put('/editor/api/documents/doc/folder', json={'folder_id': None}).status_code)

    def test_browser_and_android_share_folders_placement_and_conditional_metadata(self):
        home = self.client.put('/editor/api/folders/home', headers=self.csrf, json={'name': 'Home'})
        self.assertEqual(200, home.status_code)
        child = self.client.put('/api/v1/folders/child', headers=self.headers, json={'name': 'Child', 'parent_id': 'home'})
        self.assertEqual('home', child.json['parent_id'])
        self.assertEqual(home.json, self.client.get('/api/v1/folders/home', headers=self.headers).json)
        self.assertEqual([home.json], self.client.get('/api/v1/folders?parent_id=', headers=self.headers).json['folders'])
        self.assertEqual([child.json], self.client.get('/editor/api/folders?parent_id=home').json['folders'])
        original = self.client.put('/api/v1/documents/doc', headers=self.headers,
            json=dict(name='notes.txt', content_base64=b64encode(b'Original\r\n').decode(), request_id='first', base_revision=None)).json
        before = self.client.get('/api/v1/documents/doc', headers=self.headers)
        moved = self.client.put('/editor/api/documents/doc/folder', headers=self.csrf, json={'folder_id': 'child'})
        self.assertEqual('child', moved.json['folder_id'])
        self.assertEqual(original['revision'], moved.json['revision'])
        after = self.client.get('/api/v1/documents/doc', headers={**self.headers, 'If-None-Match': before.headers['ETag']})
        self.assertEqual(200, after.status_code)
        self.assertNotEqual(before.headers['ETag'], after.headers['ETag'])
        self.assertEqual(['doc'], [d['id'] for d in self.client.get('/api/v1/folders/child/contents', headers=self.headers).json['documents']])
        self.assertEqual(['home', 'child'], [f['id'] for f in self.client.get('/editor/api/folders/child/path').json['folders']])
        self.assertEqual([], self.client.get('/api/v1/library/root', headers=self.headers).json['documents'])
        # Saving or restoring contents through existing clients cannot reset folder membership.
        saved = self.client.put('/editor/api/documents/doc', headers=self.csrf,
            json=dict(name='notes.txt', text='Edited\n', base_revision=original['revision'], request_id='browser-save'))
        self.assertEqual('child', saved.json['folder_id'])
        restored = self.client.post('/editor/api/documents/doc/restore', headers=self.csrf,
            json=dict(revision=original['revision'], base_revision=saved.json['revision'], request_id='browser-restore'))
        self.assertEqual(200, restored.status_code)
        self.assertEqual('child', restored.json['folder_id'])
        self.assertEqual(409, self.client.delete('/api/v1/folders/child', headers=self.headers).status_code)
        self.assertEqual(200, self.client.put('/api/v1/documents/doc/folder', headers=self.headers, json={'folder_id': None}).status_code)
        self.assertEqual(200, self.client.delete('/editor/api/folders/child', headers=self.csrf).status_code)
        self.assertEqual(404, self.client.get('/api/v1/folders/child', headers=self.headers).status_code)

    def test_invalid_hierarchy_and_missing_location_do_not_silently_move_files(self):
        self.client.put('/api/v1/folders/a', headers=self.headers, json={'name': 'A'})
        self.client.put('/api/v1/folders/b', headers=self.headers, json={'name': 'B', 'parent_id': 'a'})
        self.assertEqual(400, self.client.put('/api/v1/folders/a', headers=self.headers, json={'name': 'A', 'parent_id': 'b'}).status_code)
        self.assertEqual(400, self.client.put('/api/v1/documents/doc/folder', headers=self.headers, json={}).status_code)
        self.assertEqual(400, self.client.put('/api/v1/folders/new', headers=self.headers, json=[]).status_code)
        self.assertEqual(404, self.client.put('/api/v1/folders/new', headers=self.headers, json={'name': 'New', 'parent_id': 'missing'}).status_code)
        self.assertEqual(409, self.client.put('/api/v1/folders/duplicate', headers=self.headers, json={'name': 'a'}).status_code)

    def test_browser_and_token_uploads_can_create_directly_in_a_folder(self):
        self.client.put('/api/v1/folders/home', headers=self.headers, json={'name': 'Home'})
        browser = self.client.put('/editor/api/documents/browser', headers=self.csrf,
            json=dict(name='browser.txt', text='New file\n', base_revision=None, request_id='browser', folder_id='home'))
        self.assertEqual(200, browser.status_code)
        self.assertEqual('home', browser.json['folder_id'])
        token = self.client.put('/api/v1/documents/token', headers=self.headers,
            json=dict(name='token.txt', content_base64=b64encode(b'New file\r\n').decode(), base_revision=None,
                      request_id='token', folder_id='home'))
        self.assertEqual(200, token.status_code)
        self.assertEqual('home', token.json['folder_id'])
        self.assertEqual([], self.client.get('/api/v1/library/root', headers=self.headers).json['documents'])
        self.assertEqual(2, len(self.client.get('/editor/api/folders/home/contents').json['documents']))
