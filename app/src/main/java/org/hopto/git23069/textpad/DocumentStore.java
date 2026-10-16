package org.hopto.git23069.textpad;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class DocumentStore {
    record Info(String name, boolean writable) {}
    record Recent(String name, Uri uri) {}
    record Backup(String name, File content, File metadata, String originalName, String originalUri) {}
    private final Context context;
    private final ContentResolver resolver;
    DocumentStore(Context context) { this.context = context; resolver = context.getContentResolver(); }

    String location(Uri uri, String name) {
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
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        String id = stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        File content = new File(folder, id + ".bin"), metadata = new File(folder, id + ".json");
        try (FileOutputStream out = new FileOutputStream(content)) { out.write(bytes); out.getFD().sync(); }
        try {
            JSONObject json = new JSONObject().put("name", name).put("uri", uri.toString()).put("created", System.currentTimeMillis());
            try (FileOutputStream out = new FileOutputStream(metadata)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
        } catch (JSONException e) { throw new IOException("Could not write backup metadata.", e); }
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
                        content, metadata, json.getString("name"), json.getString("uri")));
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
    private static String identity(Uri uri) {
        try { return uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri); }
        catch (IllegalArgumentException e) { return uri.normalizeScheme().toString(); }
    }
    String key(Uri uri) { return HexFormat.of().formatHex(SaveTransaction.hash(identity(uri).getBytes(StandardCharsets.UTF_8))); }
}
