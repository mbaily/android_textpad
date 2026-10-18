# Textpad uncompleted tasks for Vim 9

A native Vim 9.0+ plugin that toggles the same uncompleted-tasks view used by Textpad's Android and browser editors. It has no Python, CLI, server, or plugin-manager dependency.

[Download plugin ZIP](https://git23069.hopto.org/android_textpad/editor/static/textpad-tasks-vim.zip) · [SHA-256](https://git23069.hopto.org/android_textpad/editor/static/textpad-tasks-vim.zip.sha256) · [Online guide](https://git23069.hopto.org/android_textpad/editor/static/textpad-tasks-vim-README.txt)

## Install

Extract the ZIP into Vim's native `pack` directory. The archive contains a `textpad-tasks/` directory with `plugin/`, `autoload/`, and `doc/` beneath it.

Linux/macOS:

```sh
mkdir -p ~/.vim/pack/textpad/start
unzip textpad-tasks-vim.zip -d ~/.vim/pack/textpad/start
```

Windows: extract into `%USERPROFILE%\vimfiles\pack\textpad\start\`.

Restart Vim or run `:packloadall`. From a repository checkout, you can instead copy the `vim/` directory to `~/.vim/pack/textpad/start/textpad-tasks/` (or the Windows equivalent), then run `:helptags` on its `doc/` directory.

## Assign a key

The plugin deliberately assigns no default key. Put either of these in your vimrc:

```vim
nmap <F8> <Plug>(TextpadTasksToggle)
" Or choose a leader mapping:
nmap <leader>u <Plug>(TextpadTasksToggle)
```

You can also map the command directly, including interactively with `:map`:

```vim
nnoremap <F8> <Cmd>TextpadTasksToggle<CR>
```

For example, `:map <F8> <Plug>(TextpadTasksToggle)` assigns the toggle in Normal mode. Use `nmap` when you want the mapping restricted to Normal mode.

## Use

- `:TextpadTasksToggle` switches between full and filtered views.
- `:TextpadTasksOn` / `:TextpadTasksOff` select a view explicitly.
- `:TextpadTasksRefresh` recomputes which tasks and headings are visible.
- `:TextpadTasksRecover` opens a new unnamed recovery copy if a filtered edit cannot be safely mapped back.
- `:help textpad-tasks` opens the installed help.

The filtered buffer shows `[ ]` tasks (including `- [ ]`, `* [ ]`, and `+ [ ]`), their indentation ancestors, enclosing Markdown headings, and plain notes indented beneath open tasks. Context lines use the `Comment` highlight group through `TextpadTasksContext`.

Completed `[X]`/`[x]` and cancelled `[C]`/`[c]` tasks stay hidden unless they are ancestors of an open task. They are not treated as plain notes beneath open tasks.

It also keeps blank/title/dash-divider heading blocks only when that section contains an open task. A title at the beginning of the file does not need a preceding blank line. Dash dividers may have surrounding spaces/tabs and any positive dash count. Tasks do not need to be indented beneath these headings. A new divider heading or Markdown heading ends a divider section.

The default indentation tab width is four columns, matching Textpad. It is independent of Vim's display `tabstop`. Override it globally or for a source buffer if needed:

```vim
let g:textpad_tasks_tab_size = 4
" Or:
let b:textpad_tasks_tab_size = 8
```

## Editing and writing

Visible-line edits update the full source buffer in memory. The source remains loaded with its normal undo/swap behavior. `:write` (and `:wq`) writes the full source file, including every hidden line, through Vim's normal write path and write hooks. Encodings, BOMs, fileformat, readonly checks, and normal external-change protection belong to that source buffer. A source with no filename must be named from the full view before writing.

The filtered buffer is an `acwrite` projection with a `textpad-tasks://` name, so it cannot silently overwrite the real file with a partial document. It has no fold placeholders; hidden text is absent from the displayed projection. Line numbers in this view count projected rows. Toggling off places the cursor on the corresponding full-file line and scrolls it into view.

Line edits—including insertions, deletions, splits, and joins—are tracked and synchronized back to the underlying source buffer while preserving hidden lines and comments in place. Undo and redo in the filtered view restore edited and deleted rows to their original positions around hidden text. `:write other-file`, range writes, appends, shell filters, `:saveas`, and renaming the filtered buffer require returning to the full view first.

Task membership is stable while editing. For example, changing `[ ]` to `[X]` leaves that row visible until you refresh or toggle the view off/on, as in the app. Inline undo/redo works in the projection. Refreshing rebuilds its undo history; the full source buffer retains its own undo history.

If the source changes in another Vim window while the projection also has new edits, or if an edit cannot be mapped cleanly, propagation stops and both versions are kept. `:TextpadTasksRecover` opens a full-file candidate using the last consistent source snapshot and the edited visible rows. If row identities cannot be reconciled, it preserves the typed filtered fragment and the complete source snapshot in clearly marked sections. The existing source and projection are retained. Save the new unnamed copy to a separate file, then undo the conflicting edit in the projection before refreshing or returning to the full view.

This plugin edits local Vim buffers. With `textpad-sync watch` running separately, a normal `:write` is detected as a saved local file change; the plugin does not make server requests itself.

## Verify and package

From the repository root:

```sh
python3 -m unittest discover -s vim/tests -v
VIM_BINARY=/path/to/vim90 python3 -m unittest discover -s vim/tests -v
python3 scripts/package-vim-plugin.py
```

The real-Vim tests cover heading/task/note filtering, 169 known/random documents compared with the web implementation, user mappings, editable projections, full-file writes and write hooks, cursor scrolling, UTF-8/UTF-16/BOM/CRLF/no-final-newline preservation, readonly/write failures, multiple windows, structural-edit holds, concurrent edits, recovery, and refresh/undo.

Tested on Linux using unpatched Vim 9.0.0000 and Vim 9.1. Runtime files use compatible Vimscript; the standard `+eval`/`+autocmd` features are required. Text properties and timers are used when available. Python and Node are only used by the test/build tools.
