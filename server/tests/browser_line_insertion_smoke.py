"""Read-only line composer checks against an isolated server (requires Playwright)."""
import argparse
import base64

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture
from browser_keyboard_smoke import set_visual_viewport


def main(browser_name="chromium"):
    original = "Heading\r\n  first\r\n    nested\r\n  second"
    with fixture() as base, sync_playwright() as p:
        engine = getattr(p, browser_name)
        browser = engine.launch(**({"channel": "chrome", "args": ["--no-sandbox"]} if browser_name == "chromium" else {}))
        context = browser.new_context(viewport={"width": 1280, "height": 900})
        headers = {"Authorization": "Bearer " + TOKEN}
        for doc_id, text in [("composer", original), ("empty-composer", ""), ("tabs-composer", "root\n\tfirst\n\t\tnested\n")]:
            response = context.request.put(base + "/api/v1/documents/" + doc_id, headers=headers, data={
                "name": doc_id + ".txt", "content_base64": base64.b64encode(text.encode()).decode(),
                "base_revision": None, "request_id": "seed-" + doc_id,
            })
            assert response.ok
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/editor/")
        page.locator("#credential").fill(TOKEN)
        page.get_by_role("button", name="Sign in", exact=True).click()
        page.locator(".file-item").filter(has_text="composer.txt").filter(has_not_text="empty-").filter(has_not_text="tabs-").click()
        editor = page.get_by_role("textbox", name="Document text")
        lines = editor.locator(".cm-line")
        editor.click()
        page.keyboard.press("Control+Home")
        page.keyboard.press("ArrowDown")
        page.keyboard.press("ArrowRight")
        page.locator("#toggle-readonly").click()
        expect(editor).to_have_attribute("contenteditable", "false")
        expect(editor).to_have_attribute("aria-readonly", "true")
        expect(editor).to_have_attribute("tabindex", "0")
        expect(page.locator("#line-input-status")).to_have_text("Add below line 2 · Indent: 2 spaces")
        # Navigation and direct editing stay separate; selections cannot replace text.
        editor.focus()
        page.keyboard.press("ArrowDown")
        expect(page.locator("#line-input-status")).to_have_text("Add below line 3 · Indent: 4 spaces")
        page.keyboard.press("ArrowUp")
        page.keyboard.insert_text("blocked")
        page.keyboard.press("Delete")
        page.keyboard.press("Backspace")
        expect(lines).to_have_text(original.replace("\r\n", "\n").split("\n"))
        page.keyboard.press("Shift+ArrowDown")
        page.keyboard.press("Backspace")
        expect(lines).to_have_count(4)
        page.keyboard.press("Control+Home")
        page.keyboard.press("ArrowDown")

        new_line = page.locator("#line-input")
        new_line.fill("new 😀")
        page.locator("#line-indent-more").click()
        expect(page.locator("#line-input-status")).to_have_text("Add below line 2 · Indent: 4 spaces")
        disclosure = page.locator("#line-composer-toggle")
        expect(disclosure).to_have_attribute("aria-expanded", "true")
        expect(disclosure).to_have_attribute("aria-label", "Add one line")
        assert disclosure.bounding_box()["height"] == 10
        expanded_height = page.locator("#editor-host").bounding_box()["height"]
        disclosure.click()
        expect(disclosure).to_have_attribute("aria-expanded", "false")
        expect(new_line).to_be_hidden()
        expect(page.locator("#line-input-status")).to_be_hidden()
        assert page.locator("#line-composer").bounding_box()["height"] == 10
        assert page.locator("#editor-host").bounding_box()["height"] > expanded_height
        expect(lines).to_have_text(original.replace("\r\n", "\n").split("\n"))
        page.locator("#toggle-readonly").click()
        page.locator("#toggle-readonly").click()
        expect(disclosure).to_have_attribute("aria-expanded", "false")
        disclosure.focus()
        page.keyboard.press("Enter")
        expect(disclosure).to_have_attribute("aria-expanded", "true")
        expect(new_line).to_have_value("new 😀")
        expect(page.locator("#line-input-status")).to_have_text("Add below line 2 · Indent: 4 spaces")
        assert page.locator(".cm-cursor-primary").evaluate("el => getComputedStyle(el).display") != "none"
        assert page.locator(".cm-cursorLayer").evaluate("el => getComputedStyle(el).opacity") == "1"
        page.locator("#line-add").click()
        expected = ["Heading", "  first", "    new 😀", "    nested", "  second"]
        expect(lines).to_have_text(expected)
        expect(new_line).to_have_value("")
        expect(new_line).to_be_focused()
        expect(page.locator("#cursor-status")).to_contain_text("Ln 3, Col 11")
        expect(page.locator("#save")).to_be_enabled()
        new_line.fill("next")
        # Browser text inputs strip newlines, so reject multiline clipboard data before insertion.
        new_line.evaluate("el => { const data = new DataTransfer(); data.setData('text/plain', 'bad\\npaste'); el.dispatchEvent(new ClipboardEvent('paste', {clipboardData:data, bubbles:true, cancelable:true})); }")
        expect(new_line).to_have_value("next")
        expect(page.locator("#notice-text")).to_have_text("Enter one line at a time")
        page.locator("#line-indent-less").click()
        new_line.press("Enter")
        expect(lines).to_have_text(expected[:3] + ["  next"] + expected[3:])
        # Each addition is one history entry; switching mode restores ordinary undo.
        page.locator("#toggle-readonly").click()
        page.locator("#undo").click()
        expect(lines).to_have_text(expected)
        page.locator("#undo").click()
        expect(lines).to_have_text(original.replace("\r\n", "\n").split("\n"))
        expect(page.locator("#save")).to_be_disabled()
        page.locator("#redo").click()
        page.locator("#save").click()
        expect(page.locator("#save-status")).to_have_text("Saved")
        saved = context.request.get(base + "/api/v1/documents/composer", headers=headers).json()
        assert base64.b64decode(saved["content_base64"]) == "\r\n".join(expected).encode()

        # Drafts and indentation belong to their tab, and survive a clean remote refresh.
        page.locator("#toggle-readonly").click()
        new_line.fill("draft")
        page.locator("#line-indent-more").click()
        draft_status = page.locator("#line-input-status").text_content()
        disclosure.click()
        page.locator(".file-item").filter(has_text="empty-composer.txt").click()
        expect(disclosure).to_have_attribute("aria-expanded", "false")
        expect(new_line).to_be_hidden()
        disclosure.click()
        expect(new_line).to_have_value("")
        new_line.fill("first")
        new_line.press("Enter")
        expect(lines).to_have_text(["first"])
        page.get_by_role("tab", name="composer.txt", exact=True).click()
        expect(new_line).to_have_value("draft")
        expect(page.locator("#line-input-status")).to_have_text(draft_status)
        response = context.request.put(base + "/api/v1/documents/composer", headers=headers, data={
            "name": "composer.txt", "content_base64": base64.b64encode(("\r\n".join(expected) + "\r\nremote").encode()).decode(),
            "base_revision": saved["revision"], "request_id": "composer-remote-refresh",
        })
        assert response.ok
        expect(lines.last).to_have_text("remote", timeout=7000)
        expect(new_line).to_have_value("draft")
        expect(page.locator("#line-input-status")).to_have_text(draft_status)
        page.locator("#toggle-tasks-filter").click()
        expect(new_line).to_be_disabled()
        expect(page.locator("#line-add")).to_be_disabled()
        page.locator("#toggle-tasks-filter").click()
        expect(new_line).to_be_enabled()
        # Mouse positioning and tabs, including preserving a final newline.
        page.locator(".file-item").filter(has_text="tabs-composer.txt").click()
        lines.nth(1).click()
        expect(page.locator("#line-input-status")).to_have_text("Add below line 2 · Indent: 1 characters (tabs)")
        page.locator("#line-indent-more").click()
        new_line.fill("tabbed")
        new_line.press("Enter")
        expect(lines).to_have_text(["root", "\tfirst", "\t\ttabbed", "\t\tnested", ""])
        if page.locator("#sidebar-toggle").get_attribute("aria-expanded") == "true":
            page.locator("#sidebar-toggle").click()
        page.set_viewport_size({"width": 390, "height": 844})
        expect(new_line).to_be_visible()
        assert page.locator("#line-composer").evaluate("el => el.scrollWidth <= el.clientWidth")
        # Keep the composer and cursor usable when the mobile keyboard resizes
        # and pans the visual viewport independently of the page.
        page.evaluate("""() => {
            window.keyboardViewport = {height: window.innerHeight, top: 0};
            Object.defineProperties(window.visualViewport, {
                height: {get: () => window.keyboardViewport.height},
                offsetTop: {get: () => window.keyboardViewport.top},
            });
        }""")
        set_visual_viewport(page, 350, 170)
        new_line.fill("mobile")
        expect(new_line).to_be_focused()
        bounds = page.locator("#line-composer").bounding_box()
        assert bounds["y"] >= 170 and bounds["y"] + bounds["height"] <= 521, bounds
        new_line.press("Enter")
        expect(lines).to_have_text(["root", "\tfirst", "\t\ttabbed", "\t\tmobile", "\t\tnested", ""])
        expect(new_line).to_be_focused()
        page.wait_for_function("""() => {
            const cursor = document.querySelector('.cm-cursor-primary').getBoundingClientRect();
            const editor = document.querySelector('.editor-host').getBoundingClientRect();
            return cursor.top >= editor.top && cursor.bottom <= editor.bottom;
        }""")
        set_visual_viewport(page, 350, 220, "scroll")
        disclosure.click()
        expect(new_line).to_be_hidden()
        expect(disclosure).to_be_focused()
        disclosure.press("Space")
        expect(new_line).to_be_visible()
        set_visual_viewport(page, 844, 0)
        # The preference survives a fresh page independently of live tab drafts.
        disclosure.click()
        reopened = context.new_page()
        reopened.goto(base + "/editor/")
        if reopened.locator("#sidebar-toggle").get_attribute("aria-expanded") == "false":
            reopened.locator("#sidebar-toggle").click()
        reopened.locator(".file-item").filter(has_text="composer.txt").filter(has_not_text="empty-").filter(has_not_text="tabs-").click()
        reopened.locator("#toggle-readonly").click()
        expect(reopened.locator("#line-composer-toggle")).to_have_attribute("aria-expanded", "false")
        expect(reopened.locator("#line-input")).to_be_hidden()
        reopened.close()
        assert not errors, errors
        browser.close()
    print(f"{browser_name} read-only line composer checks passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", choices=("chromium", "webkit"), default="chromium")
    main(parser.parse_args().browser)
