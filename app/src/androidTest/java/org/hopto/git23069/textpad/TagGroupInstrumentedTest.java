package org.hopto.git23069.textpad;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class TagGroupInstrumentedTest {
    private TextpadApplication app;
    private GroupsApi api;

    private static final class GroupsApi implements RemoteApi {
        final Map<String, TagGroup> groups = new LinkedHashMap<>();
        boolean failWrites;
        Runnable duringPut;
        public List<Remote> list() { return List.of(); }
        public Remote get(String id) { throw new UnsupportedOperationException(); }
        public Remote put(String id, String name, byte[] bytes, String base, String request) { throw new UnsupportedOperationException(); }
        public List<Remote> revisions(String id) { return List.of(); }
        public Remote revision(String id, String revision) { throw new UnsupportedOperationException(); }
        public List<TagGroup> getTagGroups() { return new ArrayList<>(groups.values()); }
        public TagGroup putTagGroup(TagGroup group) throws IOException {
            if (failWrites) throw new IOException("Offline");
            TagGroup saved = new TagGroup(group.id(), group.name(), group.hashtags(), "server-time", false, false, "");
            groups.put(group.id(), saved);
            if (duringPut != null) { Runnable action = duringPut; duringPut = null; action.run(); }
            return saved;
        }
        public void deleteTagGroup(String id) throws IOException {
            if (failWrites) throw new IOException("Offline");
            groups.remove(id);
        }
    }

    @Before public void reset() throws Exception {
        app = (TextpadApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        app.io.submit(() -> {}).get(30, TimeUnit.SECONDS);
        app.settings.prefs.edit().remove("tagGroups").commit();
        api = new GroupsApi();
    }

    private TagGroup group(String id, String name, List<String> tags) {
        return new TagGroup(id, name, List.of(), "", false, false, "").edit(name, tags);
    }

    @Test public void unlimitedGroupsAndLargeTagListsPersistInAlphabeticalOrder() {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 950; i++) tags.add("tag_" + i);
        app.settings.saveTagGroup(group("z", "zebra", tags));
        app.settings.saveTagGroup(group("a", "alpha", List.of("#HOME", "home")));
        for (int i = 0; i < 220; i++) app.settings.saveTagGroup(group("group-" + i, "Group " + i, List.of()));
        EditorSettings reopened = new EditorSettings(app);
        List<TagGroup> saved = reopened.tagGroups();
        assertEquals(222, saved.size());
        assertEquals("alpha", saved.get(0).name());
        assertEquals(List.of("home"), saved.get(0).hashtags());
        assertEquals(tags, saved.get(saved.size() - 1).hashtags());
    }

    @Test public void editsMadeDuringSyncRemainPendingAndOtherGroupsArePreserved() {
        TagGroup original = group("home", "Home", List.of("home"));
        TagGroup newer = original.edit("Family", List.of("family", "calls"));
        app.settings.saveTagGroup(original);
        TagGroup other = new TagGroup("work", "Work", List.of("calls"), "remote", false, false, "");
        api.groups.put(other.id(), other);
        api.duringPut = () -> app.settings.saveTagGroup(newer);
        app.sync.syncTagGroups(api);
        assertEquals(List.of(newer, other), app.settings.tagGroups());
        app.sync.syncTagGroups(api);
        assertFalse(app.settings.tagGroups().get(0).dirty());
        assertEquals(newer.hashtags(), api.groups.get("home").hashtags());
        assertEquals(other, api.groups.get("work"));
    }

    @Test public void offlineDeletionSurvivesRestartAndRetriesWithoutDeletingOtherGroups() {
        app.settings.saveTagGroup(group("home", "Home", List.of("home")));
        app.settings.saveTagGroup(group("work", "Work", List.of("calls")));
        app.sync.syncTagGroups(api);
        TagGroup home = app.settings.tagGroups().get(0);
        app.settings.saveTagGroup(home.delete());
        api.failWrites = true;
        app.sync.syncTagGroups(api);
        assertEquals(1, new EditorSettings(app).tagGroups().size());
        assertTrue(app.settings.allTagGroups().stream().anyMatch(g -> g.id().equals("home") && g.deleted() && g.dirty()));
        assertEquals(2, api.groups.size());
        api.failWrites = false;
        app.sync.syncTagGroups(api);
        assertEquals(List.of("Work"), app.settings.tagGroups().stream().map(TagGroup::name).toList());
        assertFalse(api.groups.containsKey("home"));
        api.groups.clear();
        app.sync.syncTagGroups(api);
        assertTrue(app.settings.tagGroups().isEmpty());
    }

    @Test public void namesNormalizeAndReserveErrands() {
        assertEquals("Café", TagGroup.normalizeName("  Cafe\u0301  "));
        for (String name : List.of("", "  ", "ERRANDS", "line\nbreak", "a".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> TagGroup.normalizeName(name));
        }
    }
}
