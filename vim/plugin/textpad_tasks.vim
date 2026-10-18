" Textpad uncompleted-tasks view. Compatible with Vim 9.0 and later.
if exists('g:loaded_textpad_tasks') || v:version < 900
  finish
endif
let g:loaded_textpad_tasks = '0.1.0'

command! -bar TextpadTasksToggle call textpad_tasks#Toggle()
command! -bar TextpadTasksOn call textpad_tasks#On()
command! -bar TextpadTasksOff call textpad_tasks#Off()
command! -bar TextpadTasksRefresh call textpad_tasks#Refresh()
command! -bar TextpadTasksRecover call textpad_tasks#Recover()

nnoremap <silent> <Plug>(TextpadTasksToggle) <Cmd>TextpadTasksToggle<CR>

augroup textpad_tasks_lifetime
  autocmd!
  autocmd BufWipeout * call textpad_tasks#Wiped(str2nr(expand('<abuf>')))
augroup END
