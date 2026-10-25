"""Confirmed deletion from the read-only line composer, using an isolated server."""
import argparse
import base64

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def main(browser_name="chromium"):
    original = "Before\r\n  remove 😀\n  After\r\n"
    with fixture() as base, sync_playwright() as p:
        engine = getattr(p, browser_name)
        browser = engine.launch(**({"channel": "chrome", "args": ["--no-sandbox"]} if browser_name == "chromium" else {}))
        context = browser.new_context(viewport={"width": 1280, "height": 900})
        headers = {"Authorization": "Bearer " + TOKEN}
        response = context.request.put(base + "/api/v1/documents/deletion", headers=headers, data={
            "name": "deletion.txt", "content_base64": base64.b64encode(original.encode()).decode(),
            "base_revision": None, "request_id": "seed-deletion",
        })
        assert response.ok
        revision = response.json()["revision"]
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/editor/")
        page.locator("#credential").fill(TOKEN)
        page.get_by_role("button", name="Sign in", exact=True).click()
        page.locator(".file-item").filter(has_text="deletion.txt").click()
        editor = page.get_by_role("textbox", name="Document text")
        lines = editor.locator(".cm-line")
        expect(lines).to_have_text(["Before", "  remove 😀", "  After", ""])
        editor.press("Control+Home"); editor.press("ArrowDown")
        page.locator("#toggle-readonly").click()
        draft = page.locator("#line-input")
        draft.fill("draft 😀")
        delete = page.get_by_role("button", name="Delete current line", exact=True)
        expect(delete).to_be_enabled()
        assert delete.evaluate("el => el.nextElementSibling.id") == "line-indent-less"
        delete.click()
        dialog = page.get_by_role("dialog")
        expect(dialog).to_be_visible()
        expect(dialog).to_contain_text("Delete line 2?")
        expect(lines).to_have_text(["Before", "  remove 😀", "  After", ""])
        dialog.get_by_role("button", name="Cancel", exact=True).click()
        expect(dialog).not_to_be_visible()
        expect(page.locator("#save")).to_be_disabled()
        expect(draft).to_have_value("draft 😀")

        # A remote refresh while confirmation is open must invalidate its target.
        delete.click()
        changed = original.replace("remove 😀", "replacement")
        response = context.request.put(base + "/api/v1/documents/deletion", headers=headers, data={
            "name": "deletion.txt", "content_base64": base64.b64encode(changed.encode()).decode(),
            "base_revision": revision, "request_id": "refresh-during-confirmation",
        })
        assert response.ok
        expect(lines).to_have_text(["Before", "  replacement", "  After", ""], timeout=10000)
        dialog.get_by_role("button", name="Delete", exact=True).click()
        expect(page.locator("#notice-text")).to_have_text("The document changed. Please choose the line again.")
        expect(lines).to_have_text(["Before", "  replacement", "  After", ""])
        expect(page.locator("#save")).to_be_disabled()
        editor.press("Control+Home"); editor.press("ArrowDown")
        delete.click()
        dialog.get_by_role("button", name="Delete", exact=True).click()
        expect(lines).to_have_text(["Before", "  After", ""])
        expect(editor).to_have_attribute("contenteditable", "false")
        expect(page.locator("#line-composer-toggle")).to_have_attribute("aria-expanded", "true")
        expect(draft).to_be_visible()
        expect(draft).to_be_focused()
        expect(draft).to_have_value("draft 😀")
        expect(page.locator("#cursor-status")).to_contain_text("Ln 2, Col 1")
        page.locator("#toggle-readonly").click()
        page.locator("#undo").click()
        expect(lines).to_have_text(["Before", "  replacement", "  After", ""])
        expect(page.locator("#save")).to_be_disabled()
        page.locator("#redo").click()
        page.locator("#save").click()
        expect(page.locator("#save-status")).to_have_text("Saved")
        saved = context.request.get(base + "/api/v1/documents/deletion", headers=headers).json()
        assert base64.b64decode(saved["content_base64"]) == b"Before\r\n  After\r\n"
        page.locator("#toggle-readonly").click()
        draft.press("Enter")
        expect(lines).to_have_text(["Before", "  After", "  draft 😀", ""])
        page.locator("#toggle-readonly").click()
        page.locator("#undo").click()
        expect(lines).to_have_text(["Before", "  After", ""])
        editor.press("Control+End")
        page.locator("#toggle-readonly").click()
        draft.fill("keep this draft")
        for expected in [["Before", "  After"], ["Before"], [""]]:
            delete.click()
            dialog.get_by_role("button", name="Delete", exact=True).click()
            expect(lines).to_have_text(expected)
            expect(draft).to_be_focused()
            expect(draft).to_have_value("keep this draft")
            expect(page.locator("#line-composer-toggle")).to_have_attribute("aria-expanded", "true")
        expect(delete).to_be_disabled()
        draft.press("Enter")
        expect(lines).to_have_text(["keep this draft"])
        expect(delete).to_be_enabled()
        page.locator("#toggle-tasks-filter").click()
        expect(delete).to_be_disabled()
        page.locator("#toggle-tasks-filter").click()
        expect(delete).to_be_enabled()
        if page.locator("#sidebar-toggle").get_attribute("aria-expanded") == "true":
            page.locator("#sidebar-toggle").click()
        page.set_viewport_size({"width": 390, "height": 844})
        expect(delete).to_be_visible()
        assert page.locator("#line-composer").evaluate("el => el.scrollWidth <= el.clientWidth")
        assert not errors, errors
        context.close(); browser.close()
        print(f"{browser_name} line deletion checks passed: confirmation, cancel, remote refresh, draft, cursor, Undo, exact bytes, final line, empty file, task view, and mobile layout")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--browser", choices=["chromium", "webkit"], default="chromium")
    main(parser.parse_args().browser)
