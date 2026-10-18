"""Real Vim processes: filtering parity, editing, writes, recovery, and mappings."""
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VIM = os.environ.get('VIM_BINARY', 'vim')


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


class PluginTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='textpad-vim-test-')
        self.directory = Path(self.temp.name)
        self.file = self.directory / 'notes with spaces.txt'
        self.initial = ['Unrelated note', '', 'Shopping', '---', '[ ] Milk', '    Organic note', '[X] Bread', '', 'Done', '---', '[x] Finished']
        self.file.write_text('\n'.join(self.initial) + '\n')

    def tearDown(self):
        self.temp.cleanup()

    def run_vim(self, body):
        report = self.directory / 'errors.json'
        script = self.directory / 'test.vim'
        script.write_text('''set nocompatible
set nomore
set encoding=utf-8
execute 'set runtimepath^=' . fnameescape(''' + quote(ROOT / 'vim') + ''')
runtime plugin/textpad_tasks.vim
let g:test_file = ''' + quote(self.file) + '''
execute 'edit ' . fnameescape(g:test_file)
let g:source_buffer = bufnr('%')
try
''' + body + '''
catch
  call add(v:errors, v:exception . ' at ' . v:throwpoint)
endtry
call writefile([json_encode(v:errors)], ''' + quote(report) + ''')
if !empty(v:errors)
  cquit
endif
qa!
''')
        result = subprocess.run([VIM, '-Nu', 'NONE', '-n', '-es', '-i', 'NONE', '-S', str(script)],
                                capture_output=True, text=True, timeout=25)
        errors = json.loads(report.read_text()) if report.exists() else ['Vim exited without a test report']
        self.assertEqual([], errors, '\n'.join(errors) + '\n' + result.stderr)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_projection_keeps_heading_context_tasks_notes_and_all_source_contents(self):
        self.run_vim('''
call assert_equal(1, textpad_tasks#On())
call assert_equal(['', 'Shopping', '---', '[ ] Milk', '    Organic note'], getline(1, '$'))
call assert_equal('acwrite', &buftype)
call assert_equal(0, &modified)
call assert_equal(g:source_buffer, b:textpad_tasks_source)
call assert_equal(readfile(g:test_file), getbufline(g:source_buffer, 1, '$'))
call assert_equal(1, textpad_tasks#Off())
call assert_equal(g:source_buffer, bufnr('%'))
call assert_equal(0, &modified)
''')

    def test_edit_and_write_preserve_hidden_lines_and_vim_write_hooks(self):
        self.run_vim('''
let g:post_writes = 0
augroup test_write_hooks
  autocmd!
  autocmd BufWritePost * let g:post_writes += 1
augroup END
call textpad_tasks#On()
call setline(4, '[ ] Oat milk 😀')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal('[ ] Oat milk 😀', getbufline(g:source_buffer, 5)[0])
write
call assert_equal(0, &modified)
call assert_equal(0, getbufvar(g:source_buffer, '&modified'))
call assert_equal(1, g:post_writes)
let expected = ['Unrelated note', '', 'Shopping', '---', '[ ] Oat milk 😀', '    Organic note', '[X] Bread', '', 'Done', '---', '[x] Finished']
call assert_equal(expected, readfile(g:test_file))
call textpad_tasks#Off()
call assert_equal(expected, getline(1, '$'))
''')

    def test_toggle_returns_to_corresponding_source_line_and_scrolls_to_cursor(self):
        self.file.write_text('[ ] First\n' + '[X] Hidden\n' * 220 + '[ ] Target\n' + '[X] Other\n' * 220)
        self.run_vim('''
call textpad_tasks#On()
call assert_equal(['[ ] First', '[ ] Target'], getline(1, '$'))
call cursor(2, 6)
call textpad_tasks#Off()
call assert_equal([222, 6], [line('.'), col('.')])
call assert_true(line('w0') <= 222 && line('w$') >= 222)
''')

    def test_mapping_is_user_assignable_and_has_no_default_key(self):
        self.run_vim('''
call assert_equal('', maparg('<F8>', 'n'))
nmap <F8> <Plug>(TextpadTasksToggle)
call feedkeys("\\<F8>", 'xt')
call assert_equal('acwrite', &buftype)
call feedkeys("\\<F8>", 'xt')
call assert_equal(g:source_buffer, bufnr('%'))
''')

    def test_inserted_lines_can_be_saved_and_toggled_repeatedly(self):
        self.run_vim('''
call textpad_tasks#On()
let view = bufnr('%')
call append(4, '[ ] Inserted task')
call textpad_tasks#Changed(view)
let expected = ['Unrelated note', '', 'Shopping', '---', '[ ] Milk', '[ ] Inserted task', '    Organic note', '[X] Bread', '', 'Done', '---', '[x] Finished']
write
call assert_equal(expected, readfile(g:test_file))
call cursor(5, 6)
call assert_equal(1, textpad_tasks#Off())
call assert_equal([6, 6], [line('.'), col('.')])
call assert_equal(expected, getline(1, '$'))
call assert_equal(1, textpad_tasks#On())
call assert_equal(['', 'Shopping', '---', '[ ] Milk', '[ ] Inserted task', '    Organic note'], getline(1, '$'))
call assert_equal(1, textpad_tasks#Off())
''')

    def test_deleting_visible_rows_preserves_interleaved_hidden_lines(self):
        self.file.write_text('[ ] First\n[X] Hidden A\n[ ] Second\n[X] Hidden B\n[ ] Third\n')
        self.run_vim('''
call textpad_tasks#On()
1,2delete _
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[X] Hidden A', '[X] Hidden B', '[ ] Third'], getline(1, '$'))
call assert_equal(3, line('.'))
call assert_equal(1, textpad_tasks#On())
call assert_equal(['[ ] Third'], getline(1, '$'))
write
call assert_equal(['[X] Hidden A', '[X] Hidden B', '[ ] Third'], readfile(g:test_file))
call assert_equal(1, textpad_tasks#Off())
''')

    def test_split_and_join_preserve_hidden_rows(self):
        self.file.write_text('[ ] First\n[X] Hidden A\n[ ] Second\n[X] Hidden B\n')
        self.run_vim(r'''
call textpad_tasks#On()
call feedkeys("gg0fFi\<CR>\<Esc>", 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(['[ ] ', 'First', '[ ] Second'], getline(1, '$'))
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[ ] ', 'First', '[X] Hidden A', '[ ] Second', '[X] Hidden B'], getline(1, '$'))
call textpad_tasks#On()
call feedkeys('ggJ', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[ ] [ ] Second', 'First', '[X] Hidden A', '[X] Hidden B'], getline(1, '$'))
''')

    def test_structural_undo_redo_restores_original_positions_around_hidden_rows(self):
        self.file.write_text('[ ] First\n[X] Hidden A\n[ ] Second\n[X] Hidden B\n[ ] Third\n')
        self.run_vim(r'''
let original = getline(1, '$')
call textpad_tasks#On()
call feedkeys('gg2dd', 'xt')
call textpad_tasks#Changed(bufnr('%'))
let deleted = ['[X] Hidden A', '[X] Hidden B', '[ ] Third']
call assert_equal(deleted, getbufline(g:source_buffer, 1, '$'))
call feedkeys('u', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(original, getbufline(g:source_buffer, 1, '$'))
call feedkeys("\<C-R>", 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(deleted, getbufline(g:source_buffer, 1, '$'))
call feedkeys('u', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(1, textpad_tasks#Off())
call assert_equal(original, getline(1, '$'))
''')

    def test_join_undo_and_undo_branch_preserve_hidden_line_positions(self):
        self.file.write_text('[X] Before\n[ ] First\n[X] Between\n[ ] Second\n[X] After\n')
        self.run_vim(r'''
let original = getline(1, '$')
call textpad_tasks#On()
call feedkeys('ggJ', 'xt')
call textpad_tasks#Changed(bufnr('%'))
let joined = ['[X] Before', '[ ] First [ ] Second', '[X] Between', '[X] After']
call assert_equal(joined, getbufline(g:source_buffer, 1, '$'))
call feedkeys('u', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(original, getbufline(g:source_buffer, 1, '$'))
call feedkeys('dd', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(['[X] Before', '[X] Between', '[ ] Second', '[X] After'], getbufline(g:source_buffer, 1, '$'))
call feedkeys('u', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(original, getbufline(g:source_buffer, 1, '$'))
call feedkeys("\<C-R>", 'xt')
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[X] Before', '[X] Between', '[ ] Second', '[X] After'], getline(1, '$'))
''')

    def test_insertion_at_projection_edges_keeps_hidden_prefix_and_suffix(self):
        self.file.write_text('[X] Before\n[ ] First\n[X] Between\n[ ] Second\n[X] After\n')
        self.run_vim('''
call textpad_tasks#On()
call append(0, '[ ] New first')
call append(line('$'), ['[ ] New last', '[ ] Another new last'])
write
call cursor(line('$'), 6)
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[X] Before', '[ ] New first', '[ ] First', '[X] Between', '[ ] Second', '[ ] New last', '[ ] Another new last', '[X] After'], getline(1, '$'))
call assert_equal(7, line('.'))
call assert_equal(getline(1, '$'), readfile(g:test_file))
call assert_equal(1, textpad_tasks#On())
call assert_equal(1, textpad_tasks#Off())
''')

    def test_insert_then_delete_without_intermediate_sync_preserves_hidden_rows(self):
        self.file.write_text('[ ] First\n[X] Hidden A\n[ ] Second\n[X] Hidden B\n[ ] Third\n')
        self.run_vim('''
call textpad_tasks#On()
call append(1, '[ ] Inserted')
3delete _
call setline(3, '[ ] Renamed third')
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[ ] First', '[ ] Inserted', '[X] Hidden A', '[X] Hidden B', '[ ] Renamed third'], getline(1, '$'))
''')

    def test_duplicate_rows_and_batched_edits_use_actual_edit_positions(self):
        self.file.write_text('[ ] Same\n[X] Hidden A\n[ ] Same\n[X] Hidden B\n[ ] Last\n')
        self.run_vim('''
call textpad_tasks#On()
2delete _
call append(2, '[ ] New')
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[ ] Same', '[X] Hidden A', '[X] Hidden B', '[ ] Last', '[ ] New'], getline(1, '$'))
call assert_equal(1, textpad_tasks#On())
call assert_equal(['[ ] Same', '[ ] Last', '[ ] New'], getline(1, '$'))
''')

    def test_deleting_all_visible_rows_preserves_hidden_text_and_can_undo(self):
        self.file.write_text('[X] Before\n[ ] First\n[X] Between\n[ ] Second\n[X] After\n')
        self.run_vim('''
let original = getline(1, '$')
call textpad_tasks#On()
call feedkeys('ggdG', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(['[X] Before', '', '[X] Between', '[X] After'], getbufline(g:source_buffer, 1, '$'))
call feedkeys('u', 'xt')
call textpad_tasks#Changed(bufnr('%'))
call assert_equal(original, getbufline(g:source_buffer, 1, '$'))
call feedkeys('ggdG', 'xt')
call assert_equal(1, textpad_tasks#Off())
call assert_equal(['[X] Before', '', '[X] Between', '[X] After'], getline(1, '$'))
call assert_equal(1, textpad_tasks#On())
call assert_equal(['No uncompleted tasks found.'], getline(1, '$'))
call assert_equal(1, textpad_tasks#Off())
''')

    def test_alternate_range_append_and_saveas_writes_are_blocked(self):
        self.run_vim(r'''
call textpad_tasks#On()
let view_name = bufname('%')
let original = readfile(g:test_file)
let output = g:test_file . '.other'
for command in ['write ' . fnameescape(output), '1,2write! ' . fnameescape(g:test_file), 'write >>' . fnameescape(output), 'write !cat', 'saveas ' . fnameescape(output)]
  let stopped = 0
  try
    execute command
  catch /Textpad tasks:\|E139:/
    let stopped = 1
  endtry
  call assert_equal(1, stopped, command)
  call assert_equal(view_name, bufname('%'), command)
  call assert_equal(original, readfile(g:test_file), command)
  call assert_equal(0, filereadable(output), command)
endfor
''')

    def test_readonly_source_is_not_written_without_force(self):
        self.run_vim('''
setlocal readonly
call textpad_tasks#On()
call setline(4, '[ ] Changed in memory')
let stopped = 0
try
  write
catch /E45:/
  let stopped = 1
endtry
call assert_equal(1, stopped)
call assert_equal('[ ] Milk', readfile(g:test_file)[4])
call textpad_tasks#Off()
call assert_equal(1, &readonly)
''')

    def test_formatting_and_no_final_newline_are_preserved(self):
        raw = '\ufeffHeader\r\n---\r\n[ ] café 😀\r\n[X] Keep this hidden'.encode('utf-8')
        self.file.write_bytes(raw)
        self.run_vim('''
setlocal nofixendofline
let encoding = &fileencoding
let format = &fileformat
let bom = &bomb
let eol = &endofline
call textpad_tasks#On()
call setline(3, '[ ] Tea 😀')
write
call textpad_tasks#Off()
call assert_equal([encoding, format, bom, eol], [&fileencoding, &fileformat, &bomb, &endofline])
''')
        self.assertEqual(raw.replace('café'.encode(), b'Tea'), self.file.read_bytes())

    def test_utf16_files_are_saved_through_the_original_encoding(self):
        original = 'Shopping\r\n---\r\n[ ] café 😀\r\n[X] Hidden\r\n'
        self.file.write_bytes(b'\xff\xfe' + original.encode('utf-16-le'))
        self.run_vim('''
call assert_equal('utf-16le', &fileencoding)
call textpad_tasks#On()
call setline(3, '[ ] Tea 😀')
write
call textpad_tasks#Off()
call assert_equal('utf-16le', &fileencoding)
''')
        self.assertEqual(b'\xff\xfe' + original.replace('café', 'Tea').encode('utf-16-le'), self.file.read_bytes())

    def test_write_formatter_changes_are_refreshed_without_losing_hidden_lines(self):
        self.run_vim('''
augroup test_formatter
  autocmd!
  autocmd BufWritePre * call append(0, 'Inserted by a formatter')
augroup END
call textpad_tasks#On()
call cursor(4, 6)
call setline(4, '[ ] Edited task')
write
sleep 10m
call assert_equal(['', 'Shopping', '---', '[ ] Edited task', '    Organic note'], getline(1, '$'))
call assert_equal(4, line('.'))
call textpad_tasks#Off()
call assert_equal([6, 6], [line('.'), col('.')])
call assert_equal('Inserted by a formatter', getline(1))
call assert_equal('[ ] Edited task', getline(6))
call assert_equal('[X] Bread', getline(8))
''')

    def test_write_failure_retains_source_and_projection_edits(self):
        self.run_vim('''
augroup test_failed_write
  autocmd!
  autocmd BufWritePre * throw 'Simulated write failure'
augroup END
call textpad_tasks#On()
call setline(4, '[ ] Still in memory')
let view = bufnr('%')
try
  write
catch /Simulated write failure/
endtry
call assert_equal(view, bufnr('%'))
call assert_equal(1, &modified)
call assert_equal('[ ] Still in memory', getline(4))
call assert_equal('[ ] Still in memory', getbufline(g:source_buffer, 5)[0])
call assert_equal('[ ] Milk', readfile(g:test_file)[4])
''')

    def test_refresh_recomputes_completed_tasks_and_undo_works_for_inline_edits(self):
        self.run_vim('''
call textpad_tasks#On()
execute "normal! 4G0f rX"
call textpad_tasks#Changed(bufnr('%'))
call assert_equal('[X] Milk', getbufline(g:source_buffer, 5)[0])
undo
call textpad_tasks#Changed(bufnr('%'))
call assert_equal('[ ] Milk', getbufline(g:source_buffer, 5)[0])
execute "normal! 4G0f rX"
call textpad_tasks#Changed(bufnr('%'))
call textpad_tasks#Refresh()
call assert_equal(['No uncompleted tasks found.'], getline(1, '$'))
call assert_equal(0, &modifiable)
call textpad_tasks#Off()
call assert_equal(1, &modifiable)
call assert_equal('[X] Milk', getline(5))
''')

    def test_concurrent_source_edits_are_not_overwritten_and_recovery_retains_both(self):
        self.run_vim('''
call textpad_tasks#On()
let view = bufnr('%')
call setbufline(g:source_buffer, 5, '[ ] Changed in another window')
call setline(4, '[ ] Changed in filtered view')
call textpad_tasks#Changed(view)
call assert_equal('[ ] Changed in another window', getbufline(g:source_buffer, 5)[0])
call textpad_tasks#Recover()
call assert_equal('', &buftype)
call assert_equal('[ ] Changed in filtered view', getline(5))
call assert_equal('[X] Bread', getline(7))
call assert_equal('[ ] Changed in another window', getbufline(g:source_buffer, 5)[0])
call assert_equal('[ ] Changed in filtered view', getbufline(view, 4)[0])
''')

    def test_structural_recovery_preserves_typed_lines_and_hidden_snapshot(self):
        self.run_vim('''
call textpad_tasks#On()
call append(4, 'An inserted recovery line')
call textpad_tasks#Recover()
call assert_true(index(getline(1, '$'), 'An inserted recovery line') >= 0)
call assert_true(index(getline(1, '$'), '[X] Bread') >= 0)
call assert_equal('[ ] Milk', getbufline(g:source_buffer, 5)[0])
''')

    def test_concurrent_source_edits_hold_structural_edits_without_losing_either(self):
        self.run_vim('''
call textpad_tasks#On()
let view = bufnr('%')
call setbufline(g:source_buffer, 7, '[X] Updated elsewhere')
call append(4, '[ ] Inserted here')
call textpad_tasks#Changed(view)
call assert_equal(0, textpad_tasks#Off())
call assert_equal('[X] Updated elsewhere', getbufline(g:source_buffer, 7)[0])
call assert_equal(1, textpad_tasks#Recover())
call assert_equal('[ ] Inserted here', getline(6))
call assert_equal('[X] Bread', getline(8))
call assert_equal('[X] Updated elsewhere', getbufline(g:source_buffer, 7)[0])
call assert_equal('[ ] Inserted here', getbufline(view, 5)[0])
''')

    def test_multiple_windows_and_original_bufhidden_settings_are_preserved(self):
        self.run_vim('''
setlocal bufhidden=wipe
setlocal nofoldenable
let first = win_getid()
split
call textpad_tasks#On()
let view = bufnr('%')
let second = win_getid()
call win_gotoid(first)
call assert_equal(g:source_buffer, bufnr('%'))
call textpad_tasks#On()
call textpad_tasks#Off()
call assert_true(bufexists(view))
call assert_equal('hide', &bufhidden)
call assert_equal(0, &foldenable)
call win_gotoid(second)
call textpad_tasks#Off()
call assert_equal('wipe', &bufhidden)
call assert_equal(0, &foldenable)
call assert_false(bufexists(view))
''')

    def test_initially_modified_and_unnamed_source_buffers_are_retained(self):
        self.run_vim('''
enew
call setline(1, ['[ ] New task', '[X] Hidden task'])
let source = bufnr('%')
call textpad_tasks#On()
call assert_equal(['[ ] New task'], getline(1, '$'))
call assert_equal(1, &modified)
call textpad_tasks#Off()
call assert_equal(source, bufnr('%'))
call assert_equal(['[ ] New task', '[X] Hidden task'], getline(1, '$'))
call assert_equal(1, &modified)
''')

    def test_native_package_installs_and_help_tags_resolve(self):
        archive = self.directory / 'textpad-tasks-vim.zip'
        subprocess.run([sys.executable, str(ROOT / 'scripts/package-vim-plugin.py'), '--output', str(archive)],
                       check=True, capture_output=True, env=dict(os.environ, VIM_BINARY=VIM))
        site = self.directory / 'site'
        install = site / 'pack/textpad/start'
        with zipfile.ZipFile(archive) as package:
            package.extractall(install)
        rc = self.directory / 'vimrc'
        rc.write_text("set nocompatible\nset loadplugins\nexecute 'set packpath^=' . fnameescape(" + quote(site) + ")\n")
        script = self.directory / 'installed.vim'
        errors = self.directory / 'install-errors.json'
        script.write_text('''
try
  call assert_equal(2, exists(':TextpadTasksToggle'))
  help textpad-tasks
  call assert_equal('help', &filetype)
  call assert_equal('textpad_tasks.txt', expand('%:t'))
catch
  call add(v:errors, v:exception . ' at ' . v:throwpoint)
endtry
call writefile([json_encode(v:errors)], ''' + quote(errors) + ''')
qa!
''')
        result = subprocess.run([VIM, '-Nu', str(rc), '-n', '-es', '-i', 'NONE', '-S', str(script)],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual([], json.loads(errors.read_text()), result.stderr)
        self.assertEqual(0, result.returncode, result.stderr)

    @unittest.skipUnless(shutil.which('node'), 'Node is needed only for comparison with the existing web filter.')
    def test_vim_filter_matches_web_filter_on_known_and_random_documents(self):
        cases = [self.initial, ['Shopping', '---', '[ ] Milk', '[X] Bread'],
                 ['# Work', '## First', '- [ ] Open', '* [x] Done'],
                 ['[ ] Parent', '\tPlain note', '\t[X] Completed child', '\t\t[ ] Open child'],
                 ['', '# Shopping', '---', '[ ] Milk'], ['', 'Finished', '---', '[X] Done', '# Open', '[ ] Todo'],
                 ['[X] Old', '', '    New', '    ---', '    [ ] Todo'], [''], []]
        rng = random.Random(9329)
        tokens = ['', 'plain', '[ ] Open', '[x] Done', '[X] Done', '- [ ] Bullet', '+ [x] Bullet', '* [ ] Bullet',
                  '# Root', '## Section', '#### Deep', '####### Not a heading', '#tag', '---', '- - -', '===', 'Title']
        for _ in range(160):
            cases.append([rng.choice(['', ' ', '    ', '\t', '\t\t', '  \t']) + rng.choice(tokens)
                          for _ in range(rng.randrange(1, 70))])
        fixture = self.directory / 'cases.json'
        fixture.write_text(json.dumps(cases))
        code = '''import {filterTasks} from './server/frontend/taskFilter.js';
import fs from 'node:fs';
const cases=JSON.parse(fs.readFileSync(process.argv[1],'utf8'));
console.log(JSON.stringify(cases.map(lines=>{const r=filterTasks(lines.join('\\n'),4);return {visible:r.visibleLines,context:[...r.isContext]};})));'''
        reference = json.loads(subprocess.check_output(['node', '--input-type=module', '-e', code, str(fixture)], cwd=ROOT))
        # An empty Vim line list is not a buffer; align that fixture with an empty buffer.
        for index, case in enumerate(cases):
            if not case:
                cases[index] = ['']
        fixture.write_text(json.dumps({'cases': cases, 'expected': reference}))
        self.run_vim('''
let fixtures = json_decode(join(readfile(''' + quote(fixture) + '''), "\\n"))
for i in range(len(fixtures.cases))
  call assert_equal(fixtures.expected[i], textpad_tasks#Filter(fixtures.cases[i], 4), 'filter case ' . i)
endfor
''')


if __name__ == '__main__':
    unittest.main()
