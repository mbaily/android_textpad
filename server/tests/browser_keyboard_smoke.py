"""Mobile keyboard layout checks with simulated VisualViewport changes.

Headless browsers do not open an iOS keyboard. Simulate its independent resize
and pan in both Chromium and WebKit, then exercise real scrolling and taps.
"""
from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def sign_in(page, base):
    page.goto(base + "/editor/")
    page.locator("#credential").fill(TOKEN)
    page.get_by_role("button", name="Sign in", exact=True).click()
    page.locator("#recent-file-list .recent-file").filter(has_text="Tasks.txt").click()


def assert_layout(page, height, top):
    page.wait_for_function("""({height, top}) => {
        const workspace = document.querySelector('.workspace').getBoundingClientRect();
        const heading = document.querySelector('.editor-heading').getBoundingClientRect();
        const toolbar = document.querySelector('.toolbar').getBoundingClientRect();
        const editor = document.querySelector('.editor-host').getBoundingClientRect();
        const status = document.querySelector('.editor-status').getBoundingClientRect();
        return Math.abs(workspace.height - height) < 1 && Math.abs(workspace.top - top) < 1
            && heading.top >= top && toolbar.top >= heading.bottom
            && toolbar.height >= 47 && toolbar.bottom <= top + height
            && editor.top >= toolbar.bottom && editor.height > 20
            && status.bottom <= top + height + 1;
    }""", arg={"height": height, "top": top})
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    assert page.evaluate("window.scrollY === 0")


def set_visual_viewport(page, height, top, event="resize"):
    page.evaluate("""({height, top, event}) => {
        window.keyboardViewport = {height, top};
        window.visualViewport.dispatchEvent(new Event(event));
    }""", {"height": height, "top": top, "event": event})
    assert_layout(page, height, top)


def check_keyboard(browser, base, name):
    context = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    # Override only the two keyboard-dependent metrics; retain the real viewport
    # EventTarget and the browser's native touch, focus, and scrolling behavior.
    sign_in(page, base)
    page.evaluate("""() => {
        window.keyboardViewport = {height: window.innerHeight, top: 0};
        Object.defineProperties(window.visualViewport, {
            height: {get: () => window.keyboardViewport.height},
            offsetTop: {get: () => window.keyboardViewport.top},
        });
    }""")
    editor = page.get_by_role("textbox", name="Document text")
    editor.fill("\n".join(f"{name} task {line:03d}" for line in range(1, 101)))
    editor.press("Control+End")
    expect(editor).to_be_focused()

    # Keyboard opens without resizing the layout viewport, and iOS pans it.
    set_visual_viewport(page, 350, 170)
    assert page.evaluate("window.innerHeight") == 844
    expect(editor).to_be_focused()
    page.wait_for_function("""() => {
        const cursor = document.querySelector('.cm-cursor').getBoundingClientRect();
        const editor = document.querySelector('.editor-host').getBoundingClientRect();
        return cursor.top >= editor.top && cursor.bottom <= editor.bottom;
    }""")

    # Scroll the toolbar horizontally using the browser, then tap an edit action.
    toolbar = page.get_by_role("toolbar", name="Editor tools")
    if name == "chromium":
        # Start a native touch swipe over a button, checking that focus
        # preservation leaves the toolbar's default horizontal panning intact.
        box = toolbar.bounding_box()
        y = box["y"] + box["height"] / 2
        session = context.new_cdp_session(page)
        session.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": 340, "y": y}]})
        for x in range(310, 69, -30):
            session.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [{"x": x, "y": y}]})
            page.wait_for_timeout(20)
        session.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
        session.detach()
    else:
        # Playwright's mobile WebKit driver cannot synthesize wheel/swipe
        # scrolling; scroll natively in JS, and use real taps below.
        toolbar.evaluate("el => el.scrollBy(390, 0)")
    page.wait_for_function("() => document.querySelector('.toolbar').scrollLeft > 100")
    expect(editor).to_be_focused()
    page.locator("#insert-date").tap()
    expect(editor).to_contain_text(page.evaluate("new Date().getFullYear().toString()"))
    expect(editor).to_be_focused()
    assert_layout(page, 350, 170)

    # Toolbar panels must also open within the keyboard-adjusted viewport,
    # with their close control reachable after iOS has panned the page.
    page.locator("#hashtags").scroll_into_view_if_needed()
    page.locator("#hashtags").tap()
    panel = page.locator("#hashtags-panel")
    expect(panel).to_be_visible()
    bounds = panel.bounding_box()
    assert bounds["y"] >= 170 and bounds["y"] + bounds["height"] <= 521, bounds
    page.locator("#close-hashtags").tap()
    expect(panel).to_be_hidden()
    editor.tap()

    # Save must remain tappable without taking focus and closing the keyboard.
    expect(page.locator("#save")).to_be_enabled()
    page.locator("#save").scroll_into_view_if_needed()
    page.locator("#save").tap()
    expect(page.locator("#save")).to_be_disabled()
    expect(editor).to_be_focused()
    assert_layout(page, 350, 170)

    # Viewport pans can arrive separately from resize, including during typing.
    set_visual_viewport(page, 350, 220, "scroll")
    editor.press("Enter")
    page.keyboard.insert_text("Keyboard stays open")
    expect(editor).to_contain_text("Keyboard stays open")
    assert_layout(page, 350, 220)

    # A short landscape viewport must not retain the old 400px minimum height.
    page.set_viewport_size({"width": 844, "height": 390})
    set_visual_viewport(page, 205, 85)
    expect(editor).to_be_focused()
    page.set_viewport_size({"width": 390, "height": 844})
    set_visual_viewport(page, 350, 0)

    # Keyboard dismissal restores the full editor, ready for the next focus.
    editor.evaluate("el => el.blur()")
    set_visual_viewport(page, 844, 0)
    editor.tap()
    set_visual_viewport(page, 350, 170)
    expect(page.get_by_role("tab").filter(has_text="Tasks.txt")).to_be_in_viewport()
    expect(editor).to_be_focused()
    assert not errors, errors
    context.close()
    print(f"{name} mobile keyboard checks passed")


def main():
    with fixture() as base, sync_playwright() as playwright:
        for name in ("chromium", "webkit"):
            engine = getattr(playwright, name)
            browser = engine.launch(**({"channel": "chrome", "args": ["--no-sandbox"]} if name == "chromium" else {}))
            try:
                check_keyboard(browser, base, name)
            finally:
                browser.close()

        # Retain a working CSS/window-resize fallback without VisualViewport.
        browser = playwright.chromium.launch(channel="chrome", args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 390, "height": 844})
        page = context.new_page()
        page.add_init_script("Object.defineProperty(window, 'visualViewport', {value: undefined})")
        sign_in(page, base)
        page.set_viewport_size({"width": 390, "height": 300})
        assert_layout(page, 300, 0)
        browser.close()
    print("Browser keyboard checks passed")


if __name__ == "__main__":
    main()
