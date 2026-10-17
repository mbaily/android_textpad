"""Recent-file entry screen checks against an isolated server (requires Playwright)."""
import base64
import uuid

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def sign_in(page, base):
    page.goto(base + "/editor/")
    page.locator("#credential").fill(TOKEN)
    page.get_by_role("button", name="Sign in", exact=True).click()
    expect(page.locator("#welcome")).to_be_visible()


def main():
    with fixture() as base, sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 960})
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        sign_in(page, base)
        rows = page.locator("#recent-file-list .recent-file")
        names = rows.locator(".recent-file-name")
        expect(names).to_have_text(["Tasks.txt", "Notes.txt"])
        expect(page.locator("#welcome .welcome-mark")).to_have_count(0)
        editor = page.get_by_role("textbox", name="Document text")

        rows.filter(has_text="Notes.txt").click()
        expect(editor).to_contain_text("Unicode: café")
        expect(page.locator("#welcome")).to_be_hidden()
        page.get_by_role("button", name="Close Notes.txt", exact=True).click()
        expect(names).to_have_text(["Notes.txt", "Tasks.txt"])
        rows.filter(has_text="Tasks.txt").click()
        expect(editor).to_contain_text("First item")
        page.get_by_role("button", name="Close Tasks.txt", exact=True).click()
        expect(names).to_have_text(["Tasks.txt", "Notes.txt"])
        page.reload()
        expect(page.locator("#welcome")).to_be_visible()
        expect(names).to_have_text(["Tasks.txt", "Notes.txt"])

        # Recently opened order survives reload, independently of server modification order.
        rows.filter(has_text="Notes.txt").click()
        page.get_by_role("button", name="Close Notes.txt", exact=True).click()
        page.reload()
        expect(names).to_have_text(["Notes.txt", "Tasks.txt"])
        headers = {"Authorization": "Bearer " + TOKEN}
        response = context.request.put(base + "/api/v1/documents/sample-log", headers=headers, data={
            "name": "Renamed tasks.txt", "content_base64": base64.b64encode(b"First item\nSecond item\n").decode(),
            "base_revision": None, "request_id": str(uuid.uuid4()),
        })
        assert response.ok
        expect(names).to_have_text(["Notes.txt", "Renamed tasks.txt"])

        page.set_viewport_size({"width": 390, "height": 844})
        page.locator("#sidebar-close").click()
        assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
        expect(rows.first).to_be_in_viewport()
        rows.filter(has_text="Notes.txt").click()
        expect(editor).to_contain_text("Unicode: café")
        page.get_by_role("button", name="Close Notes.txt", exact=True).click()
        page.screenshot(path="/tmp/textpad-recent-files-web.png", full_page=True)

        # Existing cached files can also be reopened from the entry screen offline.
        page.evaluate("navigator.serviceWorker.ready")
        context.set_offline(True)
        page.reload()
        expect(names).to_have_text(["Notes.txt", "Renamed tasks.txt"])
        rows.filter(has_text="Notes.txt").click()
        expect(editor).to_contain_text("Unicode: café")
        assert not errors, errors
        context.close()

        # A server with no files still offers a way to start writing.
        empty_context = browser.new_context()
        empty_page = empty_context.new_page()
        empty_page.on("pageerror", lambda error: errors.append(str(error)))
        empty_page.route("**/editor/api/documents", lambda route: route.fulfill(json={"documents": []}))
        sign_in(empty_page, base)
        expect(empty_page.locator("#recent-files-empty")).to_have_text("No recent files yet.")
        empty_page.locator("#welcome-new").click()
        empty_page.get_by_role("textbox", name="Filename", exact=True).fill("Fresh note.txt")
        empty_page.locator("#modal-actions").get_by_role("button", name="Create", exact=True).click()
        expect(empty_page.get_by_role("tab").filter(has_text="Fresh note.txt")).to_be_visible()
        empty_page.get_by_role("button", name="Close Fresh note.txt", exact=True).click()
        empty_page.locator("#modal-actions").get_by_role("button", name="Discard", exact=True).click()
        expect(empty_page.locator("#recent-files-empty")).to_have_text("No recent files yet.")
        empty_page.locator("#welcome-new").click()
        empty_page.get_by_role("textbox", name="Filename", exact=True).fill("Saved note.txt")
        empty_page.locator("#modal-actions").get_by_role("button", name="Create", exact=True).click()
        empty_page.unroute("**/editor/api/documents")
        empty_page.get_by_role("textbox", name="Document text").fill("New saved note")
        empty_page.locator("#save").click()
        expect(empty_page.locator("#save")).to_be_disabled()
        empty_page.get_by_role("button", name="Close Saved note.txt", exact=True).click()
        expect(empty_page.locator(".recent-file-name").first).to_have_text("Saved note.txt")
        empty_page.reload()
        expect(empty_page.locator(".recent-file-name").first).to_have_text("Saved note.txt")
        assert not errors, errors
        browser.close()
    print("Browser recent-file checks passed")


if __name__ == "__main__":
    main()
