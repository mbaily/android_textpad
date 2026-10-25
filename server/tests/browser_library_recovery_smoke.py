"""Library loading must survive blocked sync work and retained offline saves."""
from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def sign_in(page, base):
    page.goto(base + '/editor/')
    page.locator('#credential').fill(TOKEN)
    page.get_by_role('button', name='Sign in', exact=True).click()
    expect(page.locator('#welcome')).to_be_visible()


def main():
    with fixture() as base, sync_playwright() as p:
        browser = p.chromium.launch(channel='chrome', headless=True, args=['--no-sandbox'])
        errors = []

        # A suspended tab can retain the origin's outbox lock indefinitely.
        context = browser.new_context()
        holder = context.new_page()
        holder.goto(base + '/editor/login')
        holder.evaluate("""() => new Promise(resolve => {
            navigator.locks.request('textpad.editor.outbox', () => new Promise(release => {
                window.releaseOutbox = release;
                resolve();
            }));
        })""")
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        try:
            sign_in(page, base)
            expect(page.locator('.file-item')).to_have_count(2)
            page.locator('.file-item[data-id="sample-notes"]').click()
            editor = page.get_by_role('textbox', name='Document text')
            expect(editor).to_contain_text('Unicode: café')
            editor.fill('Unsaved buffer survives sidebar refresh\n')
            page.locator('#refresh').click()
            expect(page.locator('.file-item')).to_have_count(2)
            expect(editor).to_contain_text('Unsaved buffer survives')
            expect(page.locator('#save')).to_be_enabled()
            page.locator('#new-folder').click()
            page.get_by_label('Folder name', exact=True).fill('Move while sync waits')
            page.get_by_role('button', name='Create', exact=True).click()
            expect(page.locator('.folder-item')).to_be_visible()
            folder_id = page.locator('.folder-item').get_attribute('data-folder-id')
            page.locator('.library-entry[data-entry-id="sample-notes"] .entry-actions').click()
            page.get_by_role('button', name='Move to folder…', exact=True).click()
            page.locator(f'.destination-folders button[data-folder-id="{folder_id}"]').click()
            page.get_by_role('button', name='Move here', exact=True).click()
            expect(page.locator('.library-entry[data-entry-id="sample-notes"]')).to_have_attribute('data-depth', '1')
            expect(editor).to_contain_text('Unsaved buffer survives')
            headers = {'Authorization': 'Bearer ' + TOKEN}
            assert context.request.put(base + '/api/v1/documents/sample-notes/folder', headers=headers, data={'folder_id': None}).ok
            assert context.request.delete(base + '/api/v1/folders/' + folder_id, headers=headers).ok
        finally:
            holder.evaluate('window.releaseOutbox()')
            context.close()

        context = browser.new_context()
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        sign_in(page, base)
        expect(page.locator('.file-item')).to_have_count(2)
        def reject_save(route):
            if route.request.method == 'PUT':
                route.fulfill(status=409, json={'error': 'Pending save needs attention.'})
            else:
                route.continue_()
        page.route('**/editor/api/documents/sample-notes', reject_save)
        # Keep a saved offline edit that the server cannot currently accept.
        page.evaluate("""async () => {
            const db = await new Promise((resolve, reject) => {
                const request = indexedDB.open('textpad.editor.offline', 2);
                request.onsuccess = () => resolve(request.result);
                request.onerror = () => reject(request.error);
            });
            await new Promise((resolve, reject) => {
                const tx = db.transaction(['files', 'documents', 'outbox'], 'readwrite');
                const request = tx.objectStore('files').get('sample-notes');
                request.onsuccess = () => {
                    const metadata = { ...request.result, text: 'Retained offline edit\\n', bom: false };
                    const body = { name: metadata.name, text: metadata.text, encoding: 'utf-8', bom: false,
                        base_revision: metadata.revision, request_id: 'retained-offline-request' };
                    tx.objectStore('outbox').put({ id: metadata.id, body, docMeta: metadata, fileSummary: request.result });
                    tx.objectStore('documents').put(metadata);
                };
                tx.oncomplete = resolve;
                tx.onerror = () => reject(tx.error);
            });
            db.close();
        }""")
        page.reload()
        expect(page.locator('.file-item')).to_have_count(2)
        expect(page.locator('#notice-text')).to_contain_text('Pending save needs attention.')
        page.locator('.file-item[data-id="sample-notes"]').click()
        expect(page.get_by_role('textbox', name='Document text')).to_contain_text('Retained offline edit')
        expect(page.locator('#save-status')).to_have_text('Saved offline')
        assert page.evaluate("""() => new Promise(resolve => {
            const request = indexedDB.open('textpad.editor.offline', 2);
            request.onsuccess = () => {
                const db = request.result;
                const read = db.transaction('outbox').objectStore('outbox').get('sample-notes');
                read.onsuccess = () => { db.close(); resolve(Boolean(read.result)); };
            };
        })""")
        page.unroute('**/editor/api/documents/sample-notes', reject_save)
        page.locator('#refresh').click()
        expect(page.locator('#save-status')).to_have_text('Saved', timeout=7000)
        expect(page.get_by_role('textbox', name='Document text')).to_contain_text('Retained offline edit')
        context.close()

        # A new offline-only document also remains visible until its save succeeds.
        context = browser.new_context()
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        sign_in(page, base)
        expect(page.locator('.file-item')).to_have_count(2)
        page.locator('#new-file').click()
        page.get_by_label('Filename', exact=True).fill('Offline-only.txt')
        page.get_by_role('button', name='Create', exact=True).click()
        page.get_by_role('textbox', name='Document text').fill('New offline document\n')
        context.set_offline(True)
        page.locator('#save').click()
        expect(page.locator('#save-status')).to_have_text('Saved offline')
        page.route('**/editor/api/documents/*', reject_save)
        context.set_offline(False)
        page.reload()
        expect(page.locator('.file-item')).to_have_count(3)
        page.locator('.file-item').filter(has_text='Offline-only.txt').click()
        expect(page.get_by_role('textbox', name='Document text')).to_contain_text('New offline document')
        expect(page.locator('#save-status')).to_have_text('Saved offline')
        context.close()

        # Settings failures cannot prevent a valid server catalog from loading.
        context = browser.new_context()
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.route('**/editor/api/errands', lambda route: route.fulfill(status=503, json={'error': 'Settings unavailable.'}))
        sign_in(page, base)
        expect(page.locator('.file-item')).to_have_count(2)
        context.close()

        # A failed or still-loading catalog is never described as an empty server.
        context = browser.new_context()
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        held = []
        hold_catalog = True
        def catalog_request(route):
            if hold_catalog:
                held.append(route)
            else:
                route.fulfill(status=503, json={'error': 'Library unavailable.'})
        page.route('**/editor/api/documents', catalog_request)
        sign_in(page, base)
        expect(page.locator('#library-empty')).to_have_text('Loading files and folders…')
        expect(page.locator('#recent-files-empty')).to_have_text('Loading recent files…')
        hold_catalog = False
        for route in list(held):
            route.fulfill(status=503, json={'error': 'Library unavailable.'})
        expect(page.locator('#library-empty')).to_contain_text('could not be loaded')
        expect(page.locator('#recent-files-empty')).to_contain_text('could not be loaded')
        page.unroute('**/editor/api/documents')
        page.locator('#refresh').click()
        expect(page.locator('.file-item')).to_have_count(2)
        context.close()
        assert not errors, errors
        browser.close()
    print('Library recovery passed: held sync lock, rejected pending save, preserved offline edits, settings failure, loading/error states, and retry')


if __name__ == '__main__':
    main()
