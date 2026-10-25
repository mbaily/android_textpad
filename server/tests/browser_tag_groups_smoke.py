"""Named tag groups: shared settings, matching, offline persistence, and live updates."""
import base64
from playwright.sync_api import expect, sync_playwright
from browser_smoke import TOKEN, fixture


def main():
    with fixture() as base, sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 800})
        headers = {"Authorization": "Bearer " + TOKEN}
        text = "[ ] Last tag #tag_949\n[X] Finished #tag_949\n[ ] Home #home\n"
        assert context.request.put(base + "/api/v1/documents/group-tasks", headers=headers, data={
            "name": "Group tasks.txt", "content_base64": base64.b64encode(text.encode()).decode(),
            "base_revision": None, "request_id": "seed-group-tasks",
        }).ok
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/editor/")
        page.locator("#credential").fill(TOKEN)
        page.get_by_role("button", name="Sign in", exact=True).click()
        page.locator('.file-item[data-id="group-tasks"]').click()
        page.locator("#hashtags").click()
        page.get_by_role("tab", name="Tag groups", exact=True).click()
        selector = page.locator("#tag-group-selector")
        settings = page.locator("#errands-settings")
        results = page.locator("#hashtag-results .hashtag-line-item")

        def groups():
            return context.request.get(base + "/api/v1/tag-groups", headers=headers).json()["groups"]

        def create(name, tags):
            page.locator("#new-tag-group").click()
            page.get_by_label("Group name", exact=True).fill(name)
            # Enter submits the group name, just like clicking Create.
            page.get_by_label("Group name", exact=True).press("Enter")
            expect(settings).to_have_js_property("open", True)
            page.locator("#errands-textarea").fill(tags)
            page.locator("#save-errands").click()
            page.wait_for_function("name => JSON.parse(localStorage.getItem('textpad.editor.tagGroups')).some(g => g.name === name && !g.dirty)", arg=name)
            return selector.input_value()

        tags = [f"tag_{i}" for i in range(950)]
        work = create("Work", " ".join(tags) + " #TAG_949")
        assert next(g for g in groups() if g["id"] == work)["hashtags"] == tags
        expect(results).to_have_count(1)
        page.get_by_label("Hide [X] / [C]", exact=True).uncheck()
        expect(results).to_have_count(2)
        page.get_by_role("button", name="File matching", exact=True).click()
        expect(page.locator("#hashtag-results .hashtag-file-group")).to_have_count(1)
        page.get_by_role("button", name="Line matching", exact=True).click()
        page.get_by_label("Hide [X] / [C]", exact=True).check()
        page.locator("#rename-tag-group").click()
        page.get_by_label("Group name", exact=True).fill("alpha")
        page.get_by_role("button", name="Rename", exact=True).click()
        expect(selector.locator("option")).to_have_text(["alpha (950)", "Errands (0)"])
        home = create("Home", "#HOME #home")
        zebra = create("zebra", "#other")
        expect(selector.locator("option")).to_have_text(["alpha (950)", "Errands (0)", "Home (1)", "zebra (1)"])

        # A reply for the first save must not acknowledge a second edit made in flight.
        held = []

        def hold_first_save(route):
            if route.request.method == "PUT" and not held:
                held.append(route)
            else:
                route.continue_()

        selector.select_option(home)
        page.locator("#errands-settings > summary").click()
        page.route("**/editor/api/tag-groups/" + home, hold_first_save)
        page.locator("#edit-errands").click()
        page.locator("#errands-textarea").fill("#home #calls")
        with page.expect_request(lambda request: request.method == "PUT" and request.url.endswith(home)):
            page.locator("#save-errands").click()
        expect(page.get_by_text("Saved on device · pending sync", exact=True)).to_be_visible()
        assert held
        page.locator("#edit-errands").click()
        page.locator("#errands-textarea").fill("#home #email")
        page.locator("#save-errands").click()
        held[0].fulfill(response=context.request.fetch(held[0].request))
        page.wait_for_function("id => JSON.parse(localStorage.getItem('textpad.editor.tagGroups')).some(g => g.id === id && !g.dirty && g.hashtags.includes('email'))", arg=home)
        page.unroute("**/editor/api/tag-groups/" + home, hold_first_save)
        assert next(g for g in groups() if g["id"] == home)["hashtags"] == ["home", "email"]
        expect(selector.locator("option")).to_have_text(["alpha (950)", "Errands (0)", "Home (2)", "zebra (1)"])
        page.locator("#new-tag-group").click()
        page.get_by_label("Group name", exact=True).fill("HOME")
        page.get_by_role("button", name="Create", exact=True).click()
        expect(selector.locator("option")).to_have_count(4)

        # A remote group arrives over the live change feed while the pane is open.
        assert context.request.put(base + "/api/v1/tag-groups/remote", headers=headers,
                                   data={"name": "Remote", "hashtags": ["home"]}).ok
        expect(selector.locator('option[value="remote"]')).to_have_text("Remote (1)", timeout=7000)
        selector.select_option("remote")
        expect(results).to_have_count(1)
        assert context.request.delete(base + "/api/v1/tag-groups/remote", headers=headers).ok
        expect(selector).to_have_value("", timeout=7000)
        expect(selector.locator('option[value="remote"]')).to_have_count(0)

        # Offline edits and deletion survive a full page reload and are retried online.
        page.wait_for_function("() => !!navigator.serviceWorker.controller")
        context.set_offline(True)
        selector.select_option(home)
        page.locator("#errands-settings > summary").click()
        page.locator("#edit-errands").click()
        page.locator("#errands-textarea").fill("#home #tag_949")
        page.locator("#save-errands").click()
        expect(results).to_have_count(2)
        selector.select_option(zebra)
        page.locator("#errands-settings > summary").click()
        page.locator("#delete-tag-group").click()
        page.get_by_role("button", name="Delete", exact=True).click()
        expect(selector.locator(f'option[value="{zebra}"]')).to_have_count(0)
        page.reload()
        page.locator("#hashtags").click()
        page.get_by_role("tab", name="Tag groups", exact=True).click()
        expect(selector.locator("option")).to_have_text(["alpha (950)", "Errands (0)", "Home (2)"])
        pending = page.evaluate("JSON.parse(localStorage.getItem('textpad.editor.tagGroups'))")
        assert next(g for g in pending if g["id"] == home)["dirty"]
        assert next(g for g in pending if g["id"] == zebra)["deleted"]
        context.set_offline(False)
        page.wait_for_function("() => JSON.parse(localStorage.getItem('textpad.editor.tagGroups')).every(g => !g.dirty)")
        assert [g["name"] for g in groups()] == ["alpha", "Home"]
        assert next(g for g in groups() if g["id"] == home)["hashtags"] == ["home", "tag_949"]
        # The two controls remain reachable in a narrow mobile layout.
        page.set_viewport_size({"width": 390, "height": 560})
        expect(selector).to_be_in_viewport()
        expect(page.locator("#new-tag-group")).to_be_in_viewport()
        page.screenshot(path="/tmp/textpad-tag-groups-mobile.png")
        assert not errors, errors
        browser.close()
    print("Browser tag groups passed: alphabetical CRUD, 950 tags, matching/filtering, live changes, offline edit/delete/reload/retry, mobile layout")


if __name__ == "__main__":
    main()
