package org.hopto.git23069.textpad;

import android.net.Uri;
import android.util.AtomicFile;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.Folder;
import org.hopto.git23069.textpad.core.FolderTree;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Main-thread library state. Catalog reads never wait for content sync or uploads. */
final class LibraryManager {
    private record Catalog(List<Folder> folders, List<RemoteApi.Remote> files) {}
    private interface Query<T> { T run(RemoteApi api) throws Exception; }
    private final TextpadApplication app;
    private final ExecutorService reads = Executors.newFixedThreadPool(2);
    List<Folder> folders = List.of();
    List<RemoteApi.Remote> files = List.of();
    FolderTree tree = new FolderTree(List.of());
    final Set<String> selected = new LinkedHashSet<>();
    String folderId, filter = "", error = "";
    boolean loaded, loading, busy, connected;
    private String scope;
    private long generation;
    private boolean refreshAgain;

    LibraryManager(TextpadApplication app) { this.app = app; ensureScope(); }

    private String serverKey() {
        String endpoint = app.sync.prefs.getString("endpoint", SyncHttpClient.DEFAULT_ENDPOINT).replaceAll("/+$", "");
        return HexFormat.of().formatHex(SaveTransaction.hash(endpoint.getBytes(StandardCharsets.UTF_8)));
    }
    void ensureScope() {
        String next = serverKey();
        if (next.equals(scope)) return;
        scope = next; generation++; loaded = false; connected = false; loading = false; busy = false;
        folders = List.of(); files = List.of(); tree = new FolderTree(folders); selected.clear(); filter = ""; error = "";
        folderId = app.getSharedPreferences("library", 0).getString(scope + ".folder", null);
        if (folderId != null && !folderId.matches("[A-Za-z0-9_-]{1,128}")) folderId = null;
        String requestedScope = scope;
        long requestedGeneration = generation;
        reads.execute(() -> {
            try {
                Catalog cached = readCache(requestedScope);
                app.main.post(() -> {
                    if (!loaded && requestedScope.equals(scope) && requestedGeneration == generation) {
                        apply(cached); loaded = true; app.refresh();
                    }
                });
            } catch (Exception ignored) { }
        });
    }
    void selectFolder(String id) {
        folderId = id; filter = ""; selected.clear();
        app.getSharedPreferences("library", 0).edit().putString(scope + ".folder", id).apply();
        app.refresh();
    }
    String pathLabel(String id) {
        List<String> names = new ArrayList<>(); names.add("Top level");
        for (Folder folder : tree.path(id)) names.add(folder.name());
        return String.join(" / ", names);
    }
    List<RemoteApi.Remote> childFiles(String parent) {
        List<RemoteApi.Remote> children = new ArrayList<>();
        for (var file : files) {
            String location = tree.get(file.folderId()) == null ? null : file.folderId();
            if (Objects.equals(parent, location)) children.add(file);
        }
        Collator order = Collator.getInstance(); order.setStrength(Collator.PRIMARY);
        children.sort((a, b) -> { int value = order.compare(a.name(), b.name()); return value == 0 ? a.id().compareTo(b.id()) : value; });
        return children;
    }
    void refresh() {
        ensureScope();
        if (loading || busy) { refreshAgain = true; return; }
        final RemoteApi api;
        try { api = app.sync.api(); }
        catch (Exception e) { connected = false; error = SyncManager.message(e); app.refresh(); return; }
        loading = true; error = ""; app.refresh();
        long ticket = generation;
        String requestedScope = scope;
        reads.execute(() -> {
            try {
                Catalog result = new Catalog(List.copyOf(api.folders()), metadata(api.list()));
                app.main.post(() -> {
                    if (!requestedScope.equals(scope)) return;
                    loading = false;
                    if (ticket == generation) {
                        apply(result); loaded = true; connected = true; error = "";
                        if (folderId != null && tree.get(folderId) == null) selectFolder(null);
                        persist();
                    } else refreshAgain = true;
                    app.refresh(); finishRefresh();
                });
            } catch (Exception e) {
                app.main.post(() -> {
                    if (!requestedScope.equals(scope)) return;
                    loading = false;
                    if (ticket == generation) { connected = false; error = SyncManager.message(e); }
                    app.refresh(); finishRefresh();
                });
            }
        });
    }
    private void finishRefresh() { if (refreshAgain && !busy) { refreshAgain = false; refresh(); } }
    private void apply(Catalog catalog) {
        folders = List.copyOf(catalog.folders()); files = metadata(catalog.files()); tree = new FolderTree(folders);
        Set<String> available = new LinkedHashSet<>(); for (var file : childFiles(folderId)) available.add(file.id());
        selected.retainAll(available);
    }
    private static List<RemoteApi.Remote> metadata(List<RemoteApi.Remote> input) {
        return input.stream().map(file -> new RemoteApi.Remote(file.id(), file.name(), file.revision(), file.hash(), file.size(),
                null, file.source(), file.replacedNewerRevision(), file.folderId())).toList();
    }
    private void updateFiles(List<RemoteApi.Remote> updated) {
        List<RemoteApi.Remote> next = new ArrayList<>(files);
        for (var file : updated) { next.removeIf(old -> old.id().equals(file.id())); next.add(file); }
        files = metadata(next);
    }
    private <T> void mutate(Query<T> action, Consumer<T> accepted) {
        if (busy) return;
        if (app.sync.busy) { error = "Wait for the current file sync to finish, then retry."; app.refresh(); return; }
        final RemoteApi api;
        try { api = app.sync.api(); }
        catch (Exception e) { error = SyncManager.message(e); app.refresh(); return; }
        busy = true; error = ""; generation++; app.refresh();
        String requestedScope = scope;
        app.io.execute(() -> {
            try {
                T result = action.run(api);
                app.main.post(() -> {
                    if (!requestedScope.equals(scope)) return;
                    generation++; busy = false; connected = true; loaded = true;
                    accepted.accept(result); persist(); app.refresh(); refresh();
                });
            } catch (Exception e) {
                app.main.post(() -> {
                    if (!requestedScope.equals(scope)) return;
                    busy = false; error = SyncManager.message(e); app.refresh(); finishRefresh();
                });
            }
        });
    }
    void createFolder(String name) {
        createFolder(name, folderId);
    }
    void createFolder(String name, String parent) {
        String id = UUID.randomUUID().toString();
        mutate(api -> api.putFolder(id, name, parent), saved -> {
            List<Folder> next = new ArrayList<>(folders); next.removeIf(f -> f.id().equals(saved.id())); next.add(saved);
            folders = List.copyOf(next); tree = new FolderTree(folders); selectFolder(saved.id());
        });
    }
    void renameFolder(Folder folder, String name) {
        mutate(api -> api.renameFolder(folder.id(), name), saved -> {
            List<Folder> next = new ArrayList<>(folders); next.removeIf(f -> f.id().equals(saved.id())); next.add(saved);
            folders = List.copyOf(next); tree = new FolderTree(folders);
        });
    }
    void moveFolder(Folder folder, String parent) {
        mutate(api -> api.putFolder(folder.id(), api.folder(folder.id()).name(), parent), saved -> {
            List<Folder> next = new ArrayList<>(folders); next.removeIf(f -> f.id().equals(saved.id())); next.add(saved);
            folders = List.copyOf(next); tree = new FolderTree(folders); selectFolder(saved.id());
        });
    }
    void deleteFolder(Folder folder) {
        mutate(api -> { api.deleteFolder(folder.id()); return folder; }, deleted -> {
            folders = folders.stream().filter(f -> !f.id().equals(deleted.id())).toList(); tree = new FolderTree(folders);
            if (Objects.equals(folderId, deleted.id())) selectFolder(deleted.parentId());
        });
    }
    boolean pending(String id) {
        for (var binding : app.sync.bindings()) if (id.equals(binding.id()) && app.sync.prefs.contains(binding.key() + ".request")) return true;
        for (var tab : app.tabs) if (id.equals(tab.document.syncId) && (tab.document.saving || tab.document.replacing)) return true;
        return false;
    }
    void moveFiles(List<String> ids, String destination) {
        if (ids.isEmpty()) return;
        for (String id : ids) if (pending(id)) { error = "Finish saving and syncing the selected files before moving them."; app.refresh(); return; }
        List<String> selection = List.copyOf(ids);
        mutate(api -> api.moveDocuments(selection, destination), moved -> {
            updateFiles(moved); selected.clear(); selectFolder(destination);
        });
    }
    void renameFile(RemoteApi.Remote file, String name) {
        if (pending(file.id())) { error = "Finish syncing this file before renaming it."; app.refresh(); return; }
        String request = UUID.randomUUID().toString();
        mutate(api -> {
            RemoteApi.Remote current = api.get(file.id());
            RemoteApi.Remote renamed = api.renameDocument(file.id(), name, current.revision(), request);
            for (var binding : app.sync.bindings()) if (file.id().equals(binding.id())) {
                var edit = app.sync.prefs.edit().putString(binding.key() + ".name", renamed.name());
                if (Objects.equals(binding.hash(), renamed.hash())) edit.putString(binding.key() + ".revision", renamed.revision());
                if (!edit.commit()) throw new IOException("The file was renamed. Refresh the library before retrying.");
            }
            return renamed;
        }, renamed -> {
            updateFiles(List.of(renamed)); filter = "";
            for (var tab : app.tabs) if (file.id().equals(tab.document.syncId)) {
                var doc = tab.document;
                if (app.store.managed(doc.uri)) { doc.name = renamed.name(); app.store.remember(doc.uri, doc.name); }
                if (doc.syncedHash != null && HexFormat.of().formatHex(doc.syncedHash).equals(renamed.hash())) doc.serverRevision = renamed.revision();
            }
        });
    }
    void createFile(String name) {
        createFile(name, folderId);
    }
    void createFile(String name, String parent) {
        String id = UUID.randomUUID().toString(), request = UUID.randomUUID().toString();
        mutate(api -> api.createDocument(id, name, new byte[0], request, parent), saved -> { updateFiles(List.of(saved)); open(saved); });
    }
    void open(RemoteApi.Remote file) {
        for (var tab : app.tabs) if (file.id().equals(tab.document.syncId)) {
            app.filesVisible = false; app.selectedTab = tab.id; app.refresh(); return;
        }
        for (var binding : app.sync.bindings()) if (file.id().equals(binding.id())) {
            app.focusOrOpen(binding.uri(), () -> { app.filesVisible = false; app.refresh(); }); return;
        }
        if (busy) return;
        final RemoteApi api;
        try { api = app.sync.api(); }
        catch (Exception e) { error = SyncManager.message(e); app.refresh(); return; }
        busy = true; error = ""; app.refresh();
        String requestedScope = scope;
        reads.execute(() -> {
            try {
                RemoteApi.Remote full = api.get(file.id());
                app.io.execute(() -> prepareCopy(full, requestedScope));
            } catch (Exception e) { app.main.post(() -> { busy = false; error = SyncManager.message(e); app.refresh(); }); }
        });
    }
    private void prepareCopy(RemoteApi.Remote remote, String requestedScope) {
        try {
            if (!requestedScope.equals(serverKey())) throw new IOException("The server settings changed. Reopen this file from the current library.");
            Uri uri = app.store.managedUri(requestedScope, remote.id());
            File path = new File(Objects.requireNonNull(uri.getPath()));
            String key = app.store.key(uri);
            if (path.exists()) {
                if (!app.sync.prefs.contains(key + ".hash")) throw new IOException("A local copy is already present. Open it from Recent files to preserve its saved contents.");
                app.main.post(() -> { busy = false; app.filesVisible = false; app.focusOrOpen(uri, null); app.refresh(); });
                return;
            }
            DocumentBuffer buffer = new DocumentBuffer(TextCodec.decode(remote.content()));
            if (!path.getParentFile().isDirectory() && !path.getParentFile().mkdirs()) throw new IOException("Could not prepare this file for offline use.");
            AtomicFile atomic = new AtomicFile(path);
            FileOutputStream out = atomic.startWrite();
            try { out.write(remote.content()); atomic.finishWrite(out); }
            catch (Exception e) { atomic.failWrite(out); throw e; }
            if (!requestedScope.equals(serverKey())) throw new IOException("The server settings changed. The downloaded local copy has been retained.");
            if (!app.sync.prefs.edit().putString(key + ".uri", uri.toString()).putString(key + ".name", remote.name())
                    .putString(key + ".id", remote.id()).putBoolean(key + ".enabled", true)
                    .putString(key + ".revision", remote.revision()).putString(key + ".hash", remote.hash()).commit())
                throw new IOException("Could not record the file's sync link. Its local copy has been retained.");
            EditorDocument doc = new EditorDocument(remote.name(), buffer);
            doc.uri = uri; doc.savedHash = SaveTransaction.hash(remote.content()); doc.syncEnabled = true;
            doc.syncId = remote.id(); doc.serverRevision = remote.revision(); doc.syncedHash = doc.savedHash;
            app.store.remember(uri, remote.name());
            app.main.post(() -> {
                busy = false;
                if (!requestedScope.equals(scope)) return;
                for (var tab : app.tabs) if (remote.id().equals(tab.document.syncId)) {
                    app.filesVisible = false; app.selectedTab = tab.id; app.refresh(); return;
                }
                app.addTab(doc);
            });
        } catch (Exception e) { app.main.post(() -> { busy = false; error = SyncManager.message(e); app.refresh(); }); }
    }
    boolean availableLocally(String id) { return app.sync.bindings().stream().anyMatch(binding -> id.equals(binding.id())); }

    private File cacheFile(String key) { return new File(app.getFilesDir(), "shared-library/" + key + "/catalog.json"); }
    private Catalog readCache(String key) throws Exception {
        byte[] bytes;
        try (var in = new AtomicFile(cacheFile(key)).openRead()) { bytes = in.readNBytes(16 * 1024 * 1024 + 1); }
        if (bytes.length > 16 * 1024 * 1024) throw new IOException("Library cache is too large.");
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (json.getInt("version") != 1) throw new IOException("Unknown library cache format.");
        List<Folder> cachedFolders = new ArrayList<>(); List<RemoteApi.Remote> cachedFiles = new ArrayList<>();
        JSONArray folderArray = json.getJSONArray("folders"), fileArray = json.getJSONArray("documents");
        for (int i = 0; i < folderArray.length(); i++) cachedFolders.add(SyncHttpClient.parseFolder(folderArray.getJSONObject(i)));
        for (int i = 0; i < fileArray.length(); i++) cachedFiles.add(SyncHttpClient.remote(fileArray.getJSONObject(i), false));
        return new Catalog(List.copyOf(cachedFolders), List.copyOf(cachedFiles));
    }
    private void persist() {
        Catalog catalog = new Catalog(folders, files); String key = scope;
        app.io.execute(() -> {
            try {
                JSONArray folderArray = new JSONArray(), fileArray = new JSONArray();
                for (Folder folder : catalog.folders()) folderArray.put(new JSONObject().put("id", folder.id()).put("name", folder.name())
                        .put("parent_id", folder.parentId() == null ? JSONObject.NULL : folder.parentId()).put("created_at", folder.createdAt()).put("updated_at", folder.updatedAt()));
                for (var file : catalog.files()) fileArray.put(new JSONObject().put("id", file.id()).put("name", file.name()).put("revision", file.revision())
                        .put("sha256", file.hash()).put("size", file.size()).put("folder_id", file.folderId() == null ? JSONObject.NULL : file.folderId()).put("source", file.source()));
                byte[] bytes = new JSONObject().put("version", 1).put("folders", folderArray).put("documents", fileArray).toString().getBytes(StandardCharsets.UTF_8);
                File target = cacheFile(key);
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Could not cache library.");
                AtomicFile atomic = new AtomicFile(target); FileOutputStream out = atomic.startWrite();
                try { out.write(bytes); atomic.finishWrite(out); }
                catch (Exception e) { atomic.failWrite(out); throw e; }
            } catch (Exception ignored) { }
        });
    }
}
