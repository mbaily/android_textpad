package org.hopto.git23069.textpad;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import org.hopto.git23069.textpad.core.RevisionPruner;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class DocumentStore {
    record Info(String name, boolean writable) {}
    record Recent(String name, Uri uri) {}
    record Backup(String name, File content, File metadata, String originalName, String originalUri, long created) {}
    private final Context context;
    private final ContentResolver resolver;
    DocumentStore(Context context) { this.context = context; resolver = context.getContentResolver(); }

    String location(Uri uri, String name) {
        if (managed(uri)) return "Shared library / " + name;
        String id;
        try { id = DocumentsContract.getDocumentId(uri); }
        catch (IllegalArgumentException e) { return name; }
        if (id.startsWith("raw:")) id = id.substring(4);
        if (id.startsWith("/storage/emulated/0/")) return "Internal storage / " + id.substring(20);
        if (id.startsWith("primary:")) return "Internal storage / " + id.substring(8);
        if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) return "Downloads / " + name;
        int colon = id.indexOf(':');
        if (colon > 0) return id.substring(0, colon) + " / " + id.substring(colon + 1);
        return name;
    }

    Info info(Uri uri) {
        if (uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            String name = managed(uri) ? context.getSharedPreferences("sync", 0).getString(key(uri) + ".name", file.getName()) : file.getName();
            return new Info(name, file.canWrite());
        }
        String name = uri.getLastPathSegment() == null ? "Untitled.txt" : uri.getLastPathSegment();
        boolean writable = false;
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), f = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (f >= 0 && !c.isNull(f)) writable = (c.getLong(f) & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0;
            }
        } catch (RuntimeException ignored) { /* Some providers omit document flags. */ }
        return new Info(name, writable);
    }

    byte[] read(Uri uri) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("The document provider could not open this file.");
            return readLimited(in);
        } catch (SecurityException e) { throw new IOException("File access has expired. Open the file again through the picker.", e); }
    }

    boolean managed(Uri uri) {
        return uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null
                && uri.getPath().startsWith(new File(context.getFilesDir(), "shared-library").getAbsolutePath() + "/");
    }

    Uri managedUri(String serverKey, String id) {
        if (!serverKey.matches("[a-f0-9]{64}") || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid shared file identifier.");
        return Uri.fromFile(new File(context.getFilesDir(), "shared-library/" + serverKey + "/documents/" + id + ".txt"));
    }

    static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[32 * 1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (out.size() + n > TextCodec.MAX_BYTES) throw new IOException("This file exceeds the 10 MiB limit.");
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    void write(Uri uri, byte[] bytes) throws IOException {
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "rwt")) {
            if (fd == null) throw new IOException("The document provider could not open this file for writing.");
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                out.write(bytes); out.flush();
                // Local documents support fsync. A provider that cannot commit is treated as a failed save.
                out.getFD().sync();
            }
        } catch (SecurityException e) { throw new IOException("This file is read-only or its write permission has expired. Use Save As.", e); }
    }

    SaveTransaction.Target target(Uri uri) {
        return new SaveTransaction.Target() {
            public byte[] read() throws IOException { return DocumentStore.this.read(uri); }
            public void write(byte[] bytes) throws IOException { DocumentStore.this.write(uri, bytes); }
        };
    }

    void backup(Uri uri, String name, byte[] bytes) throws IOException {
        File folder = new File(context.getFilesDir(), "backups");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create the backups directory.");
        long now = System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date(now));
        String id = stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        File content = new File(folder, id + ".bin"), metadata = new File(folder, id + ".json");
        try (FileOutputStream out = new FileOutputStream(content)) { out.write(bytes); out.getFD().sync(); }
        try {
            JSONObject json = new JSONObject().put("name", name).put("uri", uri.toString()).put("created", now);
            try (FileOutputStream out = new FileOutputStream(metadata)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
        } catch (JSONException e) { throw new IOException("Could not write backup metadata.", e); }
        pruneBackups(uri, now);
    }

    int pruneBackups(Uri targetUri, long nowMillis) throws IOException {
        List<Backup> all = backups();
        Map<String, List<Backup>> byUri = new LinkedHashMap<>();
        String filterKey = targetUri == null ? null : identity(targetUri);
        for (Backup b : all) {
            String groupKey;
            try { groupKey = identity(Uri.parse(b.originalUri)); }
            catch (Exception e) { groupKey = b.originalUri; }
            if (filterKey != null && !filterKey.equals(groupKey)) continue;
            byUri.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(b);
        }
        int removed = 0;
        for (List<Backup> group : byUri.values()) {
            if (group.size() <= RevisionPruner.RECENT_KEEP_COUNT) continue;
            long[] timestamps = new long[group.size()];
            for (int i = 0; i < group.size(); i++) timestamps[i] = group.get(i).created;
            for (int idx : RevisionPruner.selectPrunedIndices(timestamps, nowMillis)) {
                Backup victim = group.get(idx);
                Files.deleteIfExists(victim.metadata.toPath());
                Files.deleteIfExists(victim.content.toPath());
                removed++;
            }
        }
        return removed;
    }

    List<Backup> backups() throws IOException {
        File folder = new File(context.getFilesDir(), "backups");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        List<Backup> result = new ArrayList<>();
        if (files == null) return result;
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (File metadata : files) {
            try {
                JSONObject json = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
                File content = new File(folder, metadata.getName().replace(".json", ".bin"));
                if (content.isFile()) result.add(new Backup(metadata.getName().substring(0, 19) + "  ·  " + json.getString("name"),
                        content, metadata, json.getString("name"), json.getString("uri"), json.optLong("created", metadata.lastModified())));
            } catch (JSONException ignored) { /* One invalid entry must not hide the other backups. */ }
        }
        return result;
    }

    List<Recent> recents() {
        List<Recent> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(context.getSharedPreferences("files", 0).getString("recent", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new Recent(item.getString("name"), Uri.parse(item.getString("uri"))));
            }
        } catch (JSONException ignored) { }
        return result;
    }

    void remember(Uri uri, String name) {
        List<Recent> recent = recents(); recent.removeIf(r -> same(r.uri, uri)); recent.add(0, new Recent(name, uri));
        JSONArray array = new JSONArray();
        for (Recent r : recent.subList(0, Math.min(30, recent.size()))) {
            try { array.put(new JSONObject().put("name", r.name).put("uri", r.uri.toString())); }
            catch (JSONException ignored) { }
        }
        context.getSharedPreferences("files", 0).edit().putString("recent", array.toString()).apply();
    }

    void forget(Uri uri) {
        List<Recent> recent = recents();
        if (!recent.removeIf(r -> same(r.uri, uri))) return;
        JSONArray array = new JSONArray();
        for (Recent r : recent) {
            try { array.put(new JSONObject().put("name", r.name).put("uri", r.uri.toString())); }
            catch (JSONException ignored) { }
        }
        context.getSharedPreferences("files", 0).edit().putString("recent", array.toString()).apply();
    }

    boolean delete(Uri uri) {
        forget(uri);
        if (managed(uri)) {
            try { return Files.deleteIfExists(new File(uri.getPath()).toPath()); }
            catch (IOException ignored) { return false; }
        }
        try {
            if (DocumentsContract.deleteDocument(resolver, uri)) return true;
        } catch (Exception ignored) { }
        try {
            return resolver.delete(uri, null, null) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean same(Uri a, Uri b) { return a != null && b != null && identity(a).equals(identity(b)); }
    static String identity(Uri uri) {
        try { return uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri); }
        catch (IllegalArgumentException e) { return uri.normalizeScheme().toString(); }
    }
    String key(Uri uri) { return HexFormat.of().formatHex(SaveTransaction.hash(identity(uri).getBytes(StandardCharsets.UTF_8))); }

    static boolean isLikelyTextName(String name, String mime) {
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) return false;
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
                || mime.equals("application/pdf") || mime.equals("application/zip")
                || mime.equals("application/vnd.android.package-archive"))) {
            return false;
        }
        if (name == null || name.isEmpty() || name.startsWith(".")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".pdf",
                ".zip", ".tar", ".gz", ".7z", ".rar", ".apk", ".aab", ".dex", ".so", ".mp3", ".mp4",
                ".mkv", ".avi", ".mov", ".ogg", ".wav", ".flac", ".ttf", ".otf", ".woff", ".woff2", ".sqlite3", ".db"}) {
            if (lower.endsWith(ext)) return false;
        }
        return true;
    }

    static String parentDocumentId(Uri uri) {
        if (uri == null) return null;
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            int slash = docId.lastIndexOf('/');
            if (slash > 0) return docId.substring(0, slash);
            int colon = docId.indexOf(':');
            if (colon >= 0 && colon + 1 < docId.length()) return docId.substring(0, colon + 1);
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    static File localParentDirectory(Uri uri) {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).getParentFile();
        }
        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId.startsWith("raw:")) docId = docId.substring(4);
            if (docId.startsWith("/")) return new File(docId).getParentFile();
            if (docId.startsWith("primary:")) {
                File f = new File("/storage/emulated/0/" + docId.substring(8));
                return f.getParentFile();
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    List<Recent> discoverSiblingAndTreeFiles(List<Uri> openUris, List<Uri> searchTrees) {
        Map<String, Recent> found = new LinkedHashMap<>();
        java.util.Set<String> scannedDirs = new java.util.HashSet<>();

        // 1. Scan local filesystem parent directories of open files when accessible
        for (Uri openUri : openUris) {
            if (openUri == null) continue;
            File parent = localParentDirectory(openUri);
            if (parent != null && scannedDirs.add("file:" + parent.getAbsolutePath())) {
                File[] children = parent.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isFile() && child.canRead() && child.length() <= TextCodec.MAX_BYTES
                                && isLikelyTextName(child.getName(), null)) {
                            Uri u = Uri.fromFile(child);
                            found.putIfAbsent(identity(u), new Recent(managed(u) ? info(u).name() : child.getName(), u));
                        }
                    }
                }
            }
            // 2. Try SAF parent directory via matching searchTree or direct provider query
            String parentDocId = parentDocumentId(openUri);
            String authority = openUri.getAuthority();
            if (parentDocId != null && authority != null && scannedDirs.add("saf:" + authority + ":" + parentDocId)) {
                Uri matchedTree = null;
                for (Uri tree : searchTrees) {
                    if (authority.equals(tree.getAuthority())) {
                        try {
                            String treeDocId = DocumentsContract.getTreeDocumentId(tree);
                            if (parentDocId.equals(treeDocId) || parentDocId.startsWith(treeDocId)) {
                                matchedTree = tree;
                                break;
                            }
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                if (matchedTree != null) {
                    queryChildrenInto(matchedTree, parentDocId, true, found);
                } else {
                    try {
                        Uri childrenUri = DocumentsContract.buildChildDocumentsUri(authority, parentDocId);
                        queryChildrenUri(openUri, false, childrenUri, found);
                    } catch (Exception ignored) {}
                }
            }
        }

        // 3. Scan granted folder trees (top-level files)
        for (Uri tree : searchTrees) {
            if (tree == null) continue;
            try {
                String treeDocId = DocumentsContract.getTreeDocumentId(tree);
                String authority = tree.getAuthority();
                if (authority != null && treeDocId != null && scannedDirs.add("saf:" + authority + ":" + treeDocId)) {
                    queryChildrenInto(tree, treeDocId, true, found);
                }
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(found.values());
    }

    private void queryChildrenInto(Uri treeUri, String parentDocId, boolean usingTree, Map<String, Recent> found) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
            queryChildrenUri(treeUri, usingTree, childrenUri, found);
        } catch (Exception ignored) {}
    }

    private void queryChildrenUri(Uri baseUri, boolean usingTree, Uri childrenUri, Map<String, Recent> found) {
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor c = resolver.query(childrenUri, projection, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String docId = c.isNull(0) ? null : c.getString(0);
                String name = c.isNull(1) ? null : c.getString(1);
                String mime = c.isNull(2) ? null : c.getString(2);
                long size = c.isNull(3) ? 0L : c.getLong(3);
                if (docId == null || name == null || size > TextCodec.MAX_BYTES) continue;
                if (!isLikelyTextName(name, mime)) continue;
                Uri childUri = usingTree
                        ? DocumentsContract.buildDocumentUriUsingTree(baseUri, docId)
                        : DocumentsContract.buildDocumentUri(baseUri.getAuthority(), docId);
                found.putIfAbsent(identity(childUri), new Recent(name, childUri));
            }
        } catch (Exception ignored) {}
    }
}

