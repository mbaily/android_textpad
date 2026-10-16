"""Optional Chrome smoke test against an isolated temporary server and database."""
import base64, uuid
from contextlib import contextmanager
from pathlib import Path
import socket
import tempfile
import threading
import time

from textpad_server import create_app
from werkzeug.serving import make_server
from playwright.sync_api import sync_playwright, expect

TOKEN='local-preview-test-token-with-32-characters'


@contextmanager
def fixture():
    with tempfile.TemporaryDirectory(prefix='textpad-browser-qa-') as folder:
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0))
            port=sock.getsockname()[1]
        base=f'http://127.0.0.1:{port}'
        app=create_app({'DATABASE':str(Path(folder)/'db.sqlite3'),'API_TOKEN':TOKEN,
                        'SECRET_KEY':'local-preview-session-secret-with-32-characters',
                        'PUBLIC_URL':base,'SESSION_COOKIE_SECURE':False})
        store=app.extensions['store']
        store.put('sample-notes','Notes.txt','Textpad desktop editor\r\n\r\nUnicode: café · e\u0301 · 😀 · العربية · 日本語\r\n'.encode(),None,'seed-notes')
        store.put('sample-log','Tasks.txt',b'First item\nSecond item\n',None,'seed-tasks')
        server=make_server('127.0.0.1',port,app,threaded=True)
        thread=threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            yield base
        finally:
            server.shutdown()
            thread.join()
            server.server_close()


def main():
    with fixture() as BASE, sync_playwright() as p:
        browser=p.chromium.launch(channel='chrome',headless=True,args=['--no-sandbox'])
        context=browser.new_context(viewport={'width':1440,'height':960},accept_downloads=True)
        page=context.new_page(); errors=[]
        page.on('pageerror',lambda error:errors.append(str(error)))
        page.on('console',lambda message:errors.append(message.text) if message.type=='error' else None)
        page.goto(BASE+'/editor/'); page.locator('#credential').fill(TOKEN)
        page.get_by_role('button',name='Sign in',exact=True).click(); page.locator('.file-item').first.wait_for()
        expect(page.locator('#connection-label')).to_have_text('Connected · live updates')
        page.locator('.file-item').filter(has_text='Notes.txt').click()
        editor=page.get_by_role('textbox',name='Document text')
        expect(editor).to_contain_text('Unicode: café')
        editor.click();page.keyboard.press('Control+End');page.keyboard.insert_text('Browser addition 😀\n')
        expect(page.locator('#save-status')).to_have_text('Unsaved changes')
        page.keyboard.press('Control+s');expect(page.locator('#save-status')).to_have_text('Saved')
        headers={'Authorization':'Bearer '+TOKEN}
        saved=context.request.get(BASE+'/api/v1/documents/sample-notes',headers=headers).json()
        raw=base64.b64decode(saved['content_base64'])
        assert b'\r\n' in raw and raw.endswith('Browser addition 😀\r\n'.encode()),repr(raw)
        # A clean open tab updates when Android saves a newer revision.
        body={'name':'Notes.txt','content_base64':base64.b64encode(b'Remote clean refresh\r\n').decode(),'base_revision':saved['revision'],'request_id':str(uuid.uuid4())}
        assert context.request.put(BASE+'/api/v1/documents/sample-notes',headers=headers,data=body).ok
        expect(editor).to_have_text('Remote clean refresh\n',timeout=7000)
        # A dirty open tab survives remote writes; its save wins and retains history.
        editor.click();page.keyboard.press('Control+End');page.keyboard.insert_text('Unsaved browser edit\n')
        body['content_base64']=base64.b64encode(b'Concurrent Android edit\n').decode();body['request_id']=str(uuid.uuid4())
        assert context.request.put(BASE+'/api/v1/documents/sample-notes',headers=headers,data=body).ok
        expect(page.locator('#remote-notice')).to_be_visible(timeout=7000)
        expect(editor).to_contain_text('Unsaved browser edit')
        page.locator('#save').click();expect(page.locator('#save-status')).to_have_text('Saved')
        page.locator('#history').click();page.locator('.history-row').last.click()
        expect(page.locator('#preview-text')).to_contain_text('Textpad desktop editor')
        page.locator('#restore-revision').click();page.locator('#modal-actions').get_by_role('button',name='Restore',exact=True).click()
        expect(editor).to_contain_text('Textpad desktop editor');expect(editor).not_to_contain_text('Unsaved browser edit')
        page.locator('#close-history').click()
        # New server document, independent tabs, undo/redo, search, and preferences.
        page.locator('#new-file').click();page.get_by_role('textbox',name='Filename',exact=True).fill('Desktop QA.txt')
        page.locator('#modal-actions').get_by_role('button',name='Create',exact=True).click()
        editor.click();page.keyboard.insert_text('Created on the server\nsecond line\n')
        page.locator('#undo').click();expect(editor).to_have_text('')
        page.locator('#redo').click();expect(editor).to_contain_text('Created on the server')
        page.keyboard.press('Control+s');expect(page.locator('#save-status')).to_have_text('Saved')
        # A newer server write while our save response is in flight must refresh
        # the newly clean buffer even when there are no further socket events.
        document_id=page.locator('.file-item').filter(has_text='Desktop QA.txt').get_attribute('data-id')
        held=[]
        def hold_save(route):
            if route.request.method!='PUT':
                route.continue_()
                return
            held.append((route,route.fetch()))
        save_url=BASE+'/editor/api/documents/'+document_id
        page.route(save_url,hold_save)
        editor.click();page.keyboard.press('Control+End');page.keyboard.insert_text('Pending save\n')
        page.locator('#save').click();expect(page.locator('#save-status')).to_have_text('Saving…')
        deadline=time.monotonic()+5
        while not held and time.monotonic()<deadline:
            page.wait_for_timeout(25)
        assert held
        body={'name':'Desktop QA.txt','content_base64':base64.b64encode(b'Newer remote revision during save\n').decode(),
              'base_revision':None,'request_id':str(uuid.uuid4())}
        assert context.request.put(BASE+'/api/v1/documents/'+document_id,headers=headers,data=body).ok
        held[0][0].fulfill(response=held[0][1]);page.unroute(save_url,hold_save)
        expect(editor).to_have_text('Newer remote revision during save\n',timeout=7000)
        editor.click();page.keyboard.press('Control+a');page.keyboard.insert_text('Created on the server\nsecond line\n')
        page.keyboard.press('Control+s');expect(page.locator('#save-status')).to_have_text('Saved')
        page.locator('#find').click();page.locator('input[name=search]').fill('second');page.locator('input[name=search]').press('Enter')
        expect(page.locator('.cm-searchMatch')).to_have_text('second')
        page.locator('.cm-search button[name=close]').click()
        page.locator('#settings').click();page.get_by_role('spinbutton',name='Font size').fill('18')
        page.get_by_label('Line numbers',exact=True).uncheck();page.locator('#modal-actions').get_by_role('button',name='Apply',exact=True).click()
        expect(page.locator('.cm-lineNumbers')).to_have_count(0)
        # Collapsible sidebar toggles visibility, updates ARIA state, and persists in localStorage.
        expect(page.locator('#file-sidebar')).to_be_visible()
        expect(page.locator('#sidebar-toggle')).to_have_attribute('aria-expanded','true')
        page.locator('#sidebar-toggle').click()
        expect(page.locator('#file-sidebar')).to_be_hidden()
        expect(page.locator('#sidebar-toggle')).to_have_attribute('aria-expanded','false')
        expect(page.locator('#sidebar-toggle')).to_have_attribute('aria-label','Show sidebar')
        assert page.evaluate("localStorage.getItem('textpad.editor.sidebarCollapsed')") == 'true'
        page.locator('#sidebar-toggle').click()
        expect(page.locator('#file-sidebar')).to_be_visible()
        expect(page.locator('#sidebar-toggle')).to_have_attribute('aria-expanded','true')
        expect(page.locator('#sidebar-toggle')).to_have_attribute('aria-label','Hide sidebar')
        # Go to bottom of file places cursor at the end of the document.
        editor.click();page.keyboard.press('Control+Home')
        expect(page.locator('#cursor-status')).to_contain_text('Ln 1, Col 1')
        page.locator('#go-bottom').click()
        expect(page.locator('#cursor-status')).to_contain_text('Ln 3, Col 1')
        page.locator('#encoding-status').click();page.locator('#modal-actions').get_by_role('button',name='Rename…',exact=True).click()
        page.get_by_role('textbox',name='Filename',exact=True).fill('Renamed 😀.txt');page.locator('#modal-actions').get_by_role('button',name='Apply',exact=True).click()
        page.locator('#save').click();expect(page.locator('#save-status')).to_have_text('Saved')
        expect(page.locator('.file-item').filter(has_text='Renamed 😀.txt')).to_be_visible()
        with page.expect_download() as event:page.locator('#download').click()
        download=event.value;assert download.suggested_filename=='Renamed 😀.txt'
        assert Path(download.path()).read_bytes()==b'Created on the server\nsecond line\n'
        # Discard confirmation keeps the tab when cancelled.
        editor.click();page.keyboard.press('Control+End');page.keyboard.insert_text('discard me')
        page.get_by_role('button',name='Close Renamed 😀.txt',exact=True).click()
        page.locator('#modal-actions').get_by_role('button',name='Cancel',exact=True).click();expect(editor).to_contain_text('discard me')
        page.get_by_role('button',name='Close Renamed 😀.txt',exact=True).click()
        page.locator('#modal-actions').get_by_role('button',name='Discard',exact=True).click()
        expect(page.get_by_role('tab',name='Renamed 😀.txt',exact=True)).to_have_count(0)
        expect(editor).to_contain_text('Textpad desktop editor')
        page.screenshot(path='/tmp/textpad-server-desktop-tested.png',full_page=True)
        page.locator('#sign-out').click();expect(page.locator('#credential')).to_be_visible()
        assert not errors,errors
        print('Chrome desktop QA passed: edit/save, Unicode, CRLF preservation, clean refresh, concurrent dirty buffer, history restore, new file, tabs, undo/redo, search, preferences, rename, download, discard/cancel, sign out; no JavaScript errors.')
        browser.close()

if __name__ == "__main__":
    main()
