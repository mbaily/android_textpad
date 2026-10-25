"""Cursor visibility and minimal scrolling when the read-only composer resizes."""
import argparse

from playwright.sync_api import expect, sync_playwright

from browser_keyboard_smoke import set_visual_viewport, sign_in
from browser_smoke import fixture


def metrics(page):
    return page.evaluate("""() => {
        const scroller = document.querySelector('.cm-scroller');
        const bounds = scroller.getBoundingClientRect();
        const cursor = document.querySelector('.cm-cursor-primary').getBoundingClientRect();
        return {scroll: scroller.scrollTop, height: scroller.clientHeight,
            top: cursor.top - bounds.top, bottom: cursor.bottom - bounds.top,
            margin: parseFloat(getComputedStyle(scroller).lineHeight)};
    }""")


def wait_for_margin(page):
    page.wait_for_function("""() => {
        const cursor = document.querySelector('.cm-cursor-primary')?.getBoundingClientRect();
        if (!cursor || !cursor.height) return false;
        const scroller = document.querySelector('.cm-scroller');
        const bounds = scroller.getBoundingClientRect();
        const margin = parseFloat(getComputedStyle(scroller).lineHeight);
        return cursor.top >= bounds.top + margin - 1
            && cursor.bottom <= bounds.top + scroller.clientHeight - margin + 1;
    }""")
    settle(page)


def settle(page):
    page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")


def place_cursor(page, edge):
    # Move the viewport without moving the document selection. Include a partial
    # margin at the top so the check also covers visible rows too close to an edge.
    page.evaluate("""edge => {
        const scroller = document.querySelector('.cm-scroller');
        const bounds = scroller.getBoundingClientRect();
        const cursor = document.querySelector('.cm-cursor-primary').getBoundingClientRect();
        const margin = parseFloat(getComputedStyle(scroller).lineHeight);
        scroller.scrollTop += edge === 'bottom'
            ? cursor.bottom - bounds.top - scroller.clientHeight + margin
            : edge === 'top' ? cursor.top - bounds.top - margin / 2
            : cursor.top - bounds.top - scroller.clientHeight / 2;
    }""", edge)
    settle(page)


def main(browser_name="chromium"):
    with fixture() as base, sync_playwright() as p:
        engine = getattr(p, browser_name)
        browser = engine.launch(**({"channel": "chrome", "args": ["--no-sandbox"]} if browser_name == "chromium" else {}))
        context = browser.new_context(viewport={"width": 1280, "height": 900})
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("console", lambda message: errors.append(message.text)
                if message.type == "error" and "Invalid frame header" not in message.text else None)
        sign_in(page, base)
        editor = page.get_by_role("textbox", name="Document text")
        editor.fill("\n".join(f"Line {line:03d} " + "wrapped text " * 12 for line in range(150)))
        page.locator("#toggle-readonly").click()
        handle = page.locator("#line-composer-toggle")
        handle.click()
        editor.focus()
        editor.press("Control+Home")
        for _ in range(90):
            editor.press("ArrowDown")
        handle.focus()
        status = page.locator("#cursor-status").text_content()
        settle(page)

        # Expansion obscures a row at the old lower edge; reveal it by exactly
        # the lost viewport height rather than jumping to the middle.
        place_cursor(page, "bottom")
        before = metrics(page)
        handle.click()
        wait_for_margin(page)
        after = metrics(page)
        assert abs(after["scroll"] - before["scroll"] - (before["height"] - after["height"])) <= 2, (before, after)
        expect(handle).to_be_focused()
        assert page.locator("#cursor-status").text_content() == status

        # Growing the viewport leaves an already visible cursor in place.
        handle.click()
        wait_for_margin(page)
        assert abs(metrics(page)["scroll"] - after["scroll"]) <= 1

        # A visible row inside the upper margin needs only a small upward move.
        place_cursor(page, "top")
        before = metrics(page)
        handle.click()
        wait_for_margin(page)
        after = metrics(page)
        assert abs(before["scroll"] - after["scroll"] - before["margin"] / 2) <= 2, (before, after)

        # Neither transition moves a row that already has enough context.
        place_cursor(page, "middle")
        before = metrics(page)
        for _ in range(2):
            handle.click()
            wait_for_margin(page)
            assert abs(metrics(page)["scroll"] - before["scroll"]) <= 1

        # Keyboard resizing must reveal the document cursor even while focus and
        # a selection stay in the independent draft input.
        draft = page.locator("#line-input")
        draft.fill("preserve this draft")
        draft.evaluate("el => el.setSelectionRange(2, 7)")
        place_cursor(page, "bottom")
        before = metrics(page)
        page.evaluate("""() => {
            window.keyboardViewport = {height: window.innerHeight, top: 0};
            Object.defineProperties(window.visualViewport, {
                height: {get: () => window.keyboardViewport.height},
                offsetTop: {get: () => window.keyboardViewport.top},
            });
        }""")
        set_visual_viewport(page, 600, 80)
        wait_for_margin(page)
        after = metrics(page)
        assert abs(after["scroll"] - before["scroll"] - (before["height"] - after["height"])) <= 2, (before, after)
        expect(draft).to_be_focused()
        expect(draft).to_have_value("preserve this draft")
        assert draft.evaluate("el => [el.selectionStart, el.selectionEnd]") == [2, 7]
        set_visual_viewport(page, 900, 0)
        wait_for_margin(page)
        assert abs(metrics(page)["scroll"] - after["scroll"]) <= 1
        assert page.locator("#cursor-status").text_content() == status
        assert page.evaluate("window.scrollY === 0")

        # Scrolling far away must still reveal the current row at the nearest
        # margin while CodeMirror virtualizes the intervening document.
        handle.focus()
        page.locator(".cm-scroller").evaluate("el => { el.scrollTop = el.scrollHeight; }")
        page.wait_for_function("() => document.querySelector('.cm-cursor-primary').getBoundingClientRect().bottom < 0")
        handle.click()
        wait_for_margin(page)
        after = metrics(page)
        assert abs(after["top"] - after["margin"]) <= 2, after
        assert page.locator("#cursor-status").text_content() == status
        assert not errors, errors
        browser.close()
    print(f"{browser_name} composer scrolling checks passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", choices=("chromium", "webkit"), default="chromium")
    main(parser.parse_args().browser)
