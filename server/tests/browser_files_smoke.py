"""Full-page file management, saved bytes, and editor-session preservation."""
import base64
import uuid

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def main():
    with fixture() as base, sync_playwright() as p:
        chrome = p.chromium.launch(channel='chrome', headless=True, args=['--no-sandbox'])
        context = chrome.new_context(viewport={'width': 1440, 'height': 960})
        context.set_default_timeout(7000)
        headers = {'Authorization': 'Bearer ' + TOKEN}
        for folder_id, name, parent in [('home', 'Home', None), ('work', 'Work', None), ('projects', 'Projects', 'work')]:
            assert context.request.put(base + '/api/v1/folders/' + folder_id, headers=headers,
                data={'name': name, 'parent_id': parent}).ok
        raw = b'\xff\xfe' + 'Original café 😀\r\n'.encode('utf-16-le')
        assert context.request.put(base + '/api/v1/documents/plan', headers=headers, data={
            'name': 'Plan.txt', 'content_base64': base64.b64encode(raw).decode(),
            'base_revision': None, 'request_id': 'seed-plan'}).ok
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base + '/editor/')
        page.locator('#credential').fill(TOKEN)
        page.get_by_role('button', name='Sign in', exact=True).click()
        page.locator('.file-item[data-id="plan"]').click()
        editor = page.get_by_role('textbox', name='Document text')
        expect(editor).to_contain_text('Original café 😀')
        editor.fill('Original café 😀\nUnsaved browser note\n' + ''.join(f'Line {n:03d}\n' for n in range(150)))
        page.locator('.cm-scroller').evaluate('el => { el.scrollTop = 400; }')
        scroll_before = page.locator('.cm-scroller').evaluate('el => el.scrollTop')
        assert scroll_before > 100
        page.locator('#files-tab').click()
        view = page.locator('#folder-browser')
        expect(view).to_be_visible()
        expect(editor).to_be_hidden()
        expect(view.locator('.browser-entry-name')).to_have_text(['Home', 'Work', 'Notes.txt', 'Plan.txt', 'Tasks.txt'])

        def row(entry_id):
            return view.locator(f'.browser-row[data-entry-id="{entry_id}"]')

        def action(entry_id, label):
            row(entry_id).locator('.entry-actions').click()
            page.locator('#modal-body').get_by_role('button', name=label, exact=True).click()

        def root():
            view.locator('#browser-breadcrumbs').get_by_role('button', name='Top level', exact=True).click()

        def destination(folder_id=None):
            top = page.locator('#modal-body').get_by_role('button', name='Top level', exact=True)
            if top.is_enabled():
                top.click()
            if folder_id:
                page.locator(f'.destination-folders button[data-folder-id="{folder_id}"]').click()
            page.get_by_role('button', name='Move here', exact=True).click()

        def document(doc_id):
            return context.request.get(base + '/api/v1/documents/' + doc_id, headers=headers).json()

        row('work').locator('.browser-entry').click()
        expect(view.locator('.browser-entry-name')).to_have_text(['Projects'])
        row('projects').locator('.browser-entry').click()
        expect(view.locator('#browser-empty')).to_contain_text('This folder is empty')
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Work', 'Projects'])
        view.locator('#browser-up').click()
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Work'])
        root()
        view.locator('#browser-filter').fill('plan')
        expect(view.locator('.browser-entry-name')).to_have_text(['Plan.txt'])
        view.locator('#browser-filter').fill('')

        action('plan', 'Rename file')
        page.get_by_label('Filename', exact=True).fill('Alpha.txt')
        page.get_by_role('button', name='Rename', exact=True).click()
        expect(row('plan').locator('.browser-entry-name')).to_have_text('Alpha.txt')
        assert base64.b64decode(document('plan')['content_base64']) == raw
        renamed_revision = document('plan')['revision']
        row('plan').get_by_role('checkbox').check()
        row('sample-notes').get_by_role('checkbox').check()
        expect(view.locator('.browser-selection')).to_contain_text('2 files selected')
        view.locator('#browser-move-selected').click()
        destination('home')
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Home'])
        expect(view.locator('.browser-entry-name')).to_have_text(['Alpha.txt', 'Notes.txt'])
        assert document('plan')['folder_id'] == document('sample-notes')['folder_id'] == 'home'
        assert document('plan')['revision'] == renamed_revision
        assert base64.b64decode(document('plan')['content_base64']) == raw
        row('plan').locator('.browser-entry').click()
        expect(editor).to_be_visible()
        page.wait_for_function('y => Math.abs(document.querySelector(".cm-scroller").scrollTop - y) < 5', arg=scroll_before)
        page.keyboard.press('Control+Home')
        expect(editor).to_contain_text('Unsaved browser note')
        expect(page.locator('#save')).to_be_enabled()
        page.keyboard.press('Control+z')
        expect(editor).not_to_contain_text('Unsaved browser note')
        page.keyboard.press('Control+Shift+z')
        page.keyboard.press('Control+Home')
        expect(editor).to_contain_text('Unsaved browser note')
        page.locator('#files-tab').click()

        # A rejected group move retains both the files and the selection.
        view.locator('#browser-select-all').check()
        page.route('**/editor/api/library/move', lambda route: route.fulfill(status=404, json={'error': 'A selected file no longer exists.'}))
        view.locator('#browser-move-selected').click()
        destination()
        expect(page.locator('#notice-text')).to_contain_text('A selected file no longer exists')
        expect(view.locator('.browser-selection')).to_contain_text('2 files selected')
        assert document('plan')['folder_id'] == document('sample-notes')['folder_id'] == 'home'
        page.unroute('**/editor/api/library/move')
        view.locator('#browser-clear-selection').click()

        view.locator('#browser-new-folder').click()
        page.get_by_label('Folder name', exact=True).fill('Drafts')
        page.get_by_role('button', name='Create', exact=True).click()
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Home', 'Drafts'])
        drafts = view.locator('#browser-breadcrumbs button').last.get_attribute('data-folder-id')
        view.locator('#browser-new-file').click()
        page.get_by_label('Filename', exact=True).fill('Draft.txt')
        page.get_by_role('button', name='Create', exact=True).click()
        editor.fill('Created in the full-page browser\n')
        page.locator('#save').click()
        expect(page.locator('#save')).to_be_disabled()
        page.locator('#files-tab').click()
        expect(view.locator('.browser-entry-name')).to_have_text(['Draft.txt'])
        draft_id = view.locator('.browser-row[data-kind="file"]').get_attribute('data-entry-id')
        assert document(draft_id)['folder_id'] == drafts
        view.locator('#browser-folder-actions').click()
        page.get_by_role('button', name='Delete folder', exact=True).click()
        expect(page.locator('#notice-text')).to_contain_text('files and subfolders')
        action(draft_id, 'Move to folder…')
        destination()
        row('home').locator('.browser-entry').click()
        row(drafts).locator('.browser-entry').click()
        view.locator('#browser-folder-actions').click()
        page.get_by_role('button', name='Delete folder', exact=True).click()
        page.locator('#modal-actions').get_by_role('button', name='Delete folder', exact=True).click()
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Home'])

        view.locator('#browser-folder-actions').click()
        page.get_by_role('button', name='Rename folder', exact=True).click()
        page.get_by_label('Folder name', exact=True).fill('Archive')
        page.get_by_role('button', name='Rename', exact=True).click()
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Archive'])
        root()
        row('work').locator('.browser-entry').click()
        action('projects', 'Move folder')
        expect(page.locator('.destination-folders button[data-folder-id="projects"]')).to_have_count(0)
        destination('home')
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Archive', 'Projects'])
        root()
        action('work', 'Delete folder')
        page.locator('#modal-actions').get_by_role('button', name='Delete folder', exact=True).click()
        expect(row('work')).to_have_count(0)

        # A clean open file can refresh without taking the user out of Files.
        row(draft_id).locator('.browser-entry').click()
        expect(editor).to_contain_text('Created in the full-page browser')
        page.locator('#files-tab').click()
        current = document(draft_id)
        assert context.request.put(base + '/api/v1/documents/' + draft_id, headers=headers, data={
            'name': current['name'], 'content_base64': base64.b64encode(b'Remote refresh while browsing\n').decode(),
            'base_revision': current['revision'], 'request_id': str(uuid.uuid4())}).ok
        expect(page.locator('#revision-status')).to_have_text('Revision ' + document(draft_id)['revision'], timeout=7000)
        expect(view).to_be_visible()
        view.locator('#browser-editor').click()
        expect(editor).to_contain_text('Remote refresh while browsing')
        page.locator('#files-tab').click()
        row('home').locator('.browser-entry').click()
        page.screenshot(path='/tmp/textpad-full-files-desktop.png')
        page.reload()
        expect(view).to_be_visible()
        expect(view.locator('#browser-breadcrumbs button')).to_have_text(['Top level', 'Archive'])
        expect(row('plan')).to_be_visible()
        page.set_viewport_size({'width': 390, 'height': 640})
        if page.locator('#sidebar-toggle').get_attribute('aria-expanded') == 'true':
            page.locator('#sidebar-close').click()
        expect(view.locator('#browser-new-folder')).to_be_in_viewport()
        expect(view.locator('#browser-new-file')).to_be_in_viewport()
        assert view.evaluate('el => el.scrollWidth <= el.clientWidth')
        row('plan').get_by_role('checkbox').check()
        view.locator('#browser-move-selected').click()
        expect(page.get_by_role('button', name='Move here', exact=True)).to_be_in_viewport()
        page.get_by_role('button', name='Cancel', exact=True).click()
        page.screenshot(path='/tmp/textpad-full-files-mobile.png')
        page.evaluate('navigator.serviceWorker.ready')
        context.set_offline(True)
        page.reload()
        expect(view).to_be_visible()
        expect(row('plan')).to_be_visible()
        expect(view.locator('#browser-new-folder')).to_be_disabled()
        row('plan').locator('.browser-entry').click()
        expect(editor).to_contain_text('Original café 😀')
        assert not errors, errors
        context.close()
        chrome.close()
    print('Full-page Files passed: breadcrumbs, sorting, filtering, atomic moves, exact-byte rename, unsaved buffers and undo, folder CRUD, remote refresh, remembered view, offline browsing, and mobile controls')


if __name__ == '__main__':
    main()
