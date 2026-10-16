package org.hopto.git23069.textpad;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The editing session lives only as long as this process, including across screen rotation. */
public final class TextpadApplication extends Application {
    interface Listener { void refresh(); void event(Event event); }
    record Event(String title, String message, Conflict conflict) {}
    record Conflict(EditorDocument document, Uri target, DocumentBuffer.Snapshot snapshot, byte[] expected, Long closeTab, boolean move) {
        Conflict(EditorDocument document, Uri target, DocumentBuffer.Snapshot snapshot, byte[] expected, Long closeTab) {
            this(document, target, snapshot, expected, closeTab, false);
        }
    }
    record PendingSave(EditorDocument document, Long closeTab, boolean existing, boolean move) {
        PendingSave(EditorDocument document, Long closeTab, boolean existing) {
            this(document, closeTab, existing, false);
        }
    }
    final List<EditorTab> tabs = new ArrayList<>();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final Deque<Event> events = new ArrayDeque<>();
    Listener listener;
    DocumentStore store;
    EditorSettings settings;
    SyncManager sync;
    long selectedTab = -1, nextTab = 1;
    int untitled = 1;
    boolean opening;
    PendingSave pendingSave;
    TextCodec.Encoding nextOpenEncoding;

    @Override public void onCreate() {
        super.onCreate(); store = new DocumentStore(this); settings = new EditorSettings(this); sync = new SyncManager(this);
        main.post(sync::initialize);
    }
    EditorTab currentTab() { return tabs.stream().filter(t -> t.id == selectedTab).findFirst().orElse(null); }
    EditorDocument current() { EditorTab tab = currentTab(); return tab == null ? null : tab.document; }
    void attach(Listener next) {
        listener = next; next.refresh();
        if (!events.isEmpty()) main.post(this::deliverNext);
    }
    void detach(Listener old) { if (listener == old) listener = null; }
    void refresh() { if (listener != null) listener.refresh(); }
    void notice(String title, String message) { emit(new Event(title, message, null)); }
    void emit(Event event) { events.addLast(event); deliverNext(); }
    void deliverNext() { if (listener != null && !events.isEmpty()) listener.event(events.removeFirst()); }
    void addTab(EditorDocument doc) { EditorTab tab = new EditorTab(nextTab++, doc); tabs.add(tab); selectedTab = tab.id; refresh(); }
    void newDocument() { addTab(new EditorDocument("Untitled " + untitled++ + ".txt", DocumentBuffer.empty())); }
    void duplicate() { if (current() != null) addTab(current()); }
    void close(long tabId) {
        int index = -1;
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id == tabId) index = i;
        if (index < 0) return;
        tabs.remove(index);
        if (selectedTab == tabId) selectedTab = tabs.isEmpty() ? -1 : tabs.get(Math.min(index, tabs.size() - 1)).id;
        refresh();
    }

    void open(Uri uri) {
        open(uri, null);
    }
    void open(Uri uri, TextCodec.Encoding explicitEncoding) {
        // Opening the same URI again intentionally adds another view of its shared buffer.
        for (EditorTab tab : tabs) if (DocumentStore.same(uri, tab.document.uri)) { addTab(tab.document); return; }
        if (opening) { notice("Opening a file", "Please wait until the current file has opened."); return; }
        opening = true; refresh();
        io.execute(() -> {
            try {
                DocumentStore.Info info = store.info(uri);
                byte[] bytes = store.read(uri);
                DocumentBuffer buffer = new DocumentBuffer(TextCodec.decode(bytes, explicitEncoding));
                EditorDocument doc = new EditorDocument(info.name(), buffer);
                doc.uri = uri; doc.writable = info.writable(); doc.savedHash = SaveTransaction.hash(bytes);
                var prefs = getSharedPreferences("sync", 0); String key = store.key(uri);
                doc.syncId = prefs.getString(key + ".id", null);
                doc.syncEnabled = prefs.getBoolean(key + ".enabled", false);
                doc.serverRevision = prefs.getString(key + ".revision", null);
                String hash = prefs.getString(key + ".hash", null);
                if (hash != null) doc.syncedHash = java.util.HexFormat.of().parseHex(hash);
                store.remember(uri, info.name());
                main.post(() -> {
                    opening = false;
                    for (EditorTab existing : tabs) if (DocumentStore.same(existing.document.uri, uri)) { addTab(existing.document); return; }
                    addTab(doc);
                });
            } catch (Exception e) { main.post(() -> { opening = false; refresh(); notice("Could not open file", e.getMessage()); }); }
        });
    }

    void save(EditorDocument doc, Long closeTab) {
        if (doc.saving) return;
        if (doc.uri == null || !doc.writable) {
            pendingSave = new PendingSave(doc, closeTab, false);
            notice("save-as", ""); return;
        }
        saveTo(doc, doc.uri, doc.savedHash, null, closeTab);
    }

    void saveAsResult(Uri uri) {
        PendingSave pending = pendingSave; pendingSave = null;
        if (pending == null || uri == null) return;
        EditorDocument doc = pending.document;
        for (EditorTab tab : tabs) {
            if (tab.document != doc && DocumentStore.same(uri, tab.document.uri) && tab.document.buffer.dirty()) {
                notice("File already open", "The destination has unsaved edits in another tab. Save or close that document before overwriting it.");
                return;
            }
        }
        doc.saving = true; refresh();
        DocumentBuffer.Content content = doc.buffer.capture();
        io.execute(() -> {
            try {
                byte[] existing = store.read(uri);
                DocumentBuffer.Snapshot snapshot = content.encode();
                byte[] expected = SaveTransaction.hash(existing);
                main.post(() -> {
                    doc.saving = false;
                    if (pending.existing) emit(new Event("Overwrite existing file?", "Replace the selected file with this document? The existing contents will be backed up.",
                            new Conflict(doc, uri, snapshot, expected, pending.closeTab, pending.move)));
                    else saveTo(doc, uri, expected, snapshot, pending.closeTab, pending.move);
                });
            } catch (Exception e) { main.post(() -> { doc.saving = false; refresh(); notice("Could not save file", e.getMessage()); }); }
        });
    }

    void saveTo(EditorDocument doc, Uri target, byte[] expected, DocumentBuffer.Snapshot supplied, Long closeTab) {
        saveTo(doc, target, expected, supplied, closeTab, false);
    }
    void saveTo(EditorDocument doc, Uri target, byte[] expected, DocumentBuffer.Snapshot supplied, Long closeTab, boolean move) {
        if (doc.saving) return;
        for (EditorTab tab : tabs) {
            if (tab.document != doc && DocumentStore.same(target, tab.document.uri)) {
                notice("Destination already open", "Close the destination document's tabs before replacing it with another document. Its edits will stay intact.");
                return;
            }
        }
        Uri oldUri = doc.uri;
        boolean moving = move && oldUri != null && !DocumentStore.same(target, oldUri);
        doc.saving = true; refresh();
        DocumentBuffer.Content content = supplied == null ? doc.buffer.capture() : null;
        io.execute(() -> {
            DocumentBuffer.Snapshot snapshot = supplied;
            try {
                if (snapshot == null) snapshot = content.encode();
                final DocumentBuffer.Snapshot saved = snapshot;
                byte[] hash = SaveTransaction.save(store.target(target), expected, saved.bytes(), bytes -> store.backup(target, store.info(target).name(), bytes));
                DocumentStore.Info info = store.info(target);
                boolean deletedOld = !moving || store.delete(oldUri);
                store.remember(target, info.name());
                main.post(() -> {
                    boolean changedUri = !DocumentStore.same(target, oldUri);
                    // A second open document at this destination must become another view of this buffer.
                    for (EditorTab tab : tabs) if (DocumentStore.same(target, tab.document.uri)) tab.document = doc;
                    doc.uri = target; doc.name = info.name(); doc.savedHash = hash; doc.writable = info.writable();
                    doc.buffer.markSaved(saved.state()); doc.saving = false;
                    if (changedUri) {
                        if (moving && doc.syncEnabled && doc.syncId != null) sync.move(oldUri, target, info.name(), doc);
                        else { doc.syncEnabled = false; doc.syncId = null; doc.serverRevision = null; doc.syncedHash = null; }
                    }
                    if (doc.pendingDownload != null) {
                        RemoteApi.Remote remote = doc.pendingDownload; doc.pendingDownload = null;
                        sync.link(doc, remote.id(), remote.revision(), remote.hash());
                        if (tabs.stream().noneMatch(tab -> tab.document == doc)) addTab(doc);
                    }
                    if (moving && !deletedOld) {
                        notice("Moved with warning", "Saved to the new location, but the document provider did not allow deleting the previous file.");
                    }
                    if (closeTab != null && !doc.buffer.dirty()) close(closeTab);
                    refresh();
                    if (doc.syncEnabled) {
                        if(sync.testApi==null) SyncScheduler.retry(this);
                        sync.syncAll(false);
                    }
                });
            } catch (SaveTransaction.Changed e) {
                final DocumentBuffer.Snapshot captured = snapshot;
                main.post(() -> { doc.saving = false; refresh(); emit(new Event("File changed elsewhere", e.getMessage()
                        + " Overwrite it with your saved version? The displaced contents will be backed up.",
                        new Conflict(doc, target, captured, e.actualHash, closeTab, move))); });
            } catch (Exception e) { main.post(() -> { doc.saving = false; refresh(); notice("Could not save file", e.getMessage()); }); }
        });
    }

    void restoreBackup(DocumentStore.Backup backup) {
        io.execute(() -> {
            try {
                byte[] bytes = Files.readAllBytes(backup.content().toPath());
                EditorDocument doc = new EditorDocument("Restored " + backup.originalName(), new DocumentBuffer(TextCodec.decode(bytes)));
                main.post(() -> addTab(doc));
            } catch (Exception e) { main.post(() -> notice("Could not open backup", e.getMessage())); }
        });
    }
}
