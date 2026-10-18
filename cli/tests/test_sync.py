"""Real HTTP/SQLite tests for preservation, conflicts, monitor saves, and retries."""
from contextlib import chdir, redirect_stdout, redirect_stderr
from base64 import b64encode
from http.client import IncompleteRead
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid

from flask import redirect
from werkzeug.serving import make_server
from textpad_server import create_app

from textpad_sync import BusyFile, SyncError
from textpad_sync.api import Api, ApiError, Document, MAX_BYTES, endpoint
from textpad_sync.cli import main, parser
from textpad_sync.events import ChangeEvents, EventSocket
from textpad_sync.filesystem import deletion_reason, read, write, workspace_lock
from textpad_sync.hashtags import normalize_tags, scan
from textpad_sync.storage import Workspace
from textpad_sync.sync import Engine
from textpad_sync.watch import Watcher

TOKEN = 'disposable-client-test-token-32-characters'
LONG = ''.join(f'Line {n}: words worth retaining\r\n' for n in range(200)).encode()


class SyncTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server_temp = tempfile.TemporaryDirectory()
        cls.app = create_app({'TESTING': True, 'DATABASE': str(Path(cls.server_temp.name) / 'server.sqlite3'),
            'API_TOKEN': TOKEN, 'SECRET_KEY': 'disposable-client-test-session-secret',
            'PUBLIC_URL': 'http://127.0.0.1', 'SESSION_COOKIE_SECURE': False})
        cls.redirect_count = 0
        @cls.app.get('/api/v1/test-redirect')
        def redirect_test():
            return redirect('/api/v1/test-target')
        @cls.app.get('/api/v1/test-target')
        def target_test():
            cls.redirect_count += 1
            return {'ok': True}
        cls.server = make_server('127.0.0.1', 0, cls.app, threaded=True)
        cls.base = 'http://127.0.0.1:' + str(cls.server.server_port)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.store = cls.app.extensions['store']

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()
        cls.server.server_close()
        cls.server_temp.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.ws = Workspace.initialise(self.root, self.base, TOKEN)
        self.api = Api(self.base, TOKEN, self.ws.setting('client_id'), local_test=True)
        self.engine = Engine(self.ws, self.api)
        self.docid = str(uuid.uuid4())
        self.path = self.root / 'notes.txt'

    def tearDown(self):
        self.ws.close()
        self.temp.cleanup()

    def remote(self, content, name='notes.txt'):
        revision = self.store.put(self.docid, name, content, None, str(uuid.uuid4()))
        return self.api.get(self.docid)

    def tracked(self, content=b'First version\r\n'):
        remote = self.remote(content)
        self.path.write_bytes(content)
        self.ws.add('notes.txt', content, remote)
        return remote

    def sync(self, **options):
        with self.ws.lock():
            return self.engine.sync(settle=0, **options)[0]

    def cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        real_api = Api
        def local_api(*a, **kw):
            return real_api(*a, **kw, local_test=True)
        with redirect_stdout(out), redirect_stderr(err), patch('textpad_sync.cli.Api', side_effect=local_api):
            code = main(['-C', str(self.root), *args])
        return code, out.getvalue(), err.getvalue()

    def backup_contents(self):
        return {self.ws.backup(row['id'])['content'] for row in self.ws.backups()}

    def test_errands_tag_edits_share_settings_with_other_clients(self):
        code, out, err = self.cli('errands', 'tags', '--set', '#Chemist, #groceries', '#chemist', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual(['chemist', 'groceries'], json.loads(out)['hashtags'])
        self.assertEqual(['chemist', 'groceries'], self.store.get_errands()['hashtags'])
        self.store.put_errands(['postoffice'])
        self.assertEqual('#postoffice\n', self.cli('errands', 'tags')[1])
        self.assertEqual(0, self.cli('errands', 'tags', '--add', '#Hardware-store', '#postoffice')[0])
        self.assertEqual(['postoffice', 'hardware-store'], self.store.get_errands()['hashtags'])
        self.assertEqual(0, self.cli('errands', 'tags', '--remove', '#POSTOFFICE')[0])
        self.assertEqual(['hardware-store'], self.store.get_errands()['hashtags'])
        code, out, err = self.cli('errands', 'tags', '--clear', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual([], json.loads(out)['hashtags'])
        self.assertEqual([], self.store.get_errands()['hashtags'])

    def test_errands_tag_files_set_add_and_remove_shared_tags(self):
        self.store.put_errands(['old'])
        with tempfile.TemporaryDirectory() as folder:
            tag_file = Path(folder) / 'errands tags.txt'
            contents = b'\xef\xbb\xbf#Chemist\r\n \r\n groceries \r\n#CHEMIST\r\n_hardware-store\r\n'
            tag_file.write_bytes(contents)
            code, out, err = self.cli('errands', 'tags', '--set-file', str(tag_file), '--json')
            self.assertEqual((0, ''), (code, err))
            self.assertEqual(['chemist', 'groceries', '_hardware-store'], json.loads(out)['hashtags'])
            self.assertEqual(json.loads(out)['hashtags'], self.store.get_errands()['hashtags'])
            self.assertEqual(contents, tag_file.read_bytes())
            tag_file.write_text('#postoffice\n#GROCERIES\n', encoding='utf-8')
            code, out, err = self.cli('errands', 'tags', '--add-file', str(tag_file), '--json')
            self.assertEqual((0, ''), (code, err))
            self.assertEqual(['chemist', 'groceries', '_hardware-store', 'postoffice'], json.loads(out)['hashtags'])
            tag_file.write_bytes(b'#CHEMIST\r#not_present\r')
            code, out, err = self.cli('errands', 'tags', '--remove-file', str(tag_file), '--json')
            self.assertEqual((0, ''), (code, err))
            self.assertEqual(['groceries', '_hardware-store', 'postoffice'], json.loads(out)['hashtags'])
            self.assertEqual(json.loads(out)['hashtags'], self.store.get_errands()['hashtags'])

    def test_errands_tag_file_paths_are_relative_to_shell_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            tag_file = Path(folder) / 'relative-tags.txt'
            tag_file.write_text('chemist\n', encoding='utf-8')
            with chdir(folder):
                code, out, err = self.cli('errands', 'tags', '--set-file', 'relative-tags.txt', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual(['chemist'], json.loads(out)['hashtags'])

    def test_errands_preserves_900_tags_through_import_sync_search_add_and_remove(self):
        prefix = 'cli_' + self.docid.replace('-', '')
        tags = [f'{prefix}_shop{number:04d}' for number in range(900)]
        self.tracked(f'[ ] Last local tag #{tags[-1]}\n'.encode())
        remote_id = str(uuid.uuid4())
        self.store.put(remote_id, 'remote.txt', f'[ ] Last remote tag #{tags[-2]}\n'.encode(), None, str(uuid.uuid4()))
        self.store.put_errands(['before'])
        tag_file = self.root / 'many-tags.txt'
        tag_file.write_text('\n'.join('#' + tag.upper() for tag in tags + tags[:3]), encoding='utf-8')
        with patch('textpad_sync.cli.api_for', side_effect=AssertionError('Offline command accessed the network')):
            code, out, err = self.cli('errands', 'tags', '--set-file', str(tag_file), '--offline', '--json')
        self.assertEqual(0, code)
        self.assertEqual(tags, json.loads(out)['hashtags'])
        self.assertTrue(json.loads(out)['dirty'])
        self.assertEqual(['before'], self.store.get_errands()['hashtags'])
        code, out, err = self.cli('errands', 'tags', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual(tags, json.loads(out)['hashtags'])
        self.assertFalse(json.loads(out)['dirty'])
        self.assertEqual(tags, self.store.get_errands()['hashtags'])
        code, out, err = self.cli('errands', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual({self.docid, remote_id}, {match['id'] for match in json.loads(out)['results']})
        added = prefix + '_new'
        tag_file.write_text(added + '\n', encoding='utf-8')
        code, out, err = self.cli('errands', 'tags', '--add-file', str(tag_file), '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual(tags + [added], json.loads(out)['hashtags'])
        self.assertEqual(tags + [added], self.store.get_errands()['hashtags'])
        tag_file.write_text('\n'.join(tags), encoding='utf-8')
        code, out, err = self.cli('errands', 'tags', '--remove-file', str(tag_file), '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual([added], json.loads(out)['hashtags'])
        self.assertEqual([added], self.store.get_errands()['hashtags'])

    def test_errands_rejects_shortened_server_acknowledgement_and_retains_full_pending_list(self):
        tags = [f'shop{number:04d}' for number in range(900)]
        tag_file = self.root / 'many-tags.txt'
        tag_file.write_text('\n'.join(tags), encoding='utf-8')
        with patch.object(Api, 'errands', return_value={'hashtags': tags[:200], 'updated_at': 'old-server'}):
            code, out, err = self.cli('errands', 'tags', '--set-file', str(tag_file))
        self.assertEqual((1, ''), (code, out))
        self.assertIn('different errands hashtag list', err)
        self.assertIn('Update the server', err)
        self.assertIn('saved locally', err)
        cached = json.loads(self.cli('errands', 'tags', '--offline', '--json')[1])
        self.assertEqual(tags, cached['hashtags'])
        self.assertTrue(cached['dirty'])
        code, out, err = self.cli('errands', 'tags', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertEqual(tags, json.loads(out)['hashtags'])
        self.assertFalse(json.loads(out)['dirty'])
        self.assertEqual(tags, self.store.get_errands()['hashtags'])

    def test_errands_empty_tag_files_clear_or_leave_settings_unchanged(self):
        tag_file = self.root / 'empty-tags.txt'
        tag_file.write_bytes(b'\xef\xbb\xbf \r\n\t\r\n')
        original = self.store.put_errands(['chemist'])
        for option in ('--add-file', '--remove-file', '--set-file'):
            code, out, err = self.cli('errands', 'tags', option, str(tag_file), '--json')
            self.assertEqual((0, ''), (code, err))
            self.assertEqual([] if option == '--set-file' else ['chemist'], json.loads(out)['hashtags'])
            if option != '--set-file':
                self.assertEqual(original, self.store.get_errands())
        self.assertEqual([], self.store.get_errands()['hashtags'])

    def test_errands_tag_files_work_offline_and_sync_after_restart(self):
        self.store.put_errands(['untouched'])
        tag_file = self.root / 'tags.txt'
        with patch('textpad_sync.cli.api_for', side_effect=AssertionError('Offline command accessed the network')):
            for option, content, expected in (
                    ('--set-file', 'Chemist\ngroceries\n', ['chemist', 'groceries']),
                    ('--add-file', '#postoffice\n#chemist\n', ['chemist', 'groceries', 'postoffice']),
                    ('--remove-file', 'GROCERIES\n', ['chemist', 'postoffice'])):
                tag_file.write_text(content, encoding='utf-8')
                code, out, err = self.cli('errands', 'tags', option, str(tag_file), '--offline', '--json')
                self.assertEqual(0, code)
                self.assertIn('saved locally', err)
                self.assertTrue(json.loads(out)['dirty'])
                self.assertEqual(expected, json.loads(out)['hashtags'])
        self.assertEqual(['untouched'], self.store.get_errands()['hashtags'])
        code, out, err = self.cli('errands', 'tags', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertFalse(json.loads(out)['dirty'])
        self.assertEqual(['chemist', 'postoffice'], self.store.get_errands()['hashtags'])

    def test_errands_invalid_tag_files_preserve_remote_and_pending_local_settings(self):
        self.store.put_errands(['original'])
        self.assertEqual(0, self.cli('errands', 'tags', '--set', 'pending', '--offline')[0])
        cached = self.ws.setting('errands')
        tag_file = self.root / 'invalid-tags.txt'
        for content in ('chemist\n#two tags\n', 'chemist\n#café\n', 'chemist\n#\n',
                        'chemist\n#123\n', 'chemist\n' + 'a' * 65 + '\n'):
            tag_file.write_text(content, encoding='utf-8')
            for option in ('--set-file', '--add-file', '--remove-file'):
                with self.subTest(option=option, content=content), patch('textpad_sync.cli.api_for') as api:
                    code, out, err = self.cli('errands', 'tags', option, str(tag_file))
                    self.assertEqual((1, ''), (code, out))
                    self.assertIn('line 2', err)
                    self.assertIn(str(tag_file), err)
                    api.assert_not_called()
                    self.assertEqual(cached, self.ws.setting('errands'))
                    self.assertEqual(['original'], self.store.get_errands()['hashtags'])
        for path, message in ((self.root / 'missing-tags.txt', 'Could not read'),
                              (self.root, 'Could not read'), (tag_file, 'Could not read')):
            tag_file.write_bytes(b'\xffinvalid UTF-8')
            with self.subTest(path=path), patch('textpad_sync.cli.api_for') as api:
                code, out, err = self.cli('errands', 'tags', '--set-file', str(path))
                self.assertEqual((1, ''), (code, out))
                self.assertIn(message, err)
                api.assert_not_called()
                self.assertEqual(cached, self.ws.setting('errands'))
                self.assertEqual(['original'], self.store.get_errands()['hashtags'])

    def test_errands_defaults_hide_both_closed_states_locally_and_on_server(self):
        tag = 'cli_' + self.docid.replace('-', '')
        content = (f'[ ] Open #{tag}\r\n\t[C] Cancelled #{tag}\r\n'
                   f'  - [c] Cancelled bullet #{tag}\r\n* [X] Done #{tag}\r\n'
                   f'+ [x] Done too #{tag}\r\nNote about [C] #{tag}\r\n').encode()
        self.tracked(content)
        remote_id = str(uuid.uuid4())
        self.store.put(remote_id, 'remote.txt', content, None, str(uuid.uuid4()))
        self.store.put_errands([tag])
        for options, expected in [((), [1, 6]), (('--show-completed',), [1, 2, 3, 4, 5, 6])]:
            code, out, err = self.cli('errands', *options, '--json')
            self.assertEqual((0, ''), (code, err))
            result = json.loads(out)
            self.assertEqual('lines', result['mode'])
            self.assertEqual(not options, result['hide_completed'])
            matches = {match['id']: match for match in result['results']}
            self.assertEqual({self.docid, remote_id}, set(matches))
            for match in matches.values():
                self.assertEqual(expected, [line['line_number'] for line in match['lines']])

    def test_errands_file_mode_filters_closed_only_files_and_keeps_metadata(self):
        tag = 'cli_' + self.docid.replace('-', '')
        self.tracked(f'[ ] Open #{tag}\n[C] Closed #{tag}\n'.encode())
        remote_id = str(uuid.uuid4())
        self.store.put(remote_id, 'closed.txt', f'- [X] Done #{tag}\n'.encode(), None, str(uuid.uuid4()))
        self.store.put_errands([tag])
        code, out, err = self.cli('errands', '--files', '--json')
        self.assertEqual((0, ''), (code, err))
        result = json.loads(out)
        self.assertEqual('files', result['mode'])
        self.assertEqual(1, len(result['results']))
        match = result['results'][0]
        self.assertEqual(('notes.txt', [tag], 1), (match['path'], match['matched_tags'], match['line_count']))
        self.assertNotIn('lines', match)
        code, out, err = self.cli('errands', '--files', '--show-completed')
        self.assertEqual((0, ''), (code, err))
        self.assertIn('notes.txt\t#' + tag + '\t2 matching lines', out)
        self.assertIn('closed.txt [' + remote_id + ']', out)
        self.assertNotIn('[X] Done', out)

    def test_errands_saved_local_edits_override_server_without_uploading(self):
        tag = 'cli_' + self.docid.replace('-', '')
        original = self.tracked(f'[ ] Server task #{tag}\n'.encode())
        local = f'[ ] Saved local café 😀 #{tag}\n'.encode('utf-16')
        self.path.write_bytes(local)
        new_path = self.root / 'new.txt'
        new_path.write_text(f'[ ] New task #{tag}\n', encoding='utf-8')
        self.ws.add('new.txt', new_path.read_bytes())
        self.store.put_errands([tag])
        code, out, err = self.cli('errands')
        self.assertEqual((0, ''), (code, err))
        self.assertIn('notes.txt:1: [ ] Saved local café 😀', out)
        self.assertIn('new.txt:1: [ ] New task', out)
        self.assertNotIn('Server task', out)
        self.assertEqual(local, self.path.read_bytes())
        self.assertEqual(original.revision, self.api.get(self.docid).revision)

    def test_errands_matches_any_saved_tag_and_prints_full_lines(self):
        tag = 'cli_' + self.docid.replace('-', '')
        long_line = '[ ] ' + 'words ' * 500 + '#' + tag
        self.remote((long_line + f'\n[ ] Second #{tag}_other\n').encode())
        self.store.put_errands([tag, tag + '_other'])
        code, out, err = self.cli('errands')
        self.assertEqual((0, ''), (code, err))
        self.assertIn(long_line, out)
        self.assertIn('notes.txt [' + self.docid + ']:2: [ ] Second', out)

    def test_errands_discovery_includes_closed_tasks_and_unsynced_files(self):
        tag = 'cli_' + self.docid.replace('-', '')
        self.tracked(f'[X] Old #{tag}_old\n'.encode())
        self.path.write_text(f'[C] Saved #{tag}_local\n', encoding='utf-8')
        remote_id = str(uuid.uuid4())
        self.store.put(remote_id, 'remote.txt', f'[X] Remote #{tag}_remote\n'.encode(), None, str(uuid.uuid4()))
        code, out, err = self.cli('errands', 'tags', '--available', '--json')
        self.assertEqual((0, ''), (code, err))
        tags = json.loads(out)['all_hashtags']
        self.assertIn(tag + '_local', tags)
        self.assertIn(tag + '_remote', tags)
        self.assertNotIn(tag + '_old', tags)

    def test_errands_offline_settings_survive_restart_and_sync_including_clear(self):
        self.tracked(b'[ ] Local #chemist\n')
        self.store.put_errands(['remote'])
        with patch('textpad_sync.cli.api_for', side_effect=AssertionError('Offline command accessed the network')):
            code, out, err = self.cli('errands', 'tags', '--offline', '--set', '#chemist', '--json')
            self.assertEqual(0, code)
            self.assertTrue(json.loads(out)['dirty'])
            self.assertIn('saved locally', err)
            code, out, err = self.cli('errands', '--offline', '--json')
            self.assertEqual((0, ''), (code, err))
            self.assertEqual('local', json.loads(out)['results'][0]['source'])
        self.assertEqual(['remote'], self.store.get_errands()['hashtags'])
        code, out, err = self.cli('errands', 'tags', '--json')
        self.assertEqual((0, ''), (code, err))
        self.assertFalse(json.loads(out)['dirty'])
        self.assertEqual(['chemist'], self.store.get_errands()['hashtags'])
        self.assertEqual(0, self.cli('errands', 'tags', '--offline', '--clear')[0])
        self.assertEqual(0, self.cli('errands')[0])
        self.assertEqual([], self.store.get_errands()['hashtags'])

    def test_errands_failed_tag_save_is_preserved_for_retry(self):
        with patch.object(Api, 'errands', side_effect=SyncError('Connection lost.')):
            code, out, err = self.cli('errands', 'tags', '--set', 'chemist')
        self.assertEqual(1, code)
        self.assertIn('saved locally', err)
        self.assertTrue(json.loads(self.cli('errands', 'tags', '--offline', '--json')[1])['dirty'])
        self.assertEqual(0, self.cli('errands', 'tags')[0])
        self.assertEqual(['chemist'], self.store.get_errands()['hashtags'])

    def test_errands_unreadable_local_file_reports_partial_results_and_uses_server(self):
        tag = 'cli_' + self.docid.replace('-', '')
        self.tracked(f'[ ] Server #{tag}\n'.encode())
        self.path.unlink()
        self.store.put_errands([tag])
        code, out, err = self.cli('errands', '--json')
        self.assertEqual(2, code)
        self.assertIn('Could not search', err)
        self.assertEqual('server', json.loads(out)['results'][0]['source'])

    def test_errands_empty_configuration_and_no_matches_explain_results(self):
        self.store.put_errands([])
        self.assertIn('errands tags --set', self.cli('errands')[1])
        self.store.put_errands(['cli_' + self.docid.replace('-', '')])
        self.assertEqual('No matching lines.\n', self.cli('errands')[1])
        self.assertEqual('No matching files.\n', self.cli('errands', '--files')[1])

    def test_new_upload_is_byte_exact_and_unchanged_sync_creates_no_revision(self):
        raw = 'Unicode café 😀\r\nsecond\nlast\r'.encode('utf-8-sig')
        self.path.write_bytes(raw)
        row = self.ws.add('notes.txt', raw)
        self.assertEqual('uploaded', self.sync().state)
        self.assertEqual(raw, self.api.get(row['document_id']).content)
        self.assertEqual('synced', self.sync().state)
        self.assertEqual(1, len(self.api.history(row['document_id'])))

    def test_utf16_bom_and_line_endings_are_preserved(self):
        for prefix, encoding in [(b'\xff\xfe', 'utf-16-le'), (b'\xfe\xff', 'utf-16-be')]:
            with self.subTest(encoding=encoding):
                raw = prefix + 'Words café 😀\r\nline two\r'.encode(encoding)
                path = self.root / (encoding + '.txt')
                path.write_bytes(raw)
                row = self.ws.add(path, raw)
                result = self.engine.sync([row], settle=0)[0]
                self.assertTrue(result.ok, result)
                self.assertEqual(raw, self.api.get(row['document_id']).content)

    def test_pull_backs_up_old_bytes_and_preserves_permissions(self):
        self.tracked()
        if os.name != 'nt':
            self.path.chmod(0o640)
        self.remote(b'Remote update\r\n')
        self.assertEqual('downloaded', self.sync().state)
        self.assertEqual(b'Remote update\r\n', self.path.read_bytes())
        self.assertIn(b'First version\r\n', self.backup_contents())
        if os.name != 'nt':
            self.assertEqual(0o640, self.path.stat().st_mode & 0o777)

    def test_conflict_preserves_all_versions_and_resolves_local(self):
        self.tracked()
        self.path.write_bytes(b'Local edit\n')
        remote = self.remote(b'Remote edit\n')
        self.assertEqual('conflict', self.sync().state)
        self.assertEqual(b'Local edit\n', self.path.read_bytes())
        self.assertEqual(remote.revision, self.api.get(self.docid).revision)
        self.assertTrue({b'First version\r\n', b'Local edit\n', b'Remote edit\n'} <= self.backup_contents())
        result = self.engine.resolve(self.ws.file('notes.txt'), 'local', settle=0)
        self.assertEqual('uploaded', result.state)
        self.assertEqual(b'Local edit\n', self.api.get(self.docid).content)

    def test_conflict_remote_choice_retains_displaced_local(self):
        self.tracked()
        self.path.write_bytes(b'Local edit\n')
        self.remote(b'Remote edit\n')
        self.assertEqual('conflict', self.sync().state)
        self.engine.resolve(self.ws.file('notes.txt'), 'remote', settle=0)
        self.assertEqual(b'Remote edit\n', self.path.read_bytes())
        self.assertIn(b'Local edit\n', self.backup_contents())

    def test_disjoint_changes_merge_locally_and_remotely_with_all_snapshots(self):
        base = b'one\r\ntwo\r\nthree\r\n'
        local, remote = base.replace(b'one', b'ONE'), base.replace(b'three', b'THREE')
        merged = b'ONE\r\ntwo\r\nTHREE\r\n'
        self.tracked(base)
        self.path.write_bytes(local)
        self.remote(remote)
        if os.name != 'nt':
            self.path.chmod(0o640)
        result = self.sync()
        self.assertEqual('merged', result.state)
        self.assertTrue(result.ok)
        self.assertEqual(merged, self.path.read_bytes())
        self.assertEqual(merged, self.api.get(self.docid).content)
        self.assertEqual(merged, self.ws.file('notes.txt')['base_content'])
        self.assertTrue({base, local, remote, merged} <= self.backup_contents())
        self.assertEqual('synced', self.sync().state)
        self.assertEqual(3, len(self.api.history(self.docid)))
        if os.name != 'nt':
            self.assertEqual(0o640, self.path.stat().st_mode & 0o777)

    def test_cli_merges_disjoint_word_edits_and_exits_successfully(self):
        self.tracked(b'The quick fox likes milk\n')
        self.path.write_bytes(b'The quick fox likes tea\n')
        self.remote(b'The FAST fox likes milk\n')
        code, out, err = self.cli('sync', '--settle', '0.001')
        self.assertEqual((0, ''), (code, err))
        self.assertIn('merged:', out)
        self.assertEqual(b'The FAST fox likes tea\n', self.path.read_bytes())

    def test_remote_rename_combines_with_local_content_edit(self):
        self.tracked(b'one\ntwo\n')
        self.path.write_bytes(b'ONE\ntwo\n')
        self.remote(b'one\ntwo\n', 'renamed-remotely.txt')
        self.assertEqual('merged', self.sync().state)
        self.assertEqual('renamed-remotely.txt', self.ws.file('notes.txt')['name'])
        self.assertEqual('renamed-remotely.txt', self.api.get(self.docid).name)
        self.assertEqual(b'ONE\ntwo\n', self.api.get(self.docid).content)

    def test_local_rename_combines_with_remote_content_edit(self):
        self.tracked(b'one\ntwo\n')
        self.ws.update(self.ws.file('notes.txt'), name='renamed-locally.txt')
        self.remote(b'one\nTWO\n')
        self.assertEqual('merged', self.sync().state)
        self.assertEqual('renamed-locally.txt', self.api.get(self.docid).name)
        self.assertEqual(b'one\nTWO\n', self.path.read_bytes())

    def test_incompatible_renames_still_require_resolution(self):
        self.tracked()
        self.ws.update(self.ws.file('notes.txt'), name='local.txt')
        self.remote(b'First version\r\n', 'remote.txt')
        self.assertEqual('conflict', self.sync().state)
        self.assertEqual('remote.txt', self.api.get(self.docid).name)
        self.assertIsNone(self.ws.file('notes.txt')['pending_id'])

    def test_merge_holds_suspicious_local_or_remote_deletions(self):
        self.tracked(LONG)
        self.path.write_bytes(b'')
        self.remote(LONG + b'Remote addition\n')
        self.assertEqual('held', self.sync().state)
        self.assertEqual(b'', self.path.read_bytes())
        self.assertEqual(LONG + b'Remote addition\n', self.api.get(self.docid).content)
        self.path.write_bytes(LONG + b'Local addition\n')
        self.remote(b'')
        self.assertEqual('held', self.sync().state)
        self.assertEqual(LONG + b'Local addition\n', self.path.read_bytes())
        self.assertIsNone(self.ws.file('notes.txt')['pending_id'])

    def test_combined_deletions_cannot_bypass_the_large_deletion_hold(self):
        lines = [f'Line {number:03d}: content worth keeping\n'.encode() for number in range(100)]
        self.tracked(b''.join(lines))
        self.path.write_bytes(b''.join(lines[:60]))
        remote = self.remote(b''.join(lines[40:]))
        self.assertEqual('held', self.sync().state)
        self.assertEqual(b''.join(lines[:60]), self.path.read_bytes())
        self.assertEqual(remote.revision, self.api.get(self.docid).revision)
        self.assertIsNone(self.ws.file('notes.txt')['pending_id'])

    def test_server_race_after_merge_is_rejected_then_merged_against_new_baseline(self):
        self.tracked(b'one\ntwo\nthree\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        self.remote(b'one\ntwo\nTHREE\n')
        original = self.api.put
        def concurrent_save(*args, **kwargs):
            self.remote(b'one\nTWO\nTHREE\n')
            return original(*args, **kwargs)
        with patch.object(self.api, 'put', side_effect=concurrent_save):
            self.assertEqual('conflict', self.sync().state)
        self.assertEqual(b'one\nTWO\nTHREE\n', self.api.get(self.docid).content)
        self.assertEqual('merged', self.sync().state)
        self.assertEqual(b'ONE\nTWO\nTHREE\n', self.path.read_bytes())
        self.assertEqual(b'ONE\nTWO\nTHREE\n', self.api.get(self.docid).content)

    def test_editor_save_during_merge_fetch_is_preserved_and_rebased_on_retry(self):
        self.tracked(b'one\ntwo\nthree\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        self.remote(b'one\ntwo\nTHREE\n')
        original = self.api.get
        def edit_during_get(*args, **kwargs):
            remote = original(*args, **kwargs)
            self.path.write_bytes(b'ONE\nTWO\nthree\n')
            return remote
        with patch.object(self.api, 'get', side_effect=edit_during_get):
            self.assertEqual('waiting', self.sync().state)
        self.assertEqual(b'ONE\nTWO\nthree\n', self.path.read_bytes())
        self.assertTrue(self.ws.file('notes.txt')['pending_id'])
        self.assertTrue(self.sync().ok)
        self.assertEqual(b'ONE\nTWO\nTHREE\n', self.path.read_bytes())
        self.assertEqual(b'ONE\nTWO\nTHREE\n', self.api.get(self.docid).content)

    def test_editor_save_during_merged_upload_is_not_overwritten(self):
        self.tracked(b'one\ntwo\nthree\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        self.remote(b'one\ntwo\nTHREE\n')
        original = self.api.put
        def edit_during_put(*args, **kwargs):
            self.assertEqual(b'ONE\ntwo\nTHREE\n', self.path.read_bytes())
            self.path.write_bytes(b'ONE\nTWO\nTHREE\n')
            return original(*args, **kwargs)
        with patch.object(self.api, 'put', side_effect=edit_during_put):
            self.assertEqual('merged', self.sync().state)
        self.assertEqual(b'ONE\nTWO\nTHREE\n', self.path.read_bytes())
        self.assertEqual(b'ONE\ntwo\nTHREE\n', self.api.get(self.docid).content)
        self.assertEqual('uploaded', self.sync().state)
        self.assertEqual(b'ONE\nTWO\nTHREE\n', self.api.get(self.docid).content)

    def test_merge_queue_survives_restart_before_local_replacement(self):
        self.tracked(b'one\ntwo\nthree\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        self.remote(b'one\ntwo\nTHREE\n')
        with patch('textpad_sync.sync.write', side_effect=BusyFile('Interrupted before replacement')):
            self.assertEqual('waiting', self.sync().state)
        row = self.ws.file('notes.txt')
        pending_id = row['pending_id']
        self.assertEqual(b'ONE\ntwo\nthree\n', row['pending_local'])
        self.assertEqual(b'ONE\ntwo\nTHREE\n', row['pending_content'])
        self.ws.close()
        self.ws = Workspace(self.root)
        self.engine = Engine(self.ws, self.api)
        with patch.object(self.api, 'put', wraps=self.api.put) as put:
            self.assertTrue(self.sync().ok)
        self.assertEqual(pending_id, put.call_args.args[4])
        self.assertEqual(b'ONE\ntwo\nTHREE\n', self.path.read_bytes())
        self.assertIsNone(self.ws.file('notes.txt')['pending_id'])

    def test_merge_receipt_retry_does_not_reorder_a_newer_server_save(self):
        self.tracked(b'one\ntwo\nthree\nfour\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\nfour\n')
        self.remote(b'one\ntwo\nTHREE\nfour\n')
        original = self.api.put
        requests = []
        def accept_and_drop(*args, **kwargs):
            requests.append(args[4])
            original(*args, **kwargs)
            raise SyncError('Merge response lost')
        with patch.object(self.api, 'put', side_effect=accept_and_drop):
            self.assertEqual('error', self.sync().state)
        self.assertEqual(b'ONE\ntwo\nTHREE\nfour\n', self.path.read_bytes())
        newer = self.remote(b'ONE\nTWO\nTHREE\nfour\n')
        self.ws.close()
        self.ws = Workspace(self.root)
        self.engine = Engine(self.ws, self.api)
        with patch.object(self.api, 'put', wraps=self.api.put) as put:
            self.assertTrue(self.sync().ok)
        self.assertEqual(requests[0], put.call_args.args[4])
        self.assertEqual(newer.revision, self.api.get(self.docid).revision)
        self.assertEqual(b'ONE\nTWO\nTHREE\nfour\n', self.path.read_bytes())
        self.assertEqual(4, len(self.api.history(self.docid)))

    def test_restart_after_local_merge_write_before_staging_marker_is_cleared(self):
        self.tracked(b'one\ntwo\nthree\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        self.remote(b'one\ntwo\nTHREE\n')
        original = self.ws.update
        def interrupt_staging(row, **values):
            if values == {'pending_local': None}:
                raise SyncError('Interrupted after local replacement')
            return original(row, **values)
        with patch.object(self.ws, 'update', side_effect=interrupt_staging):
            self.assertEqual('error', self.sync().state)
        self.assertEqual(b'ONE\ntwo\nTHREE\n', self.path.read_bytes())
        pending_id = self.ws.file('notes.txt')['pending_id']
        self.assertIsNotNone(self.ws.file('notes.txt')['pending_local'])
        self.ws.close()
        self.ws = Workspace(self.root)
        self.engine = Engine(self.ws, self.api)
        with patch.object(self.api, 'put', wraps=self.api.put) as put:
            self.assertTrue(self.sync().ok)
        self.assertEqual(pending_id, put.call_args.args[4])
        self.assertIsNone(self.ws.file('notes.txt')['pending_local'])
        self.assertEqual(b'ONE\ntwo\nTHREE\n', self.api.get(self.docid).content)

    def test_overlap_during_merge_staging_holds_and_allows_explicit_resolution(self):
        self.tracked(b'one\ntwo\nthree\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        self.remote(b'one\ntwo\nTHREE\n')
        with patch('textpad_sync.sync.write', side_effect=BusyFile('Interrupted before replacement')):
            self.assertEqual('waiting', self.sync().state)
        self.path.write_bytes(b'ONE\ntwo\nAnother third line\n')
        self.assertEqual('conflict', self.sync().state)
        self.assertEqual(b'ONE\ntwo\nAnother third line\n', self.path.read_bytes())
        self.assertEqual(b'one\ntwo\nTHREE\n', self.api.get(self.docid).content)
        result = self.engine.resolve(self.ws.file('notes.txt'), 'local', settle=0)
        self.assertTrue(result.ok)
        self.assertEqual(b'ONE\ntwo\nAnother third line\n', self.api.get(self.docid).content)
        self.assertIn(b'ONE\ntwo\nTHREE\n', self.backup_contents())

    def test_schema_upgrade_preserves_an_existing_pending_upload(self):
        self.tracked()
        self.path.write_bytes(b'Pending from an older CLI\n')
        with patch.object(self.api, 'put', side_effect=SyncError('Offline')):
            self.assertEqual('error', self.sync().state)
        pending_id = self.ws.file('notes.txt')['pending_id']
        with self.ws.db:
            self.ws.db.execute('ALTER TABLE files DROP COLUMN pending_local')
            self.ws.db.execute('PRAGMA user_version=1')
        self.ws.close()
        self.ws = Workspace(self.root)
        self.engine = Engine(self.ws, self.api)
        self.assertEqual(2, self.ws.db.execute('PRAGMA user_version').fetchone()[0])
        self.assertEqual(pending_id, self.ws.file('notes.txt')['pending_id'])
        self.assertIsNone(self.ws.file('notes.txt')['pending_local'])
        self.assertTrue(self.sync().ok)
        self.assertEqual(b'Pending from an older CLI\n', self.api.get(self.docid).content)

    def test_resolving_remote_discards_an_unsent_merge_without_uploading_it(self):
        self.tracked(b'one\ntwo\nthree\n')
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        remote = self.remote(b'one\ntwo\nTHREE\n')
        with patch('textpad_sync.sync.write', side_effect=BusyFile('Interrupted before replacement')):
            self.assertEqual('waiting', self.sync().state)
        self.path.write_bytes(b'ONE\ntwo\nAnother third line\n')
        self.assertEqual('conflict', self.sync().state)
        with patch.object(self.api, 'put', side_effect=AssertionError('Unsent merge was uploaded')):
            result = self.engine.resolve(self.ws.file('notes.txt'), 'remote', settle=0)
        self.assertEqual('downloaded', result.state)
        self.assertEqual(remote.content, self.path.read_bytes())
        self.assertEqual(remote.revision, self.api.get(self.docid).revision)
        self.assertIsNone(self.ws.file('notes.txt')['pending_id'])

    def test_blank_and_large_truncations_are_held_until_explicitly_allowed(self):
        original = self.tracked(LONG)
        for raw in (b'', b' \r\n\t', b'Only one line left\n'):
            self.path.write_bytes(raw)
            self.assertEqual('held', self.sync().state)
            self.assertEqual(original.revision, self.api.get(self.docid).revision)
            self.assertIsNone(self.ws.file('notes.txt')['pending_id'])
        self.assertEqual('uploaded', self.sync(allow_large_delete=True).state)
        self.assertEqual(b'Only one line left\n', self.api.get(self.docid).content)
        self.assertIn(LONG, self.backup_contents())

    def test_initial_unsynced_contents_also_protect_against_truncation(self):
        self.path.write_bytes(LONG)
        row = self.ws.add('notes.txt', LONG)
        self.path.write_bytes(b'')
        self.assertEqual('held', self.sync().state)
        with self.assertRaises(ApiError) as error:
            self.api.get(row['document_id'])
        self.assertEqual(404, error.exception.status)

    def test_intentionally_new_empty_file_can_be_uploaded(self):
        self.path.write_bytes(b'')
        row = self.ws.add('notes.txt', b'')
        self.assertEqual('uploaded', self.sync().state)
        self.assertEqual(b'', self.api.get(row['document_id']).content)

    def test_remote_blank_is_held_and_can_be_explicitly_chosen(self):
        self.tracked(LONG)
        self.remote(b'')
        self.assertEqual('held', self.sync().state)
        self.assertEqual(LONG, self.path.read_bytes())
        self.engine.resolve(self.ws.file('notes.txt'), 'remote', settle=0)
        self.assertEqual(b'', self.path.read_bytes())
        self.assertIn(LONG, self.backup_contents())

    def test_utf16_whitespace_only_change_is_held(self):
        original = b'\xff\xfe' + 'Important text\r\n'.encode('utf-16-le')
        self.tracked(original)
        self.path.write_bytes(b'\xff\xfe' + ' \r\n'.encode('utf-16-le'))
        self.assertEqual('held', self.sync().state)
        self.assertEqual(original, self.api.get(self.docid).content)

    def test_missing_local_file_is_not_recreated_and_never_deletes_remote(self):
        self.tracked()
        self.path.unlink()
        self.assertEqual('missing', self.sync().state)
        self.assertFalse(self.path.exists())
        self.assertEqual(b'First version\r\n', self.api.get(self.docid).content)

    def test_missing_remote_document_is_not_silently_recreated(self):
        self.tracked()
        with patch.object(self.api, 'list', return_value=[]):
            self.assertEqual('missing-remote', self.sync().state)
        self.assertEqual(b'First version\r\n', self.path.read_bytes())

    def test_editor_change_during_download_defers_replacement(self):
        self.tracked()
        self.remote(b'Remote update\n')
        original = self.api.get
        def edit_during_get(*a, **kw):
            result = original(*a, **kw)
            self.path.write_bytes(b'Local edit while request was in flight\n')
            return result
        with patch.object(self.api, 'get', side_effect=edit_during_get):
            self.assertEqual('waiting', self.sync().state)
        self.assertEqual(b'Local edit while request was in flight\n', self.path.read_bytes())

    def test_edit_during_upload_is_not_mistaken_for_acknowledged_contents(self):
        self.tracked()
        self.path.write_bytes(b'Upload these bytes\n')
        original = self.api.put
        def edit_during_put(*a, **kw):
            self.path.write_bytes(b'Later editor save\n')
            return original(*a, **kw)
        with patch.object(self.api, 'put', side_effect=edit_during_put):
            self.assertEqual('uploaded', self.sync().state)
        self.assertEqual(b'Upload these bytes\n', self.ws.file('notes.txt')['base_content'])
        self.assertEqual(b'Later editor save\n', self.path.read_bytes())
        self.assertEqual('uploaded', self.sync().state)
        self.assertEqual(b'Later editor save\n', self.api.get(self.docid).content)

    def test_changed_remote_between_listing_and_save_is_atomically_rejected(self):
        self.tracked()
        self.path.write_bytes(b'Local edit\n')
        original = self.api.put
        def concurrent_save(*a, **kw):
            self.remote(b'Won the race on the server\n')
            return original(*a, **kw)
        with patch.object(self.api, 'put', side_effect=concurrent_save):
            self.assertEqual('conflict', self.sync().state)
        self.assertEqual(b'Won the race on the server\n', self.api.get(self.docid).content)
        self.assertEqual(b'Local edit\n', self.path.read_bytes())
        self.assertEqual('conflict', self.sync().state)
        self.assertIn(b'Won the race on the server\n', self.backup_contents())

    def test_lost_response_retry_survives_restart_and_does_not_reorder_old_write(self):
        self.tracked()
        self.path.write_bytes(b'Accepted but response lost\n')
        original = self.api.put
        request_ids = []
        def accept_and_drop(*a, **kw):
            request_ids.append(a[4])
            original(*a, **kw)
            raise SyncError('Simulated response loss')
        with patch.object(self.api, 'put', side_effect=accept_and_drop):
            self.assertEqual('error', self.sync().state)
        row = self.ws.file('notes.txt')
        self.assertEqual(request_ids[0], row['pending_id'])
        self.assertEqual(b'Accepted but response lost\n', row['pending_content'])
        self.path.write_bytes(b'Newer local edit\n')
        remote = self.remote(b'Newer remote edit\n')
        self.ws.close()
        self.ws = Workspace(self.root)
        self.api = Api(self.base, TOKEN, self.ws.setting('client_id'), local_test=True)
        self.engine = Engine(self.ws, self.api)
        with patch.object(self.api, 'put', wraps=self.api.put) as retry:
            self.assertEqual('conflict', self.sync().state)
        self.assertEqual(request_ids[0], retry.call_args.args[4])
        self.assertEqual(remote.revision, self.api.get(self.docid).revision)
        self.assertEqual(b'Newer local edit\n', self.path.read_bytes())
        self.assertEqual(3, len(self.api.history(self.docid)))

    def test_queued_offline_payload_is_retried_before_newer_local_save(self):
        self.tracked()
        self.path.write_bytes(b'First offline save\n')
        with patch.object(self.api, 'put', side_effect=SyncError('Offline')):
            self.assertEqual('error', self.sync().state)
        pending_id = self.ws.file('notes.txt')['pending_id']
        self.path.write_bytes(b'Second offline save\n')
        with patch.object(self.api, 'put', wraps=self.api.put) as uploads:
            self.assertTrue(self.sync().ok)
        self.assertEqual(2, uploads.call_count)
        self.assertEqual(pending_id, uploads.call_args_list[0].args[4])
        self.assertNotEqual(pending_id, uploads.call_args_list[1].args[4])
        self.assertEqual(b'Second offline save\n', self.api.get(self.docid).content)
        self.assertIn(b'First offline save\n', self.backup_contents())

    def test_conditional_create_and_receipt_retry_are_safe(self):
        self.api.capabilities()
        request_id = str(uuid.uuid4())
        first = self.api.put(self.docid, 'notes.txt', b'First\n', None, request_id)
        newer = self.remote(b'Newer\n')
        repeated = self.api.put(self.docid, 'notes.txt', b'First\n', None, request_id)
        self.assertEqual(first, repeated)
        self.assertEqual(newer.revision, self.api.get(self.docid).revision)
        with self.assertRaises(ApiError) as error:
            self.api.put(self.docid, 'notes.txt', b'Overwrite\n', None, str(uuid.uuid4()))
        self.assertEqual('revision_conflict', error.exception.code)

    def test_client_refuses_write_if_server_has_no_conditional_capability(self):
        with patch.object(self.api, 'request', return_value={'conditional_put': False}) as request:
            with self.assertRaises(SyncError):
                self.api.put(self.docid, 'notes.txt', b'Data', None, str(uuid.uuid4()))
            self.assertEqual([('GET', 'capabilities')], [call.args for call in request.call_args_list])

    def test_checksum_and_wrong_document_are_rejected(self):
        doc = self.tracked()
        data = {'id': doc.id, 'revision': doc.revision, 'name': doc.name, 'sha256': doc.sha256,
                'size': doc.size, 'content_base64': 'Y29ycnVwdA=='}
        with patch.object(self.api, 'request', return_value=data):
            with self.assertRaises(SyncError):
                self.api.get(doc.id)
        data.update(id=str(uuid.uuid4()), content_base64=b64encode(doc.content).decode())
        with patch.object(self.api, 'request', return_value=data):
            with self.assertRaises(SyncError):
                self.api.get(doc.id)
        self.assertEqual(doc.content, self.path.read_bytes())

    def test_credentials_are_not_forwarded_through_redirects(self):
        before = self.redirect_count
        with self.assertRaises(ApiError) as error:
            self.api.request('GET', 'test-redirect')
        self.assertEqual(302, error.exception.status)
        self.assertEqual(before, self.redirect_count)

    def test_insecure_network_commands_accept_flags_in_either_position(self):
        self.tracked()
        for args in (('--insecure', 'remote', '--json'), ('remote', '--insecure', '--json')):
            code, out, err = self.cli(*args)
            self.assertEqual(0, code, err)
            self.assertTrue(any(doc['id'] == self.docid for doc in json.loads(out)))

    def test_watch_passes_insecure_to_its_network_client(self):
        with (redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()),
              patch('textpad_sync.cli.Api') as api, patch('textpad_sync.cli.Watcher') as watcher):
            watcher.return_value.run.side_effect = KeyboardInterrupt
            code = main(['-C', str(self.root), 'watch', '--insecure'])
        self.assertEqual(130, code)
        self.assertTrue(api.call_args.kwargs['insecure'])
        self.assertIs(api.return_value, watcher.call_args.args[1])

    def test_watch_waits_for_editor_truncate_rewrite_and_atomic_replace(self):
        self.tracked(LONG)
        messages = []
        watcher = Watcher(self.ws, self.api, report=messages.append)
        watcher.step(0)
        watcher.step(2)
        temporary = self.root / 'editor.tmp'
        temporary.write_bytes(b'')
        os.replace(temporary, self.path)
        watcher.step(3)
        final = LONG + b'Another line from the editor\r\n'
        temporary.write_bytes(final)
        os.replace(temporary, self.path)
        watcher.step(3.4)
        watcher.step(5)
        self.assertEqual(LONG, self.api.get(self.docid).content)
        watcher.step(5.5)
        self.assertEqual(final, self.api.get(self.docid).content)
        self.assertEqual(2, len(self.api.history(self.docid)))

    def test_watch_holds_persistent_blank_once_and_recovers_when_content_returns(self):
        self.tracked(LONG)
        messages = []
        watcher = Watcher(self.ws, self.api, report=messages.append)
        watcher.step(0)
        watcher.step(2)
        self.path.write_bytes(b'')
        watcher.step(3)
        watcher.step(5.1)
        watcher.step(8)
        self.assertEqual(1, sum('held:' in message for message in messages))
        self.assertEqual(LONG, self.api.get(self.docid).content)
        self.path.write_bytes(LONG + b'Final saved text\n')
        watcher.step(9)
        watcher.step(11.1)
        self.assertEqual(LONG + b'Final saved text\n', self.api.get(self.docid).content)

    def test_watch_ignores_brief_absence_during_rename_save_but_reports_persistent_absence(self):
        self.tracked()
        messages = []
        watcher = Watcher(self.ws, self.api, report=messages.append)
        watcher.step(0)
        watcher.step(2)
        backup = self.root / 'notes.txt~'
        os.replace(self.path, backup)  # Vim-style save: original moved aside first.
        watcher.step(3)
        watcher.step(3.5)
        self.path.write_bytes(b'Saved by rename\r\n')
        backup.unlink()
        watcher.step(4)
        watcher.step(6)
        self.assertFalse(any('missing:' in message for message in messages), messages)
        self.assertEqual(b'Saved by rename\r\n', self.api.get(self.docid).content)
        self.path.unlink()
        watcher.step(7)
        self.assertFalse(any('missing:' in message for message in messages), messages)
        watcher.step(9)
        self.assertEqual(1, sum('missing:' in message for message in messages))

    def test_idle_watch_does_not_make_http_requests_each_local_tick(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, remote_interval=30, report=lambda text: None)
        with patch.object(self.api, 'list', wraps=self.api.list) as listing:
            watcher.step(0)
            watcher.step(2)
            self.assertEqual(1, listing.call_count)
            for tick in range(3, 30):
                watcher.step(tick)
            self.assertEqual(1, listing.call_count)
            watcher.step(30)
            self.assertEqual(2, listing.call_count)

    def test_monitor_defers_when_file_changed_after_stability_observation(self):
        self.tracked()
        self.path.write_bytes(b'Changing local file\n')
        row = self.ws.file('notes.txt')
        result = self.engine.sync([row], settle=0, expected_signatures={'notes.txt': ('old',)})[0]
        self.assertEqual('waiting', result.state)
        self.assertEqual(b'First version\r\n', self.api.get(self.docid).content)

    def test_watch_retries_offline_checks_early_and_reports_unchanged_recovery(self):
        self.tracked()
        messages = []
        watcher = Watcher(self.ws, self.api, remote_interval=120, report=messages.append)
        watcher.step(0)
        watcher.step(2)
        with patch.object(self.api, 'list', side_effect=SyncError('Network unavailable')) as listing:
            watcher.notify()
            watcher.step(3)
            watcher.step(4)
            watcher.step(7)
            self.assertEqual(1, listing.call_count)
            watcher.step(8)
            self.assertEqual(2, listing.call_count)
        self.assertEqual(1, sum('offline:' in message for message in messages))
        watcher.step(13)
        watcher.step(14)
        self.assertEqual(1, sum('online:' in message for message in messages))
        self.assertFalse(watcher.offline)
        # No content change was needed to make recovery visible.
        self.assertEqual(b'First version\r\n', self.path.read_bytes())

    def test_watch_retries_an_offline_local_save_without_waiting_for_remote_interval(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, remote_interval=120, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        self.path.write_bytes(b'Local edit while Wi-Fi is disconnected\n')
        watcher.step(3)
        with patch.object(self.api, 'list', side_effect=SyncError('Network unavailable')):
            watcher.step(5)
        self.assertEqual(b'First version\r\n', self.api.get(self.docid).content)
        watcher.step(10)
        self.assertEqual(b'Local edit while Wi-Fi is disconnected\n', self.api.get(self.docid).content)

    def test_watch_retries_the_exact_pending_upload_after_a_connection_failure(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, remote_interval=120, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        self.path.write_bytes(b'Local save interrupted during upload\n')
        watcher.step(3)
        with patch.object(self.api, 'put', side_effect=SyncError('Connection lost during upload')):
            watcher.step(5)
        pending = self.ws.file('notes.txt')['pending_id']
        self.assertTrue(pending)
        with patch.object(self.api, 'put', wraps=self.api.put) as put:
            watcher.step(10)
        self.assertEqual(pending, put.call_args.args[-1])
        self.assertIsNone(self.ws.file('notes.txt')['pending_id'])
        self.assertEqual(b'Local save interrupted during upload\n', self.api.get(self.docid).content)

    def test_watch_survives_an_interrupted_http_response_and_recovers(self):
        self.tracked()
        messages = []
        watcher = Watcher(self.ws, self.api, remote_interval=120, report=messages.append)
        watcher.step(0)
        watcher.step(2)
        watcher.notify()
        with patch.object(self.api.opener, 'open') as opening:
            opening.return_value.__enter__.return_value.read.side_effect = IncompleteRead(b'partial', 10)
            watcher.step(3)
        self.assertTrue(watcher.offline)
        self.assertTrue(any('IncompleteRead' in message for message in messages))
        self.remote(b'Server edit after the connection recovered\n')
        watcher.step(8)
        self.assertFalse(watcher.offline)
        self.assertEqual(b'Server edit after the connection recovered\n', self.path.read_bytes())

    def test_watch_pulls_remote_changes_while_local_file_is_unchanged(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        self.remote(b'Remote edit observed by monitor\r\n')
        watcher.step(30)
        self.assertEqual(b'Remote edit observed by monitor\r\n', self.path.read_bytes())
        self.assertIn(b'First version\r\n', self.backup_contents())

    def test_live_notifications_reconcile_before_the_periodic_deadline(self):
        self.tracked()
        messages, hint = [], threading.Event()
        watcher = Watcher(self.ws, self.api, report=messages.append)
        watcher.step(0)
        watcher.step(2)
        def changed():
            watcher.notify()
            hint.set()
        with ChangeEvents(self.api, changed, lambda: None) as events:
            self.assertTrue(hint.wait(3), 'No initial ready notification')
            self.assertEqual('live', events.status())
            watcher.step(3)
            hint.clear()
            self.remote(b'Remote change received over WebSocket\r\n')
            self.assertTrue(hint.wait(3), 'No committed-change notification')
            watcher.step(4)
            self.assertEqual(b'Remote change received over WebSocket\r\n', self.path.read_bytes())
            self.assertTrue(any('downloaded:' in message for message in messages))
        self.assertFalse(events.thread.is_alive())

    def test_idle_live_heartbeats_do_not_notify_or_query_the_server_database(self):
        ready, hints = threading.Event(), []
        def changed():
            hints.append(True)
            ready.set()
        with (patch('textpad_sync.events.PING_INTERVAL', 0.02),
              patch('textpad_sync.events.PONG_TIMEOUT', 0.5),
              patch.object(self.store, 'change_cursor', wraps=self.store.change_cursor) as cursor):
            with ChangeEvents(self.api, changed, lambda: None) as events:
                self.assertTrue(ready.wait(3))
                with events.lock:
                    connection = events.socket
                self.assertTrue(connection.pong.wait(3), 'Server did not answer the client heartbeat')
                self.assertEqual([True], hints, 'A pong triggered an unnecessary reconciliation')
                self.assertEqual(1, cursor.call_count, 'A heartbeat queried the database')
        self.assertFalse(events.thread.is_alive())

    def test_live_reconnect_checks_changes_missed_while_disconnected(self):
        self.tracked()
        hint, reconnecting, release = threading.Event(), threading.Event(), threading.Event()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        original = EventSocket.connect
        attempts = []
        def connect(connection):
            attempts.append(True)
            if len(attempts) > 1:
                reconnecting.set()
                if not release.wait(3):
                    raise SyncError('Test did not release reconnect')
            original(connection)
        def changed():
            watcher.notify()
            hint.set()
        with patch.object(EventSocket, 'connect', connect):
            with ChangeEvents(self.api, changed, lambda: None) as events:
                try:
                    self.assertTrue(hint.wait(3))
                    watcher.step(3)
                    hint.clear()
                    with events.lock:
                        events.socket.interrupt()
                    self.assertTrue(reconnecting.wait(3))
                    self.assertEqual('polling', events.status())
                    self.remote(b'Change made while the socket was disconnected\n')
                    self.assertEqual(b'First version\r\n', self.path.read_bytes())
                    release.set()
                    self.assertTrue(hint.wait(3), 'Reconnect did not request reconciliation')
                    watcher.step(4)
                    self.assertEqual(b'Change made while the socket was disconnected\n', self.path.read_bytes())
                finally:
                    release.set()
        self.assertFalse(events.thread.is_alive())

    def test_live_hint_waits_for_local_stability_without_losing_the_remote_check(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        stamp = self.path.stat()
        os.utime(self.path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns + 1_000_000))
        watcher.step(3)
        self.remote(b'Remote change while local metadata settles\n')
        watcher.notify()
        watcher.step(3.5)
        self.assertEqual(b'First version\r\n', self.path.read_bytes())
        watcher.step(5.1)
        self.assertEqual(b'Remote change while local metadata settles\n', self.path.read_bytes())

    def test_live_downloads_do_not_restart_the_editor_settle_timer(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, settle=10, report=lambda message: None)
        watcher.step(0)
        watcher.step(10)
        self.remote(b'First live remote edit\n')
        watcher.notify()
        watcher.step(10.1)
        self.assertEqual(b'First live remote edit\n', self.path.read_bytes())
        self.remote(b'Second live remote edit\n')
        watcher.notify()
        watcher.step(10.2)
        self.assertEqual(b'Second live remote edit\n', self.path.read_bytes())
        self.remote(b'Third live remote edit\n')
        watcher.notify()
        watcher.step(10.3)
        self.assertEqual(b'Third live remote edit\n', self.path.read_bytes())

    def test_editor_writes_after_download_still_wait_for_stability(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        self.remote(b'Live remote edit\n')
        watcher.notify()
        watcher.step(3)
        self.path.write_bytes(b'Saved editor change after the download\n')
        watcher.notify()
        watcher.step(3.1)
        watcher.step(5)
        self.assertEqual(b'Live remote edit\n', self.api.get(self.docid).content)
        watcher.step(5.2)
        self.assertEqual(b'Saved editor change after the download\n', self.api.get(self.docid).content)

    def test_editor_writes_during_download_reporting_are_not_marked_as_settled(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        self.remote(b'Live remote edit\n')
        sync = watcher.engine.sync
        def edit_after_download(*args, **kwargs):
            results = sync(*args, **kwargs)
            self.path.write_bytes(b'Editor saved while the download completed\n')
            return results
        watcher.notify()
        with patch.object(watcher.engine, 'sync', side_effect=edit_after_download):
            watcher.step(3)
        watcher.notify()
        watcher.step(3.1)
        watcher.step(5)
        self.assertEqual(b'Live remote edit\n', self.api.get(self.docid).content)
        watcher.step(5.2)
        self.assertEqual(b'Editor saved while the download completed\n', self.api.get(self.docid).content)

    def test_live_hint_during_reconciliation_schedules_another_pass(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        listing = self.api.list
        def change_after_listing():
            documents = listing()
            self.remote(b'Change committed during reconciliation\n')
            watcher.notify()
            return documents
        watcher.notify()
        with patch.object(self.api, 'list', side_effect=change_after_listing):
            watcher.step(3)
        self.assertEqual(b'First version\r\n', self.path.read_bytes())
        watcher.step(3.1)
        self.assertEqual(b'Change committed during reconciliation\n', self.path.read_bytes())

    def test_live_hint_preserves_conflicting_local_and_remote_edits(self):
        self.tracked()
        messages = []
        watcher = Watcher(self.ws, self.api, report=messages.append)
        watcher.step(0)
        watcher.step(2)
        self.path.write_bytes(b'Unsynced local edit\n')
        watcher.step(3)
        self.remote(b'Conflicting remote edit\n')
        watcher.notify()
        watcher.step(5)
        self.assertEqual(b'Unsynced local edit\n', self.path.read_bytes())
        self.assertEqual(b'Conflicting remote edit\n', self.api.get(self.docid).content)
        self.assertTrue(any('conflict:' in message for message in messages))
        self.assertTrue({b'Unsynced local edit\n', b'Conflicting remote edit\n'} <= self.backup_contents())

    def test_watch_merges_stable_edits_then_accepts_the_next_live_download_immediately(self):
        self.tracked(b'one\ntwo\nthree\n')
        messages = []
        watcher = Watcher(self.ws, self.api, settle=10, report=messages.append)
        watcher.step(0)
        watcher.step(10)
        self.path.write_bytes(b'ONE\ntwo\nthree\n')
        watcher.step(11)
        self.remote(b'one\ntwo\nTHREE\n')
        watcher.notify()
        watcher.step(12)
        self.assertEqual(b'ONE\ntwo\nthree\n', self.path.read_bytes())
        watcher.step(21)
        self.assertEqual(b'ONE\ntwo\nTHREE\n', self.path.read_bytes())
        self.assertEqual(b'ONE\ntwo\nTHREE\n', self.api.get(self.docid).content)
        self.assertEqual(1, sum('merged:' in message for message in messages))
        self.remote(b'ONE\nTWO\nTHREE\n')
        watcher.notify()
        watcher.step(21.1)
        self.assertEqual(b'ONE\nTWO\nTHREE\n', self.path.read_bytes())

    def test_periodic_reconciliation_continues_when_live_updates_are_unavailable(self):
        self.tracked()
        wake = threading.Event()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        with patch.object(EventSocket, 'connect', side_effect=SyncError('No WebSocket support')):
            with ChangeEvents(self.api, watcher.notify, wake.set) as events:
                self.assertTrue(wake.wait(3))
                self.assertEqual('polling', events.status())
                self.remote(b'Remote change found by periodic fallback\n')
                watcher.step(29)
                self.assertEqual(b'First version\r\n', self.path.read_bytes())
                watcher.step(30)
                self.assertEqual(b'Remote change found by periodic fallback\n', self.path.read_bytes())
        self.assertFalse(events.thread.is_alive())

    def test_server_rename_is_pulled_as_metadata_without_moving_local_path(self):
        self.tracked()
        self.remote(b'First version\r\n', 'Renamed on server.txt')
        self.assertEqual('downloaded', self.sync().state)
        self.assertTrue(self.path.exists())
        self.assertEqual('Renamed on server.txt', self.ws.file('notes.txt')['name'])
        self.assertEqual(0, self.cli('rename', 'notes.txt', 'New display name.txt')[0])
        self.assertEqual('uploaded', self.sync().state)
        self.assertEqual('New display name.txt', self.api.get(self.docid).name)

    def test_cli_lists_gets_syncs_and_removes_while_retaining_files_and_backups(self):
        remote = self.remote(b'Server text\r\n')
        self.assertEqual(0, self.cli('get', remote.id, 'subdir/local.txt')[0])
        local = self.root / 'subdir/local.txt'
        self.assertEqual(remote.content, local.read_bytes())
        self.assertEqual(0, self.cli('remote', '--json')[0])
        code, out, err = self.cli('status', '--json')
        self.assertEqual(0, code, err)
        self.assertEqual('synced', json.loads(out)[0]['state'])
        local.write_bytes(b'Edited outside Textpad\r\n')
        self.assertEqual(0, self.cli('sync', 'subdir/local.txt', '--settle', '0.001')[0])
        self.assertEqual(local.read_bytes(), self.api.get(remote.id).content)
        self.assertEqual(0, self.cli('history', 'subdir/local.txt')[0])
        self.assertEqual(0, self.cli('remove', 'subdir/local.txt')[0])
        self.assertTrue(local.exists())
        self.assertEqual([], self.ws.files())
        self.assertTrue(self.ws.backups())
        self.assertEqual(local.read_bytes(), self.api.get(remote.id).content)

    def test_cli_add_works_without_network_or_token_and_relocates_existing_file(self):
        self.path.write_bytes(b'Local file\n')
        (self.ws.folder / 'token').unlink()
        with patch.dict(os.environ, {}, clear=True), patch.object(Api, 'request', side_effect=AssertionError('Network access')):
            self.assertEqual(0, self.cli('add', 'notes.txt')[0])
            self.assertEqual(0, self.cli('status')[0])
        renamed = self.root / 'moved.txt'
        self.path.rename(renamed)
        self.assertEqual(0, self.cli('relocate', 'notes.txt', 'moved.txt')[0])
        self.assertEqual('moved.txt', self.ws.files()[0]['path'])

    def test_cli_download_and_recovery_do_not_clobber_existing_files(self):
        self.tracked()
        old_revision = self.ws.file('notes.txt')['revision']
        self.path.write_bytes(b'New local edit\n')
        self.sync()
        self.assertEqual(0, self.cli('download', self.docid, 'historical.txt', '--revision', old_revision)[0])
        self.assertEqual(b'First version\r\n', (self.root / 'historical.txt').read_bytes())
        self.assertEqual(1, self.cli('get', self.docid, 'notes.txt')[0])
        snapshot = self.ws.backups()[0]
        self.assertEqual(0, self.cli('recover', str(snapshot['id']), '--to', 'recovered.txt')[0])
        self.assertEqual(1, self.cli('recover', str(snapshot['id']), '--to', 'notes.txt')[0])
        self.assertEqual(b'New local edit\n', self.path.read_bytes())

    def test_cli_requires_explicit_paths_to_allow_large_deletions(self):
        self.tracked(LONG)
        self.path.write_bytes(b'')
        self.assertEqual(2, self.cli('sync', 'notes.txt', '--settle', '0.001')[0])
        self.assertEqual(1, self.cli('sync', '--allow-large-delete')[0])
        self.assertEqual(0, self.cli('sync', 'notes.txt', '--allow-large-delete', '--settle', '0.001')[0])

    def test_cli_does_not_remove_a_pending_mapping(self):
        self.tracked()
        self.path.write_bytes(b'Queued edit\n')
        with patch.object(self.api, 'put', side_effect=SyncError('Offline')):
            self.sync()
        self.assertEqual(1, self.cli('remove', 'notes.txt')[0])
        self.assertEqual(1, len(self.ws.files()))

    def test_workspace_refuses_traversal_private_state_symlinks_and_special_files(self):
        for value in ('../outside.txt', '.textpad/token', str(self.root.parent / 'outside.txt')):
            with self.assertRaises(SyncError):
                self.ws.path(value)
        if os.name != 'nt':
            link = self.root / 'link'
            link.symlink_to(self.root.parent, target_is_directory=True)
            with self.assertRaises(SyncError):
                self.ws.path('link/outside.txt')
            fifo = self.root / 'pipe'
            os.mkfifo(fifo)
            with self.assertRaises(SyncError):
                read(fifo)

    def test_changed_file_cannot_be_replaced_using_old_snapshot(self):
        self.path.write_bytes(b'Old content\n')
        old = read(self.path)
        self.path.write_bytes(b'Fresh editor save\n')
        with self.assertRaises(BusyFile):
            write(self.path, b'Remote content\n', expected=old)
        self.assertEqual(b'Fresh editor save\n', self.path.read_bytes())
        self.assertEqual([], list(self.root.glob('.textpad-*.tmp')))

    def test_hard_link_alias_cannot_create_a_second_mapping_for_the_same_file(self):
        self.tracked()
        alias = self.root / 'alias.txt'
        os.link(self.path, alias)
        with self.assertRaises(SyncError):
            self.ws.add('alias.txt', self.path.read_bytes())

    @unittest.skipIf(os.name == 'nt', 'Creating symlinks on Windows can require extra privileges.')
    def test_mapping_can_be_removed_if_the_saved_path_becomes_a_symlink(self):
        self.tracked()
        self.path.unlink()
        self.path.symlink_to(self.ws.folder / 'token')
        self.assertEqual('error', self.sync().state)
        self.assertEqual(0, self.cli('remove', 'notes.txt')[0])
        self.assertEqual([], self.ws.files())

    def test_workspace_commands_cannot_take_the_same_lock_twice(self):
        with self.ws.lock():
            with self.assertRaises(SyncError):
                with workspace_lock(self.ws.folder, timeout=0.01):
                    self.fail('Concurrent command acquired workspace lock')

    def test_client_enforces_the_server_document_size_limit_before_upload(self):
        self.path.write_bytes(b'a' * MAX_BYTES)
        self.assertEqual(MAX_BYTES, len(read(self.path).content))
        with self.path.open('ab') as stream:
            stream.write(b'a')
        with self.assertRaises(SyncError):
            read(self.path)

    def test_workspace_is_private_and_token_not_stored_in_database(self):
        self.assertNotIn(TOKEN.encode(), (self.ws.folder / 'state.sqlite3').read_bytes())
        if os.name != 'nt':
            self.assertEqual(0o700, self.ws.folder.stat().st_mode & 0o777)
            self.assertEqual(0o600, (self.ws.folder / 'token').stat().st_mode & 0o777)

    def test_corrupted_backup_is_refused(self):
        self.tracked()
        snapshot = self.ws.backups()[0]
        with self.ws.db:
            self.ws.db.execute("UPDATE snapshots SET content=x'00' WHERE id=?", (snapshot['id'],))
        with self.assertRaises(SyncError):
            self.ws.backup(snapshot['id'])


class ValidationTests(unittest.TestCase):
    def test_errands_tag_file_operations_are_mutually_exclusive(self):
        with tempfile.TemporaryDirectory() as folder:
            tag_file = Path(folder) / 'tags.txt'
            tag_file.write_text('chemist\n', encoding='utf-8')
            for option, other in (('--set-file', ['--add', 'groceries']),
                                  ('--add-file', ['--remove-file', str(tag_file)]),
                                  ('--remove-file', ['--clear']), ('--set-file', ['--available'])):
                with self.subTest(option=option, other=other), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    parser().parse_args(['errands', 'tags', option, str(tag_file), *other])

    def test_errands_parser_defaults_and_nested_common_flags(self):
        args = parser().parse_args(['errands'])
        self.assertEqual(('lines', True, False), (args.mode, args.hide_completed, args.offline))
        for flags in (['--insecure', 'errands', 'tags'], ['errands', '--insecure', 'tags'],
                      ['errands', 'tags', '--insecure']):
            self.assertTrue(parser().parse_args(flags).insecure)
        for flags in (['errands', '--offline', '--json', 'tags'], ['errands', 'tags', '--offline', '--json']):
            args = parser().parse_args(flags)
            self.assertTrue(args.offline)
            self.assertTrue(args.json)
        for flags in (['errands', '--lines', '--files'], ['errands', 'tags', '--clear', '--set', 'chemist']):
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                parser().parse_args(flags)

    def test_local_hashtag_boundaries_line_numbers_and_normalization_match_server(self):
        self.assertEqual(['chemist', 'hardware-store', '_shop'], normalize_tags('#Chemist, chemist #hardware-store _shop'))
        value = ('[ ] (#Chemist), #hardware-store!\r\n'
                 'https://example.com#chemist #123 x#chemist #chemist/path\r'
                 'Note about [C] #chemist\n+ [c] Closed #chemist\n')
        discovered, match = scan(value, ['chemist'], True)
        self.assertEqual(['chemist', 'hardware-store'], discovered)
        self.assertEqual([1, 3], [line['line_number'] for line in match['lines']])

    def test_errands_api_rejects_malformed_responses(self):
        api = Api('https://example.com', TOKEN, 'cli-test')
        for response in ({}, {'hashtags': 'chemist', 'updated_at': ''}, {'hashtags': [False], 'updated_at': ''}):
            with patch.object(api, 'request', return_value=response), self.assertRaises(SyncError):
                api.errands()
        for response in ({}, {'all_hashtags': [], 'results': ['invalid']},
                         {'all_hashtags': [], 'results': [{'id': 'ok', 'name': 'file', 'lines': [None], 'matched_tags': []}]}):
            with patch.object(api, 'request', return_value=response), self.assertRaises(SyncError):
                api.search_hashtags(['chemist'])

    def test_insecure_flag_is_per_invocation_and_accepts_either_position(self):
        for args in (['--insecure', 'sync'], ['sync', '--insecure'],
                     ['--insecure', 'sync', '--insecure'], ['init', 'directory', '--insecure']):
            self.assertTrue(parser().parse_args(args).insecure)
        self.assertFalse(parser().parse_args(['sync']).insecure)

    def test_insecure_flag_does_not_allow_plain_http(self):
        with self.assertRaises(SyncError):
            Api('http://example.com', TOKEN, 'cli-test', insecure=True)

    def test_production_urls_require_https_and_never_embedded_credentials(self):
        for value in ('http://example.com', 'https://token@example.com', 'https://example.com/?token=x',
                      'https://example.com/#x', 'https://example.com:bad', 'file:///tmp/test'):
            with self.subTest(value=value), self.assertRaises(SyncError):
                endpoint(value)

    def test_invalid_identifiers_and_names_have_clean_cli_errors(self):
        for args in (['get', '../bad'], ['rename', 'file.txt', '../bad']):
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, main(args))

    def test_non_destructive_edits_and_small_documents_are_not_flagged(self):
        for old, new in ((b'Hello\n', b'Hi\n'), (b'a\nb\nc\n', b'a\nc\n'), (LONG, LONG.replace(b'Line 2:', b'Line TWO:'))):
            self.assertIsNone(deletion_reason(old, new))
