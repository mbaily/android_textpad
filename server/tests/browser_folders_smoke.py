"""Sidebar hierarchy, folder management, file placement, and offline preservation."""
import base64
from playwright.sync_api import expect, sync_playwright
from browser_smoke import TOKEN, fixture


def main():
    with fixture() as base, sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        headers = {"Authorization": "Bearer " + TOKEN}
        for folder_id, name, parent in [('home', 'Home', None), ('work', 'Work', None),
                ('zebra', 'zebra', None), ('projects', 'Projects', 'work'), ('deep', 'Deep', 'projects')]:
            assert context.request.put(base + '/api/v1/folders/' + folder_id, headers=headers,
                                       data=dict(name=name, parent_id=parent)).ok
        raw = b'\xff\xfe' + 'Original café 😀\r\n'.encode('utf-16-le')
        for doc_id, name, content, folder_id in [('plan', 'Plan.txt', raw, 'projects'),
                                                ('deep-note', 'Deep note.txt', b'Deep note\n', 'deep')]:
            assert context.request.put(base + '/api/v1/documents/' + doc_id, headers=headers,
                data=dict(name=name, content_base64=base64.b64encode(content).decode(),
                          base_revision=None, request_id='seed-' + doc_id, folder_id=folder_id)).ok
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base + '/editor/')
        page.locator('#credential').fill(TOKEN)
        page.get_by_role('button', name='Sign in', exact=True).click()
        expect(page.locator('#new-folder')).to_be_enabled()

        def folder(folder_id):
            return page.locator(f'.folder-item[data-folder-id="{folder_id}"]')

        def folder_action(folder_id, action):
            page.locator(f'.library-entry[data-kind="folder"][data-entry-id="{folder_id}"] .entry-actions').click()
            page.locator('#modal-body').get_by_role('button', name=action, exact=True).click()

        def file_action(doc_id, action):
            page.locator(f'.library-entry[data-kind="file"][data-entry-id="{doc_id}"] .entry-actions').click()
            page.locator('#modal-body').get_by_role('button', name=action, exact=True).click()

        def document(doc_id):
            return context.request.get(base + '/api/v1/documents/' + doc_id, headers=headers).json()

        def choose_destination(folder_id=None):
            page.locator('#modal-body').get_by_role('button', name='Top level', exact=True).click()
            if folder_id:
                page.locator(f'.destination-folders button[data-folder-id="{folder_id}"]').click()
            page.get_by_role('button', name='Move here', exact=True).click()

        expect(page.locator('.folder-item .file-name')).to_have_text(['Home', 'Work', 'zebra'])
        expect(page.locator('.file-item .file-name')).to_have_text(['Notes.txt', 'Tasks.txt'])
        folder('work').click()
        folder('projects').click()
        page.locator('.file-item[data-id="plan"]').click()
        editor = page.get_by_role('textbox', name='Document text')
        expect(editor).to_contain_text('Original café 😀')
        editor.fill('Original café 😀\nUnsaved note\n')
        expect(page.locator('#save')).to_be_enabled()
        file_action('plan', 'Move to folder…')
        choose_destination('home')
        expect(page.locator('#save')).to_be_enabled()
        expect(editor).to_contain_text('Unsaved note')
        moved = document('plan')
        assert moved['folder_id'] == 'home'
        assert base64.b64decode(moved['content_base64']) == raw
        assert len(context.request.get(base + '/api/v1/documents/plan/revisions', headers=headers).json()['documents']) == 1
        page.locator('#save').click()
        expect(page.locator('#save')).to_be_disabled()
        saved = document('plan')
        assert saved['folder_id'] == 'home'
        assert base64.b64decode(saved['content_base64']).startswith(b'\xff\xfe')
        assert 'Unsaved note\r\n' in base64.b64decode(saved['content_base64'])[2:].decode('utf-16-le')

        folder_action('home', 'Rename folder')
        page.get_by_label('Folder name', exact=True).fill('Archive')
        page.get_by_role('button', name='Rename', exact=True).click()
        expect(folder('home')).to_contain_text('Archive')
        assert document('plan')['revision'] == saved['revision']
        folder_action('work', 'Delete folder')
        expect(page.locator('#notice-text')).to_contain_text('files and subfolders')
        assert context.request.get(base + '/api/v1/folders/work', headers=headers).ok
        folder_action('projects', 'Move folder')
        expect(page.locator('.destination-folders button[data-folder-id="projects"]')).to_have_count(0)
        expect(page.locator('.destination-folders button[data-folder-id="deep"]')).to_have_count(0)
        choose_destination('home')
        expect(page.locator('.library-entry[data-entry-id="projects"]')).to_have_attribute('data-depth', '1')
        folder_action('work', 'Delete folder')
        page.get_by_role('button', name='Cancel', exact=True).click()
        expect(folder('work')).to_be_visible()
        folder_action('work', 'Delete folder')
        page.get_by_role('button', name='Delete folder', exact=True).click()
        expect(folder('work')).to_have_count(0)

        page.locator('#library-root').click()
        page.locator('#new-folder').click()
        page.get_by_label('Folder name', exact=True).fill('Inbox')
        page.get_by_label('Folder name', exact=True).press('Enter')
        expect(page.locator('.folder-item').filter(has_text='Inbox')).to_be_visible()
        inbox = page.locator('.folder-item').filter(has_text='Inbox').get_attribute('data-folder-id')
        folder_action(inbox, 'New subfolder')
        page.get_by_label('Folder name', exact=True).fill('Drafts')
        page.get_by_role('button', name='Create', exact=True).click()
        expect(page.locator('.folder-item').filter(has_text='Drafts')).to_be_visible()
        drafts = page.locator('.folder-item').filter(has_text='Drafts').get_attribute('data-folder-id')
        folder_action(drafts, 'New file')
        page.get_by_label('Filename', exact=True).fill('New note.txt')
        page.get_by_role('button', name='Create', exact=True).click()
        editor.fill('Created inside a folder\n')
        expect(page.locator('.file-item').filter(has_text='New note.txt')).to_be_visible()
        new_id = page.locator('.file-item').filter(has_text='New note.txt').get_attribute('data-id')
        page.locator('#save').click()
        expect(page.locator('#save')).to_be_disabled()
        assert document(new_id)['folder_id'] == drafts
        assert len(context.request.get(base + '/api/v1/documents/' + new_id + '/revisions', headers=headers).json()['documents']) == 1
        file_action(new_id, 'Move to folder…')
        choose_destination()
        assert document(new_id)['folder_id'] is None
        folder_action(drafts, 'Delete folder')
        page.get_by_role('button', name='Delete folder', exact=True).click()
        expect(folder(drafts)).to_have_count(0)

        # Duplicate sibling names are rejected without a duplicate row.
        page.locator('#library-root').click()
        page.locator('#new-folder').click()
        page.get_by_label('Folder name', exact=True).fill('ARCHIVE')
        page.get_by_role('button', name='Create', exact=True).click()
        expect(page.locator('#notice-text')).to_contain_text('already exists')
        expect(folder('home')).to_have_count(1)

        if folder('home').get_attribute('aria-expanded') == 'true':
            folder('home').click()
        page.reload()
        expect(folder('home')).to_have_attribute('aria-expanded', 'false')
        page.locator('#file-filter').fill('Deep note')
        expect(page.locator('.folder-item .file-name')).to_have_text(['Archive', 'Projects', 'Deep'])
        page.locator('.file-item[data-id="deep-note"]').click()
        expect(editor).to_contain_text('Deep note')
        page.locator('#file-filter').fill('')
        folder_action('home', 'New file')
        page.get_by_label('Filename', exact=True).fill('Offline.txt')
        page.get_by_role('button', name='Create', exact=True).click()
        editor.fill('Offline folder file\n')
        page.wait_for_function('() => !!navigator.serviceWorker.controller')
        context.set_offline(True)
        page.locator('#save').click()
        expect(page.locator('#notice-text')).to_contain_text('Saved offline')
        offline_id = page.locator('.file-item').filter(has_text='Offline.txt').get_attribute('data-id')
        page.reload()
        expect(folder('home')).to_be_visible()
        expect(page.locator('#new-folder')).to_be_disabled()
        expect(page.locator('.file-item[data-id="' + offline_id + '"]')).to_be_visible()
        page.locator('.file-item[data-id="' + offline_id + '"]').click()
        expect(editor).to_contain_text('Offline folder file')
        context.set_offline(False)
        page.wait_for_function("() => !document.getElementById('connection-label').textContent.includes('Offline')")
        page.wait_for_function("id => new Promise(resolve => { const r = indexedDB.open('textpad.editor.offline', 2); r.onsuccess = () => { const db = r.result; const q = db.transaction('outbox').objectStore('outbox').get(id); q.onsuccess = () => { db.close(); resolve(!q.result); }; }; })", arg=offline_id)
        assert document(offline_id)['folder_id'] == 'home'
        assert base64.b64decode(document(offline_id)['content_base64']) == b'Offline folder file\n'
        assert context.request.put(base + '/api/v1/folders/remote', headers=headers, data={'name': 'Remote'}).ok
        expect(folder('remote')).to_be_visible(timeout=7000)
        # A remote move updates cached placement even without a new text revision.
        original_revision = document('deep-note')['revision']
        assert context.request.put(base + '/api/v1/documents/deep-note/folder', headers=headers, data={'folder_id': 'remote'}).ok
        page.locator('#file-filter').fill('Deep note')
        expect(page.locator('.folder-item .file-name')).to_have_text(['Remote'], timeout=7000)
        page.locator('.file-item[data-id="deep-note"]').click()
        expect(editor).to_contain_text('Deep note')
        context.set_offline(True)
        page.reload()
        page.locator('#file-filter').fill('Deep note')
        expect(page.locator('.folder-item .file-name')).to_have_text(['Remote'])
        page.locator('.file-item[data-id="deep-note"]').click()
        expect(editor).to_contain_text('Deep note')
        context.set_offline(False)
        expect(page.locator('#new-folder')).to_be_enabled()
        assert document('deep-note')['revision'] == original_revision
        page.locator('#file-filter').fill('')
        expect(page.locator(f'.library-entry[data-entry-id="{offline_id}"]')).to_have_attribute('data-depth', '1')
        page.screenshot(path='/tmp/textpad-folders-sidebar-desktop.png')
        page.set_viewport_size({'width': 390, 'height': 640})
        expect(page.locator('#new-folder')).to_be_in_viewport()
        expect(page.locator('#new-file')).to_be_in_viewport()
        bounds = page.locator('#file-sidebar').bounding_box()
        assert bounds['x'] >= 0 and bounds['x'] + bounds['width'] <= 390
        folder_action('home', 'Move folder')
        expect(page.locator('#modal')).to_be_in_viewport()
        expect(page.get_by_role('button', name='Move here', exact=True)).to_be_in_viewport()
        page.get_by_role('button', name='Cancel', exact=True).click()
        page.screenshot(path='/tmp/textpad-folders-sidebar-mobile.png')
        assert not errors, errors
        browser.close()
    print('Folder sidebar passed: sorted nesting, create/rename/delete, legal moves, dirty-buffer and history preservation, filtering, remembered expansion, offline creation/reload/retry, live changes, and mobile controls')


if __name__ == '__main__':
    main()
