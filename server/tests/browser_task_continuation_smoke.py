"""Task continuation in the read-only composer, using an isolated server."""
import argparse
import base64

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def main(browser_name="chromium"):
    original = "Heading\r\n  - [x] done\r\n\t* [c] stopped\r\n    [ ] open\r\nplain\r\n"
    with fixture() as base, sync_playwright() as p:
        engine = getattr(p, browser_name)
        browser = engine.launch(**({"channel": "chrome", "args": ["--no-sandbox"]} if browser_name == "chromium" else {}))
        context = browser.new_context(viewport={"width": 1280, "height": 900})
        context.add_init_script("localStorage.setItem('textpad.editor.lineComposerCollapsed:', 'true')")
        headers = {"Authorization": "Bearer " + TOKEN}
        response = context.request.put(base + "/api/v1/documents/continuation", headers=headers, data={
            "name": "continuation.txt", "content_base64": base64.b64encode(original.encode()).decode(),
            "base_revision": None, "request_id": "seed-continuation",
        })
        assert response.ok
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/editor/")
        page.locator("#credential").fill(TOKEN)
        page.get_by_role("button", name="Sign in", exact=True).click()
        page.locator(".file-item").filter(has_text="continuation.txt").click()
        editor = page.get_by_role("textbox", name="Document text")
        lines = editor.locator(".cm-line")
        editor.press("Control+Home"); editor.press("ArrowDown")
        page.locator("#toggle-readonly").click()
        draft = page.locator("#line-input")
        handle = page.locator("#line-composer-toggle")
        expect(handle).to_have_attribute("aria-expanded", "false")
        handle.click()
        expect(draft).to_have_value("- [ ] ")
        expect(page.locator("#line-input-status")).to_have_text("Add below line 2 · Indent: 2 spaces")
        expect(page.locator("#save")).to_be_disabled()
        expect(page.locator("#undo")).to_be_disabled()
        expect(lines).to_have_text(original.splitlines() + [""])

        # A cleared suggestion stays cleared until expansion or a context change.
        draft.fill("")
        page.locator("#line-indent-more").click()
        expect(draft).to_have_value("")
        adjusted_status = page.locator("#line-input-status").text_content()
        handle.click(); handle.click()
        expect(draft).to_have_value("- [ ] ")
        expect(page.locator("#line-input-status")).to_have_text(adjusted_status)
        editor.press("ArrowDown")
        expect(draft).to_have_value("* [ ] ")
        expect(page.locator("#line-input-status")).to_have_text("Add below line 3 · Indent: 1 characters (tabs)")
        editor.press("ArrowDown")
        expect(draft).to_have_value("[ ] ")
        expect(page.locator("#line-input-status")).to_have_text("Add below line 4 · Indent: 4 spaces")
        editor.press("ArrowDown")
        expect(draft).to_have_value("")
        editor.press("ArrowUp")
        expect(draft).to_have_value("[ ] ")

        draft.press("End"); draft.press_sequentially("keep this draft")
        handle.click()
        editor.press("Control+Home"); editor.press("ArrowDown")
        handle.click()
        expect(draft).to_have_value("[ ] keep this draft")
        expect(page.locator("#line-input-status")).to_have_text("Add below line 2 · Indent: 2 spaces")
        draft.fill("")
        editor.press("ArrowDown")
        expect(draft).to_have_value("* [ ] ")
        draft.press("End"); draft.press_sequentially("new 😀")
        draft.press("Enter")
        expected = original.replace("\t* [c] stopped\r\n", "\t* [c] stopped\r\n\t* [ ] new 😀\r\n")
        expect(lines).to_have_text(expected.splitlines() + [""])
        expect(draft).to_have_value("* [ ] ")
        expect(draft).to_be_focused()
        assert draft.evaluate("el => el.selectionStart === el.value.length && el.selectionEnd === el.value.length")
        draft.press_sequentially("again"); draft.press("Enter")
        twice = expected.replace("\t* [ ] new 😀\r\n", "\t* [ ] new 😀\r\n\t* [ ] again\r\n")
        expect(lines).to_have_text(twice.splitlines() + [""])
        expect(draft).to_have_value("* [ ] ")
        page.locator("#toggle-readonly").click()
        page.locator("#undo").click()
        expect(lines).to_have_text(expected.splitlines() + [""])
        page.locator("#undo").click()
        expect(lines).to_have_text(original.splitlines() + [""])
        expect(page.locator("#save")).to_be_disabled()
        page.locator("#redo").click()
        page.locator("#save").click()
        expect(page.locator("#save-status")).to_have_text("Saved")
        saved = context.request.get(base + "/api/v1/documents/continuation", headers=headers).json()
        assert base64.b64decode(saved["content_base64"]) == expected.encode()
        assert not errors, errors
        context.close(); browser.close()
    print(f"{browser_name} task continuation checks passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", choices=("chromium", "webkit"), default="chromium")
    main(parser.parse_args().browser)
