"""Real HTTP/SQLite tests for preservation, conflicts, monitor saves, and retries."""
from contextlib import redirect_stdout, redirect_stderr
from base64 import b64encode
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

    def test_watch_pulls_remote_changes_while_local_file_is_unchanged(self):
        self.tracked()
        watcher = Watcher(self.ws, self.api, report=lambda message: None)
        watcher.step(0)
        watcher.step(2)
        self.remote(b'Remote edit observed by monitor\r\n')
        watcher.step(30)
        self.assertEqual(b'Remote edit observed by monitor\r\n', self.path.read_bytes())
        self.assertIn(b'First version\r\n', self.backup_contents())

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
