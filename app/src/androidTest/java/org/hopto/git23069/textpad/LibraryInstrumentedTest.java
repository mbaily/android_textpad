package org.hopto.git23069.textpad;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.Folder;
import org.hopto.git23069.textpad.core.FolderTree;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class LibraryInstrumentedTest {
    private TextpadApplication app;
    private LibraryApi api;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(SaveTransaction.hash(bytes)); }
    private static final class LibraryApi implements RemoteApi {
        final Map<String, Folder> folders = new LinkedHashMap<>();
        final Map<String, Remote> files = new LinkedHashMap<>();
        final Map<String, List<Remote>> history = new LinkedHashMap<>();
        int sequence, uploads, batches, deletes;
        volatile boolean offline, failMoves;
        volatile CountDownLatch entered, release;
        private void online() throws IOException { if (offline) throw new IOException("Offline test connection"); }
        synchronized Remote seed(String id, String name, byte[] content, String parent) {
            Remote remote = new Remote(id, name, String.valueOf(++sequence), hash(content), content.length, content, "api", false, parent);
            files.put(id, remote); history.computeIfAbsent(id, ignored -> new ArrayList<>()).add(remote); return remote;
        }
        public List<Remote> list() throws IOException {
            online(); List<Remote> snapshot;
            CountDownLatch wait, signal;
            synchronized (this) { snapshot = new ArrayList<>(files.values()); wait = release; signal = entered; entered = null; release = null; }
            if (wait != null) {
                signal.countDown();
                try { if (!wait.await(15, TimeUnit.SECONDS)) throw new IOException("Test read timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            return snapshot;
        }
        public synchronized Remote get(String id) throws IOException { online(); Remote file = files.get(id); if (file == null) throw new IOException("File missing"); return file; }
        public synchronized Remote put(String id, String name, byte[] content, String base, String request) throws IOException {
            online(); uploads++; Remote old = files.get(id); return seed(id, name, content, old == null ? null : old.folderId());
        }
        public synchronized Remote createDocument(String id, String name, byte[] content, String request, String parent) throws IOException {
            online(); if (parent != null && !folders.containsKey(parent)) throw new IOException("Folder missing");
            uploads++; return seed(id, name, content, parent);
        }
        public synchronized List<Remote> revisions(String id) { return List.copyOf(history.get(id)); }
        public synchronized Remote revision(String id, String revision) { return history.get(id).stream().filter(r -> r.revision().equals(revision)).findFirst().orElseThrow(); }
        public List<TagGroup> getTagGroups() { return List.of(); }
        public synchronized List<Folder> folders() throws IOException { online(); return new ArrayList<>(folders.values()); }
        public synchronized Folder folder(String id) throws IOException { online(); Folder folder = folders.get(id); if (folder == null) throw new IOException("Folder missing"); return folder; }
        public synchronized Folder putFolder(String id, String name, String parent) throws IOException {
            online(); if (parent != null && (!folders.containsKey(parent) || new FolderTree(new ArrayList<>(folders.values())).descendants(id).contains(parent))) throw new IOException("Invalid destination");
            Folder saved = new Folder(id, name, parent, "now", "now"); folders.put(id, saved); return saved;
        }
        public synchronized void deleteFolder(String id) throws IOException {
            online();
            if (folders.values().stream().anyMatch(f -> Objects.equals(id, f.parentId())) || files.values().stream().anyMatch(f -> Objects.equals(id, f.folderId()))) throw new IOException("Folder is not empty");
            folders.remove(id); deletes++;
        }
        public synchronized List<Remote> moveDocuments(List<String> ids, String parent) throws IOException {
            online(); batches++;
            if (failMoves) throw new IOException("Selected file no longer exists");
            if (parent != null && !folders.containsKey(parent)) throw new IOException("Folder missing");
            for (String id : ids) if (!files.containsKey(id)) throw new IOException("File missing");
            List<Remote> moved = new ArrayList<>();
            for (String id : ids) {
                Remote old = files.get(id), next = new Remote(id, old.name(), old.revision(), old.hash(), old.size(), old.content(), old.source(), false, parent);
                files.put(id, next); moved.add(next);
            }
            return moved;
        }
        public synchronized Remote renameDocument(String id, String name, String base, String request) throws IOException {
            Remote current = get(id); if (!current.revision().equals(base)) throw new IOException("Revision changed");
            return seed(id, name, current.content(), current.folderId());
        }
    }
    private void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private void waitFor(BooleanSupplier ready) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean value = new AtomicBoolean(); main(() -> value.set(ready.getAsBoolean()));
            if (value.get()) return; Thread.sleep(40);
        }
        fail("Timed out waiting for native library");
    }
    @Before public void reset() throws Exception {
        app = (TextpadApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        app.io.submit(() -> {}).get(30, TimeUnit.SECONDS); api = new LibraryApi();
        main(() -> {
            app.tabs.clear(); app.selectedTab = -1; app.events.clear(); app.filesVisible = false; app.exiting = false;
            app.readOnlyMode = false; app.pendingSave = null; app.sync.rerun = false;
            app.settings.prefs.edit().clear().commit(); app.getSharedPreferences("files", 0).edit().clear().commit();
            app.sync.testApi = api; app.sync.prefs.edit().clear().putString("endpoint", "https://library-" + UUID.randomUUID() + ".invalid").commit();
            app.library.ensureScope();
        });
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        var info = automation.getServiceInfo(); info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; automation.setServiceInfo(info);
    }
    @After public void cleanup() throws Exception {
        app.io.submit(() -> {}).get(30, TimeUnit.SECONDS);
        main(() -> { app.filesVisible = false; app.sync.testApi = null; app.sync.prefs.edit().clear().commit(); app.library.ensureScope(); });
    }
    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String value, boolean description) {
        if (node == null) return null;
        CharSequence text = description ? node.getContentDescription() : node.getText();
        if ((description ? value.contentEquals(text == null ? "" : text) : value.equalsIgnoreCase(text == null ? "" : text.toString())) && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) { var found = find(node.getChild(i), value, description); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo node(String value, boolean description) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            var found = find(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(), value, description);
            if (found != null) return found; Thread.sleep(60);
        }
        throw new AssertionError("Missing native control: " + value);
    }
    private void click(String value) throws Exception { assertTrue(node(value, true).performAction(AccessibilityNodeInfo.ACTION_CLICK)); }
    private void textButton(String value) throws Exception {
        var found = node(value, false); while (!found.isClickable() && found.getParent() != null) found = found.getParent();
        assertTrue(found.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    private void name(String title, String name) throws Exception {
        Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name);
        assertTrue(node(title, true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)); textButton("Save");
    }
    private void idle() throws Exception { waitFor(() -> !app.library.loading && !app.library.busy && !app.sync.busy); }

    @Test public void nativeControlsManageNestedFoldersAndPreserveDirtyLinkedTabs() throws Exception {
        api.putFolder("home", "Home", null); api.putFolder("work", "Work", null);
        byte[] original = ("\uFEFFOriginal café 😀\r\n").getBytes(StandardCharsets.UTF_16LE);
        var remote = api.seed("plan", "Plan.txt", original, null); api.seed("notes", "Notes.txt", bytes("Notes\n"), null);
        Uri local = DocumentsContract.buildDocumentUri("org.hopto.git23069.textpad.test.documents", "native-plan-" + UUID.randomUUID() + ".txt");
        try (var out = app.getContentResolver().openOutputStream(local, "wt")) { out.write(original); }
        EditorDocument doc = new EditorDocument("Plan.txt", new DocumentBuffer(TextCodec.decode(original)));
        doc.uri = local; doc.savedHash = SaveTransaction.hash(original);
        main(() -> { app.sync.link(doc, remote.id(), remote.revision(), remote.hash()); app.addTab(doc); doc.buffer.edit(doc.buffer.length(), doc.buffer.length(), "Unsaved note\n"); });
        try (var scenario = launch()) {
            click("Browse shared files"); waitFor(() -> app.library.loaded); idle();
            assertEquals(List.of("Home", "Work"), app.library.tree.children(null).stream().map(Folder::name).toList());
            click("New folder"); name("New folder", "Drafts"); idle();
            String draft = app.library.folderId; assertEquals("Drafts", api.folder(draft).name());
            click("Current folder actions"); textButton("Rename folder"); name("Rename folder", "Draft notes"); idle();
            click("Current folder actions"); textButton("Move folder…"); click("Destination folder: Home"); textButton("Move here"); idle();
            assertEquals("home", api.folder(draft).parentId());
            click("Top level"); click("Select Notes.txt"); click("Select Plan.txt");
            click("Move selected files"); click("Destination folder: Home"); textButton("Move here"); idle();
            assertEquals("home", api.get("plan").folderId()); assertEquals("home", api.get("notes").folderId()); assertEquals(1, api.batches);
            assertEquals(0, api.uploads); assertArrayEquals(original, api.get("plan").content());
            click("Back to editor");
            scenario.onActivity(activity -> {
                assertSame(doc, app.current()); assertTrue(doc.buffer.dirty()); assertTrue(doc.buffer.text().contains("Unsaved note"));
                assertEquals(doc.buffer.text(), ((CodeEditor) activity.findViewById(R.id.editor)).getText().toString());
                doc.buffer.undo(); assertFalse(doc.buffer.text().contains("Unsaved note")); doc.buffer.redo(); app.refresh();
            });
            click("Browse shared files"); idle(); click("Actions for file Plan.txt"); textButton("Rename shared file"); name("Rename shared file", "Alpha.txt"); idle();
            assertEquals("Alpha.txt", api.get("plan").name()); assertArrayEquals(original, api.get("plan").content());
            assertTrue(doc.buffer.text().contains("Unsaved note")); assertEquals("Plan.txt", doc.name);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try (var screenshot = new java.io.FileOutputStream(new java.io.File(app.getCacheDir(), "library-preview.png"))) {
                var bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(bitmap); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, screenshot); bitmap.recycle();
            }
            click("Current folder actions"); textButton("Delete empty folder");
            waitFor(() -> app.library.error.contains("files and subfolders")); assertEquals(0, api.deletes);
            click("Open folder: Draft notes"); click("Current folder actions"); textButton("Delete empty folder"); textButton("Delete"); idle();
            assertFalse(api.folders.containsKey(draft)); assertEquals("home", app.library.folderId);
            api.failMoves = true;
            click("Select Alpha.txt"); click("Select Notes.txt"); click("Move selected files"); click("Top level destination"); textButton("Move here"); idle();
            assertEquals(2, app.library.selected.size()); assertEquals("home", api.get("plan").folderId());
            assertTrue(app.library.error.contains("no longer exists"));
            assertArrayEquals(original, app.store.read(local)); assertEquals(0, api.uploads);
            scenario.recreate(); waitFor(() -> app.filesVisible && app.library.loaded);
            scenario.onActivity(activity -> { assertEquals(View.VISIBLE, activity.findViewById(R.id.library_browser).getVisibility()); assertSame(doc, app.current()); });
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 4").close();
            waitFor(() -> app.filesVisible && app.library.folderId == null);
            InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 4").close();
            waitFor(() -> !app.filesVisible); assertSame(doc, app.current()); assertTrue(doc.buffer.dirty());
        }
    }

    @Test public void creatingOpeningSavingAndOfflineReopeningUseStableLocalCopies() throws Exception {
        api.putFolder("home", "Home", null);
        try (var scenario = launch()) {
            click("Browse shared files"); waitFor(() -> app.library.loaded); idle(); click("Open folder: Home");
            click("New shared file"); name("New file", "Created.txt"); waitFor(() -> app.current() != null && !app.filesVisible); idle();
            EditorDocument doc = app.current(); assertTrue(app.store.managed(doc.uri)); assertTrue(doc.writable); assertTrue(doc.syncEnabled);
            assertEquals("home", api.get(doc.syncId).folderId());
            scenario.onActivity(activity -> { ((CodeEditor) activity.findViewById(R.id.editor)).getText().append("Saved café 😀\n"); app.save(doc, null); });
            waitFor(() -> !doc.saving && !app.sync.busy && api.files.get(doc.syncId).size() > 0);
            assertEquals("Saved café 😀\n", TextCodec.decode(api.get(doc.syncId).content()).text());
            click("Browse shared files"); idle(); app.io.submit(() -> {}).get(30, TimeUnit.SECONDS);
            String id = doc.syncId; Uri uri = doc.uri;
            api.offline = true;
            main(() -> { app.tabs.clear(); app.selectedTab = -1; app.library = new LibraryManager(app); app.filesVisible = true; });
            scenario.recreate(); waitFor(() -> app.library.loaded && !app.library.loading); assertEquals("home", app.library.folderId);
            assertFalse(app.library.connected); assertEquals(1, app.library.childFiles("home").size());
            click("Open file: Created.txt"); waitFor(() -> app.current() != null && !app.filesVisible);
            assertEquals(id, app.current().syncId); assertEquals(uri, app.current().uri); assertTrue(app.current().writable);
            assertEquals("Created.txt", app.current().name); assertEquals("Saved café 😀\n", app.current().buffer.text());
            assertEquals("Saved café 😀\n", TextCodec.decode(app.store.read(uri)).text());
        }
    }

    @Test public void staleCatalogResponsesCannotUndoAnAcknowledgedFolderChange() throws Exception {
        api.seed("notes", "Notes.txt", bytes("Saved\n"), null);
        main(app.library::refresh); waitFor(() -> app.library.loaded && !app.library.loading);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); api.entered = entered; api.release = release;
        main(app.library::refresh); assertTrue(entered.await(10, TimeUnit.SECONDS));
        try {
            main(() -> app.library.createFolder("New folder")); waitFor(() -> !app.library.busy && app.library.folders.size() == 1);
            assertEquals("New folder", app.library.folders.get(0).name());
        } finally { release.countDown(); }
        waitFor(() -> !app.library.loading); assertEquals(1, app.library.folders.size()); assertEquals(1, app.library.files.size());
        assertEquals("New folder", app.library.folders.get(0).name());
    }
}
