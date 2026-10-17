"""Task-view regression checks against an isolated server (requires Playwright)."""
import base64

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def main():
    original = (
        "Unrelated introduction\n# Shopping\n[X] Finished project\n"
        "    [X] Finished step\n    [ ] Buy milk\n        Remember organic\n"
        "    [X] Finished sibling\n[ ] Buy apples\n[X] Finished last task\n"
    )
    visible = ["# Shopping", "[X] Finished project", "    [ ] Buy milk",
               "        Remember organic", "[ ] Buy apples"]
    with fixture() as base, sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 960})
        headers = {"Authorization": "Bearer " + TOKEN}
        for doc_id, name, text in [
            ("filter-tasks", "Filter tasks.txt", original),
            ("no-open-tasks", "No open tasks.txt", "Plain notes\n[X] Finished task\n"),
        ]:
            response = context.request.put(base + "/api/v1/documents/" + doc_id, headers=headers, data={
                "name": name, "content_base64": base64.b64encode(text.replace("\n", "\r\n").encode()).decode(),
                "base_revision": None, "request_id": "seed-" + doc_id,
            })
            assert response.ok

        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/editor/")
        page.locator("#credential").fill(TOKEN)
        page.get_by_role("button", name="Sign in", exact=True).click()
        page.locator(".file-item").filter(has_text="Filter tasks.txt").click()
        editor = page.get_by_role("textbox", name="Document text")
        lines = editor.locator(".cm-line")
        toggle = page.locator("#toggle-tasks-filter")
        expect(lines).to_have_text(original.split("\n"))
        toggle.click()
        assert not errors, errors
        expect(toggle).to_have_attribute("aria-pressed", "true")
        expect(lines).to_have_text(visible)
        expect(editor.locator(".cm-task-context")).to_have_text(visible[:2])
        expect(page.locator("#save")).to_be_disabled()

        # Typing at the first position after a hidden block stays visible.
        lines.nth(2).click()
        page.keyboard.press("Home")
        page.keyboard.insert_text("  ")
        expect(lines).to_have_text(visible[:2] + ["  " + visible[2]] + visible[3:])
        page.keyboard.press("Control+z")
        expect(lines).to_have_text(visible)
        expect(page.locator("#save")).to_be_disabled()

        # Restrictions keep edits from spanning hidden lines.
        lines.last.click()
        page.keyboard.press("End")
        page.keyboard.press("Enter")
        expect(lines).to_have_text(visible)
        expect(page.locator("#notice-text")).to_have_text("Lines cannot be added or deleted in this view")
        page.keyboard.press("Control+a")
        page.keyboard.press("Backspace")
        expect(lines).to_have_text(visible)
        lines.last.click()
        page.keyboard.press("End")
        page.keyboard.press("Control+Shift+ArrowLeft")
        page.keyboard.insert_text("pears")
        edited_visible = visible[:-1] + ["[ ] Buy pears"]
        expect(lines).to_have_text(edited_visible)
        page.keyboard.press("Control+s")
        expect(page.locator("#save")).to_be_disabled()
        saved = context.request.get(base + "/api/v1/documents/filter-tasks", headers=headers).json()
        assert base64.b64decode(saved["content_base64"]) == original.replace("apples", "pears").replace("\n", "\r\n").encode()

        # Switching tabs retains the filter; switching it off restores every line.
        page.locator(".file-item").filter(has_text="No open tasks.txt").click()
        expect(toggle).to_have_attribute("aria-pressed", "false")
        toggle.click()
        expect(editor).to_have_text("")
        expect(page.locator("#notice-text")).to_have_text("No uncompleted tasks found")
        toggle.click()
        expect(lines).to_have_text(["Plain notes", "[X] Finished task", ""])
        page.get_by_role("tab", name="Filter tasks.txt", exact=True).click()
        expect(toggle).to_have_attribute("aria-pressed", "true")
        expect(lines).to_have_text(edited_visible)
        toggle.click()
        expect(lines).to_have_text(original.replace("apples", "pears").split("\n"))
        expect(page.locator("#save")).to_be_disabled()

        # Removing the filter must reveal the cursor after hundreds of hidden lines expand.
        page.locator("#new-file").click()
        page.get_by_role("textbox", name="Filename", exact=True).fill("Task cursor.txt")
        page.locator("#modal-actions").get_by_role("button", name="Create", exact=True).click()
        editor.click()
        page.keyboard.insert_text("[ ] First task\n" + "[X] Completed before target\n" * 250
                                  + "[ ] Task to keep visible\n" + "[X] Completed after target\n" * 250)
        toggle.click()
        expect(lines).to_have_text(["[ ] First task", "[ ] Task to keep visible"])
        lines.last.click()
        page.keyboard.press("End")
        expect(page.locator("#cursor-status")).to_contain_text("Ln 252, Col 25")
        toggle.click()
        expect(toggle).to_have_attribute("aria-pressed", "false")
        expect(page.locator("#cursor-status")).to_contain_text("Ln 252, Col 25")
        expect(lines.filter(has_text="[ ] Task to keep visible")).to_be_in_viewport()

        assert not errors, errors
        browser.close()
    print("Browser task-view checks passed")


if __name__ == "__main__":
    main()
