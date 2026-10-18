" Native Vimscript; no Python, server, or CLI dependency.
let s:views = {}

function! s:Message(text) abort
  echohl WarningMsg
  echomsg 'Textpad tasks: ' . a:text
  echohl None
endfunction

function! textpad_tasks#Filter(raw_lines, tab_size) abort
  let lines = map(copy(a:raw_lines), 'substitute(v:val, "\r$", "", "")')
  let line_count = len(lines)
  let size = a:tab_size > 0 ? a:tab_size : 4
  let types = []
  let indents = []
  let levels = []
  for line in lines
    let leading = matchstr(line, '^[ \t]*')
    let indent = 0
    for char in split(leading, '\zs')
      let indent += char ==# "\t" ? size - indent % size : 1
    endfor
    call add(indents, indent)
    let rest = strpart(line, strlen(leading))
    let task = substitute(rest, '^\%(-\|\*\|+\) ', '', '')
    let header = rest =~# '^#\{1,6}\%($\| \)'
    call add(levels, header ? strlen(matchstr(rest, '^#\+')) : 0)
    call add(types, empty(rest) ? 0 : stridx(task, '[ ]') == 0 ? 1
          \ : task =~# '^\[[Xx]\]' ? 2 : header ? 3 : 4)
  endfor
  let divider_ends = repeat([-1], line_count)
  if line_count > 1
    for title in range(line_count - 1)
      if types[title] != 0 && (title == 0 || types[title - 1] == 0)
            \ && lines[title + 1] =~# '^[ \t]*-\+[ \t]*$'
        let divider_ends[max([0, title - 1])] = title + 1
      endif
    endfor
  endif
  let parents = repeat([-1], line_count)
  let stack = []
  let headers = []
  let enclosing = []
  for i in range(line_count)
    call add(enclosing, copy(headers))
    if types[i] == 3
      while !empty(headers) && levels[headers[-1]] >= levels[i]
        call remove(headers, -1)
      endwhile
      call add(headers, i)
    endif
    if divider_ends[i] >= 0 || types[i] == 3
      let stack = []
    endif
    if types[i] == 0
      continue
    endif
    while !empty(stack) && indents[stack[-1]] >= indents[i]
      call remove(stack, -1)
    endwhile
    if !empty(stack)
      let parents[i] = stack[-1]
    endif
    call add(stack, i)
  endfor
  let visible = repeat([0], line_count)
  let context = repeat([0], line_count)
  let notes = repeat([0], line_count)
  let section = -1
  for i in range(line_count)
    if divider_ends[i] >= 0
      let section = i
    elseif types[i] == 3 && (section < 0 || i != divider_ends[section] - 1)
      let section = -1
    endif
    if types[i] == 1
      let visible[i] = 1
      if section >= 0
        for h in range(section, divider_ends[section])
          let visible[h] = 1
          let context[h] = 1
        endfor
      endif
      let top = i
      let parent = parents[i]
      while parent != -1
        let visible[parent] = 1
        let context[parent] = 1
        let top = parent
        let parent = parents[parent]
      endwhile
      for h in enclosing[top]
        let visible[h] = 1
        let context[h] = 1
      endfor
    endif
  endfor
  for i in range(line_count)
    if types[i] == 4 && parents[i] != -1
      let parent = parents[i]
      if types[parent] == 1 || notes[parent]
        let visible[i] = 1
        let notes[i] = 1
      endif
    endif
  endfor
  return {'visible': filter(range(line_count), 'visible[v:val]'), 'context': context}
endfunction

function! s:State(buffer) abort
  return get(s:views, a:buffer, {})
endfunction

function! s:Current() abort
  let buffer = bufnr('%')
  return has_key(s:views, buffer) ? buffer : get(b:, 'textpad_tasks_view', -1)
endfunction

function! s:SourceExists(state) abort
  return bufloaded(a:state.source)
endfunction

function! s:Sync(view, quiet) abort
  let state = s:State(a:view)
  if empty(state) || state.busy
    return 1
  endif
  let current = getbufline(a:view, 1, '$')
  if current ==# state.projected && s:SourceExists(state)
    call setbufvar(a:view, '&modified', getbufvar(state.source, '&modified'))
    return 1
  endif
  let problem = ''
  if !s:SourceExists(state)
    let problem = 'Source buffer is unavailable. Use :TextpadTasksRecover to preserve these edits.'
  elseif empty(state.map) || len(current) != len(state.map)
    let problem = 'Toggle to the full view before inserting/deleting lines. Undo that operation, or use :TextpadTasksRecover to keep a copy.'
  elseif getbufline(state.source, 1, '$') !=# state.lines
    let problem = 'Source changed in another window. Use :TextpadTasksRecover to keep these edits; undo them here before :TextpadTasksRefresh.'
  elseif !getbufvar(state.source, '&modifiable')
    let problem = 'Source buffer is not modifiable.'
  endif
  if !empty(problem)
    let state.problem = problem
    call setbufvar(a:view, '&modified', 1)
    if !a:quiet && get(state, 'last_problem', '') !=# problem
      call s:Message(problem)
      let state.last_problem = problem
    endif
    return 0
  endif
  let complete = copy(state.lines)
  for i in range(len(state.map))
    let complete[state.map[i] - 1] = current[i]
  endfor
  let state.busy = 1
  try
    if setbufline(state.source, 1, complete) != 0
      throw 'Textpad tasks: Could not update the full source buffer.'
    endif
    let state.lines = complete
    let state.projected = current
    let state.problem = ''
    let state.last_problem = ''
    call setbufvar(a:view, '&modified', getbufvar(state.source, '&modified'))
  finally
    let state.busy = 0
  endtry
  return 1
endfunction

function! textpad_tasks#Changed(view) abort
  call s:Sync(a:view, 0)
endfunction

function! s:RealPosition(view) abort
  let state = s:State(a:view)
  return empty(state.map) ? [state.start_line, state.start_col]
        \ : [state.map[min([line('.') - 1, len(state.map) - 1])], col('.')]
endfunction

function! s:Place(state, real, column) abort
  let nearest = 0
  while nearest + 1 < len(a:state.map) && a:state.map[nearest] < a:real
    let nearest += 1
  endwhile
  call cursor(nearest + 1, a:column)
endfunction

function! s:Render(view) abort
  let state = s:State(a:view)
  let source_lines = getbufline(state.source, 1, '$')
  let tab_size = getbufvar(state.source, 'textpad_tasks_tab_size', get(g:, 'textpad_tasks_tab_size', 4))
  let result = textpad_tasks#Filter(source_lines, tab_size)
  let state.lines = source_lines
  let state.map = map(copy(result.visible), 'v:val + 1')
  let state.projected = map(copy(state.map), 'source_lines[v:val - 1]')
  if empty(state.projected)
    let state.projected = ['No uncompleted tasks found.']
  endif
  let state.busy = 1
  try
    call setbufvar(a:view, '&modifiable', 1)
    " A rebuilt projection starts a new undo history. Full-buffer undo is kept.
    let undo_levels = getbufvar(a:view, '&undolevels')
    call setbufvar(a:view, '&undolevels', -1)
    call setbufline(a:view, 1, state.projected)
    if len(getbufline(a:view, 1, '$')) > len(state.projected)
      call deletebufline(a:view, len(state.projected) + 1, '$')
    endif
    call setbufvar(a:view, '&undolevels', undo_levels)
    if exists('*prop_type_add')
      call prop_clear(1, len(state.projected), {'bufnr': a:view})
      if empty(prop_type_get('TextpadTasksContext', {'bufnr': a:view}))
        call prop_type_add('TextpadTasksContext', {'bufnr': a:view, 'highlight': 'TextpadTasksContext', 'combine': 0})
      endif
      for i in range(len(state.map))
        let source_line = state.map[i] - 1
        if result.context[source_line] && !empty(state.projected[i])
          call prop_add(i + 1, 1, {'bufnr': a:view, 'type': 'TextpadTasksContext', 'length': strlen(state.projected[i])})
        endif
      endfor
    endif
    call setbufvar(a:view, '&modifiable', !empty(state.map) && getbufvar(state.source, '&modifiable'))
    call setbufvar(a:view, '&readonly', getbufvar(state.source, '&readonly'))
    call setbufvar(a:view, '&modified', getbufvar(state.source, '&modified'))
  finally
    let state.busy = 0
  endtry
endfunction

function! textpad_tasks#On() abort
  if has_key(s:views, bufnr('%'))
    return 1
  endif
  if &buftype !=# ''
    call s:Message('Open a normal text buffer first.')
    return 0
  endif
  let source = bufnr('%')
  let real = line('.')
  let column = col('.')
  let view = get(b:, 'textpad_tasks_view', -1)
  if !has_key(s:views, view)
    let name = 'textpad-tasks://' . source . '/' . (empty(bufname(source)) ? '[No Name]' : fnamemodify(bufname(source), ':t'))
    let view = bufadd('')
    call bufload(view)
    let s:views[view] = {'source': source, 'source_hidden': &l:bufhidden, 'busy': 0,
          \ 'start_line': real, 'start_col': column, 'projected': [], 'map': [], 'lines': [], 'name': name}
    let b:textpad_tasks_view = view
    call setbufvar(source, '&bufhidden', 'hide')
    call setbufvar(view, 'textpad_tasks_source', source)
    call setbufvar(view, '&buftype', 'acwrite')
    call setbufvar(view, '&bufhidden', 'hide')
    call setbufvar(view, '&swapfile', 0)
    call setbufvar(view, '&buflisted', 0)
    call setbufvar(view, '&undofile', 0)
    highlight default link TextpadTasksContext Comment
    augroup textpad_tasks_views
      execute 'autocmd TextChanged,TextChangedI,InsertLeave,BufLeave <buffer=' . view . '> call textpad_tasks#Changed(' . view . ')'
      execute 'autocmd BufEnter <buffer=' . view . '> call textpad_tasks#Entered(' . view . ')'
      execute 'autocmd BufWriteCmd <buffer=' . view . '> ++nested call textpad_tasks#Write(' . view . ', expand("<afile>"), v:cmdbang, v:cmdarg)'
      execute 'autocmd FileWriteCmd,FileAppendCmd,FilterWritePre,BufFilePre <buffer=' . view . '> throw "Textpad tasks: Toggle to the full view before range writes, appends, filters, or Save As."'
    augroup END
  elseif !s:Sync(view, 0)
    return 0
  endif
  call s:Render(view)
  let w:textpad_tasks_folds = &l:foldenable
  execute 'keepalt keepjumps hide buffer ' . view
  if empty(bufname(view))
    execute 'silent noautocmd file ' . fnameescape(s:views[view].name)
  endif
  setlocal nofoldenable
  call s:Place(s:views[view], real, column)
  return 1
endfunction

function! textpad_tasks#Off() abort
  let view = bufnr('%')
  let state = s:State(view)
  if empty(state)
    return 1
  endif
  if !s:Sync(view, 0) || !s:SourceExists(state)
    return 0
  endif
  if getbufline(state.source, 1, '$') !=# state.lines
    call textpad_tasks#Refresh()
  endif
  let [real, column] = s:RealPosition(view)
  execute 'keepalt keepjumps hide buffer ' . state.source
  if exists('w:textpad_tasks_folds')
    let &l:foldenable = w:textpad_tasks_folds
    unlet w:textpad_tasks_folds
  endif
  call cursor(real, column)
  silent! normal! zv
  normal! zz
  if empty(win_findbuf(view))
    execute 'silent bwipeout! ' . view
  endif
  return 1
endfunction

function! textpad_tasks#Toggle() abort
  return has_key(s:views, bufnr('%')) ? textpad_tasks#Off() : textpad_tasks#On()
endfunction

function! textpad_tasks#Refresh() abort
  let view = s:Current()
  let state = s:State(view)
  if empty(state) || !s:Sync(view, 0) || !s:SourceExists(state)
    return 0
  endif
  let position = bufnr('%') == view ? s:RealPosition(view) : [state.start_line, state.start_col]
  let current_source = getbufline(state.source, 1, '$')
  if current_source !=# state.lines
    let anchor = get(state.lines, position[0] - 1, '')
    let nearest = -1
    for i in range(len(current_source))
      if !empty(anchor) && current_source[i] ==# anchor
            \ && (nearest < 0 || abs(i + 1 - position[0]) < abs(nearest + 1 - position[0]))
        let nearest = i
      endif
    endfor
    if nearest >= 0
      let position[0] = nearest + 1
    endif
  endif
  call s:Render(view)
  if bufnr('%') == view
    call s:Place(state, position[0], position[1])
  endif
  return 1
endfunction

function! textpad_tasks#Entered(view) abort
  let state = s:State(a:view)
  if !empty(state) && !state.busy && s:SourceExists(state)
        \ && getbufline(a:view, 1, '$') ==# state.projected
        \ && getbufline(state.source, 1, '$') !=# state.lines
    call textpad_tasks#Refresh()
  endif
endfunction

function! textpad_tasks#AfterWrite(view, timer) abort
  if bufexists(a:view) && bufnr('%') == a:view
    call textpad_tasks#Refresh()
  endif
endfunction

function! textpad_tasks#Write(view, target, bang, arguments) abort
  let state = s:State(a:view)
  if empty(state) || a:target !=# bufname(a:view) || !empty(a:arguments)
    throw 'Textpad tasks: Toggle to the full view before writing another file or using ++options.'
  endif
  if !s:Sync(a:view, 0)
    throw 'Textpad tasks: Write stopped; fix the filtered edit or use :TextpadTasksRecover.'
  endif
  if !s:SourceExists(state) || empty(bufname(state.source))
    throw 'Textpad tasks: Name the source file in the full view before writing.'
  endif
  let window = win_getid()
  let saved_view = winsaveview()
  try
    execute 'noautocmd keepalt keepjumps hide buffer ' . state.source
    execute 'keepalt write' . (a:bang ? '!' : '')
  finally
    if win_gotoid(window) && bufexists(a:view)
      execute 'noautocmd keepalt keepjumps hide buffer ' . a:view
      call winrestview(saved_view)
    endif
  endtry
  call setbufvar(a:view, '&modified', getbufvar(state.source, '&modified'))
  if getbufline(state.source, 1, '$') !=# state.lines && exists('*timer_start')
    call timer_start(0, function('textpad_tasks#AfterWrite', [a:view]))
  endif
endfunction

function! textpad_tasks#Recover() abort
  let state = s:State(s:Current())
  if empty(state)
    return 0
  endif
  let current = getbufline(s:Current(), 1, '$')
  let complete = copy(state.lines)
  if len(current) == len(state.map)
    for i in range(len(state.map))
      let complete[state.map[i] - 1] = current[i]
    endfor
  else
    " Keep every typed line and the full snapshot if line identities are unknown.
    let complete = ['=== FILTERED EDITS ==='] + current + ['', '=== FULL SOURCE SNAPSHOT ==='] + complete
  endif
  keepalt hide enew
  call setline(1, complete)
  setlocal modified
  call s:Message('Recovery copy opened in a new unnamed buffer; the source and filtered buffers are retained.')
  return 1
endfunction

function! textpad_tasks#Wiped(buffer) abort
  let state = s:State(a:buffer)
  if !empty(state)
    if bufexists(state.source)
      call setbufvar(state.source, '&bufhidden', state.source_hidden)
      call setbufvar(state.source, 'textpad_tasks_view', -1)
    endif
    call remove(s:views, a:buffer)
  endif
endfunction
