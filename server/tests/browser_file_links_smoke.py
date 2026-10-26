"""Direct file links, sign-in, tab URLs, and offline reloads (requires Playwright)."""
import re
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def main():
    with fixture() as base, sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 960})
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        editor = page.get_by_role("textbox", name="Document text")
        notes_url = base + "/editor/?file=sample-notes"
        tasks_url = base + "/editor/?file=sample-log"

        # A link without the trailing slash must survive sign-in and a bad password.
        page.goto(base + "/editor?file=sample-notes")
        expect(page).to_have_url(base + "/editor/login?file=sample-notes")
        page.locator("#credential").fill("wrong-password")
        page.get_by_role("button", name="Sign in", exact=True).click()
        expect(page.locator(".form-error")).to_be_visible()
        page.locator("#credential").fill(TOKEN)
        page.get_by_role("button", name="Sign in", exact=True).click()
        expect(editor).to_contain_text("Unicode: café")
        expect(page).to_have_url(notes_url)
        page.reload()
        expect(editor).to_contain_text("Unicode: café")

        # Opening, switching and closing tabs keeps the address in sync.
        page.locator(".file-item").filter(has_text="Tasks.txt").click()
        expect(page).to_have_url(tasks_url)
        expect(editor).to_contain_text("First item")
        page.get_by_role("tab", name="Notes.txt", exact=True).click()
        expect(page).to_have_url(notes_url)
        page.get_by_role("button", name="Close Tasks.txt", exact=True).click()
        expect(page).to_have_url(notes_url)
        page.get_by_role("button", name="Close Notes.txt", exact=True).click()
        expect(page).to_have_url(base + "/editor/")
        expect(page.locator("#welcome")).to_be_visible()

        # A direct link overrides the persisted Files view without waiting for the catalog.
        page.locator("#files-tab").click()
        held_catalog = []
        page.route("**/editor/api/documents", lambda route: held_catalog.append(route))
        page.goto(tasks_url)
        expect(editor).to_contain_text("First item")
        expect(page.locator("#folder-browser")).to_be_hidden()
        for route in held_catalog:
            route.continue_()
        page.unroute("**/editor/api/documents")
        page.locator("#files-tab").click()
        expect(page).to_have_url(base + "/editor/")
        page.get_by_role("tab", name="Tasks.txt", exact=True).click()
        expect(page).to_have_url(tasks_url)

        # Unsaved documents have no link until their first server save.
        page.locator("#new-file").click()
        page.get_by_role("textbox", name="Filename", exact=True).fill("Linked note.txt")
        page.locator("#modal-actions").get_by_role("button", name="Create", exact=True).click()
        expect(page).to_have_url(base + "/editor/")
        editor.fill("Saved through a file link")
        page.locator("#save").click()
        expect(page.locator("#save")).to_be_disabled()
        expect(page).to_have_url(re.compile(r"/editor/\?file=[A-Za-z0-9_-]+$"))
        new_url = page.url
        doc_id = parse_qs(urlsplit(new_url).query)["file"][0]

        # Rename and move retain the same identity and URL.
        headers = {"X-CSRF-Token": page.locator('meta[name="csrf-token"]').get_attribute("content")}
        metadata = context.request.get(base + "/editor/api/documents/" + doc_id).json()
        response = context.request.put(base + "/editor/api/documents/" + doc_id + "/name", headers=headers,
                                       data={"name": "Renamed note.txt", "base_revision": metadata["revision"],
                                             "request_id": "link-rename"})
        assert response.ok, response.text()
        response = context.request.put(base + "/editor/api/folders/link-folder", headers=headers,
                                       data={"name": "Linked folder", "parent_id": None})
        assert response.ok, response.text()
        response = context.request.put(base + "/editor/api/documents/" + doc_id + "/folder", headers=headers,
                                       data={"folder_id": "link-folder"})
        assert response.ok, response.text()
        page.goto(new_url)
        expect(editor).to_have_text("Saved through a file link")
        expect(page.get_by_role("tab", name="Renamed note.txt", exact=True)).to_have_attribute("aria-selected", "true")
        expect(page).to_have_url(new_url)

        # Service worker shell plus cached bytes support the same link offline.
        page.evaluate("navigator.serviceWorker.ready")
        context.set_offline(True)
        page.reload()
        expect(editor).to_have_text("Saved through a file link")
        expect(page).to_have_url(new_url)
        context.set_offline(False)

        # Broken and malformed links report an error and leave the library usable.
        page.goto(base + "/editor/?file=missing-file")
        expect(page.locator("#notice")).to_have_class(re.compile(r"\berror\b"))
        expect(page.locator("#notice-text")).to_contain_text("not found")
        page.locator(".file-item").filter(has_text="Tasks.txt").click()
        expect(editor).to_contain_text("First item")
        page.goto(base + "/editor/?file=..%2Fsample-notes")
        expect(page.locator("#notice-text")).to_contain_text("invalid file ID")
        expect(page.locator("#welcome")).to_be_visible()
        assert not errors, errors
        browser.close()
    print("Browser file-link checks passed")


if __name__ == "__main__":
    main()
