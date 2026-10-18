"""Errands settings and pane scrolling checks against an isolated server."""
import base64

from playwright.sync_api import expect, sync_playwright

from browser_smoke import TOKEN, fixture


def main():
    tags = [f"errand_{i}" for i in range(950)]
    text = "\n".join(f"[ ] Errand {i + 1:02d} #errand_{i}" for i in range(79))
    text += "\n[ ] Last errand #errand_949\n[X] Finished #errand_0\n[C] Cancelled #errand_949"
    with fixture() as base, sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 600})
        headers = {"Authorization": "Bearer " + TOKEN}
        response = context.request.put(base + "/api/v1/documents/errands-layout", headers=headers, data={
            "name": "Errands layout.txt", "content_base64": base64.b64encode(text.encode()).decode(),
            "base_revision": None, "request_id": "seed-errands-layout",
        })
        assert response.ok
        response = context.request.put(base + "/api/v1/errands", headers=headers, data={"hashtags": tags})
        assert response.ok

        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/editor/")
        page.locator("#credential").fill(TOKEN)
        page.get_by_role("button", name="Sign in", exact=True).click()
        page.locator('.file-item[data-id="errands-layout"]').click()
        page.locator("#sidebar-toggle").click()

        panel = page.locator("#hashtags-panel")
        settings = page.locator("#errands-settings")
        summary = page.locator("#errands-settings > summary")
        results = page.locator("#hashtag-results .hashtag-line-item")
        hide_closed = page.get_by_label("Hide [X] / [C]", exact=True)

        for width, height in [(1440, 600), (820, 500), (390, 560), (390, 280)]:
            page.set_viewport_size({"width": width, "height": height})
            page.locator("#hashtags").click()
            page.get_by_role("tab", name="Errands (950)", exact=True).click()
            expect(settings).to_have_js_property("open", False)
            expect(summary).to_contain_text("950 hashtags")
            expect(page.locator("#edit-errands")).to_be_hidden()
            expect(hide_closed).to_be_hidden()
            expect(hide_closed).to_be_checked()
            expect(results).to_have_count(80)
            expect(results.first).to_be_in_viewport()

            bounds = panel.bounding_box()
            assert bounds["x"] >= 0 and bounds["x"] + bounds["width"] <= width + 1, bounds
            assert bounds["y"] >= 0 and bounds["y"] + bounds["height"] <= height + 1, bounds
            assert panel.evaluate("el => el.scrollHeight > el.clientHeight")
            # A real wheel event scrolls the entire pane, rather than an inner results box.
            results.first.hover()
            page.mouse.wheel(0, 800)
            page.wait_for_function("() => document.getElementById('hashtags-panel').scrollTop > 0")
            assert page.locator("#hashtag-results").evaluate("el => el.scrollTop") == 0
            results.nth(79).scroll_into_view_if_needed()
            expect(results.nth(79)).to_be_in_viewport()
            results.nth(79).click()
            expect(page.locator("#cursor-status")).to_contain_text("Ln 80, Col 1")

            summary.click()
            expect(settings).to_have_js_property("open", True)
            hide_closed.uncheck()
            expect(results).to_have_count(82)
            summary.focus()
            page.keyboard.press("Enter")
            expect(settings).to_have_js_property("open", False)
            expect(results).to_have_count(82)
            summary.click()
            expect(hide_closed).not_to_be_checked()
            hide_closed.check()
            expect(results).to_have_count(80)

            if height == 280:
                # Editing controls remain reachable even when settings exceed the viewport.
                page.locator("#edit-errands").click()
                draft = " ".join("#" + tag for tag in tags) + " #ERRAND_949"
                page.locator("#errands-textarea").fill(draft)
                summary.click()
                expect(page.locator("#errands-textarea")).to_be_hidden()
                summary.click()
                expect(page.locator("#errands-textarea")).to_have_value(draft)
                page.locator("#save-errands").click()
                expect(page.locator("#errands-textarea")).to_be_hidden()
                assert context.request.get(base + "/api/v1/errands", headers=headers).json()["hashtags"] == tags

            page.locator("#close-hashtags").click()

        # An empty configuration also starts collapsed and explains where to add tags.
        response = context.request.put(base + "/api/v1/errands", headers=headers, data={"hashtags": []})
        assert response.ok
        page.reload()
        page.locator("#hashtags").click()
        page.get_by_role("tab", name="Errands", exact=True).click()
        expect(settings).to_have_js_property("open", False)
        expect(page.locator("#hashtag-results")).to_contain_text("Expand Errands settings")
        summary.click()
        page.locator("#errands-textarea").fill("#chemist")
        page.locator("#save-errands").click()
        expect(page.get_by_role("tab", name="Errands (1)", exact=True)).to_be_visible()
        assert not errors, errors
        browser.close()
    print("Browser errands checks passed: collapsed settings, 950 tags, wheel scrolling, desktop/tablet/mobile/short viewports, editing, filters, and result navigation")


if __name__ == "__main__":
    main()
