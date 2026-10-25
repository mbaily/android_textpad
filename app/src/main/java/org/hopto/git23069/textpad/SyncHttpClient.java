package org.hopto.git23069.textpad;

import android.util.Base64;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import org.hopto.git23069.textpad.core.SyncEndpoint;
import org.hopto.git23069.textpad.core.Folder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;

final class SyncHttpClient implements RemoteApi {
    static final String DEFAULT_ENDPOINT = SyncEndpoint.DEFAULT;
    private final String endpoint, token, clientId;
    SyncHttpClient(String endpoint, String token) { this(endpoint, token, null); }
    SyncHttpClient(String endpoint, String token, String clientId) {
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Use an HTTPS server address, without credentials, a query, or a fragment.");
        this.endpoint = endpoint.replaceAll("/+$", ""); this.token = token; this.clientId = clientId;
    }
    @Override public List<Remote> list() throws IOException { return array(request("GET", "/api/v1/documents",null)); }
    @Override public Remote get(String id) throws IOException { return remote(request("GET",path(id),null),true); }
    @Override public List<Remote> revisions(String id) throws IOException { return array(request("GET",path(id)+"/revisions",null)); }
    @Override public Remote revision(String id, String rev) throws IOException { return remote(request("GET",path(id)+"/revisions/"+segment(rev),null),true); }
    @Override public List<Remote> prune(String id) throws IOException { return array(request("POST",path(id)+"/prune",new JSONObject())); }
    @Override public Remote put(String id, String name, byte[] bytes, String baseRevision, String requestId) throws IOException {
        try {
            String normalizedName = Normalizer.normalize(name, Normalizer.Form.NFC);
            JSONObject body = new JSONObject().put("name",normalizedName).put("content_base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
                    .put("base_revision",baseRevision == null ? JSONObject.NULL : baseRevision).put("request_id",requestId);
            return remote(request("PUT",path(id),body),false);
        } catch (JSONException e) { throw new IOException("Could not encode the sync request.",e); }
    }
    @Override public Errands getErrands() throws IOException {
        return parseErrands(request("GET", "/api/v1/errands", null));
    }
    @Override public Errands putErrands(List<String> hashtags) throws IOException {
        try {
            JSONArray arr = new JSONArray();
            if (hashtags != null) for (String t : hashtags) arr.put(t);
            JSONObject body = new JSONObject().put("hashtags", arr);
            return parseErrands(request("PUT", "/api/v1/errands", body));
        } catch (JSONException e) { throw new IOException("Could not encode the errands request.", e); }
    }
    private Errands parseErrands(JSONObject json) {
        List<String> tags = new ArrayList<>();
        JSONArray arr = json.optJSONArray("hashtags");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isBlank()) tags.add(s);
            }
        }
        return new Errands(tags, json.optString("updated_at", ""));
    }
    @Override public List<TagGroup> getTagGroups() throws IOException {
        try {
            JSONArray array = request("GET", "/api/v1/tag-groups", null).getJSONArray("groups");
            List<TagGroup> groups = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) groups.add(parseTagGroup(array.getJSONObject(i)));
            return groups;
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("The server returned invalid tag groups.", e); }
    }

    @Override public TagGroup putTagGroup(TagGroup group) throws IOException {
        try {
            TagGroup saved = parseTagGroup(request("PUT", "/api/v1/tag-groups/" + segment(group.id()),
                    new JSONObject().put("name", group.name()).put("hashtags", new JSONArray(group.hashtags()))));
            if (!saved.id().equals(group.id()) || !saved.name().equals(group.name()) || !saved.hashtags().equals(group.hashtags()))
                throw new IOException("The server did not save the complete tag group. Your local changes are retained.");
            return saved;
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("The server returned an invalid tag group.", e); }
    }

    @Override public void deleteTagGroup(String id) throws IOException {
        try {
            if (!request("DELETE", "/api/v1/tag-groups/" + segment(id), null).getBoolean("deleted"))
                throw new IOException("The server did not delete the tag group.");
        } catch (JSONException e) { throw new IOException("The server returned an invalid tag group deletion.", e); }
    }

    private TagGroup parseTagGroup(JSONObject json) throws JSONException {
        JSONArray array = json.getJSONArray("hashtags");
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) tags.add(array.getString(i));
        return new TagGroup(segment(json.getString("id")), TagGroup.normalizeName(json.getString("name")),
                tags, json.getString("updated_at"), false, false, "");
    }

    @Override public List<Folder> folders() throws IOException {
        try {
            JSONArray array = request("GET", "/api/v1/folders", null).getJSONArray("folders");
            List<Folder> folders = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) folders.add(parseFolder(array.getJSONObject(i)));
            return folders;
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("The server returned invalid folders.", e); }
    }

    @Override public Folder folder(String id) throws IOException {
        try { return parseFolder(request("GET", "/api/v1/folders/" + segment(id), null)); }
        catch (JSONException | IllegalArgumentException e) { throw new IOException("The server returned an invalid folder.", e); }
    }

    @Override public Folder putFolder(String id, String name, String parentId) throws IOException {
        try {
            String parent = parentId == null ? null : segment(parentId);
            return parseFolder(request("PUT", "/api/v1/folders/" + segment(id), new JSONObject()
                    .put("name", name).put("parent_id", parent == null ? JSONObject.NULL : parent)));
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("The server returned an invalid folder.", e); }
    }

    @Override public void deleteFolder(String id) throws IOException {
        try {
            if (!request("DELETE", "/api/v1/folders/" + segment(id), null).getBoolean("deleted"))
                throw new IOException("The server did not delete the folder.");
        } catch (JSONException e) { throw new IOException("The server returned an invalid folder deletion.", e); }
    }

    @Override public Remote moveDocument(String id, String folderId) throws IOException {
        try {
            return remote(request("PUT", path(id) + "/folder", new JSONObject()
                    .put("folder_id", folderId == null ? JSONObject.NULL : segment(folderId))), false);
        } catch (JSONException e) { throw new IOException("Could not encode the folder request.", e); }
    }

    @Override public List<Remote> moveDocuments(List<String> ids, String folderId) throws IOException {
        try {
            JSONArray selected = new JSONArray();
            for (String id : ids) selected.put(segment(id));
            List<Remote> moved = array(request("POST", "/api/v1/library/move", new JSONObject().put("document_ids", selected)
                    .put("folder_id", folderId == null ? JSONObject.NULL : segment(folderId))));
            var acknowledged = new java.util.HashSet<>(moved.stream().map(Remote::id).toList());
            if (moved.size() != ids.size() || !acknowledged.equals(new java.util.HashSet<>(ids))
                    || moved.stream().anyMatch(file -> !java.util.Objects.equals(folderId, file.folderId())))
                throw new IOException("The server did not confirm every selected file's destination. Refresh the library before retrying.");
            return moved;
        } catch (JSONException e) { throw new IOException("Could not encode the selected files.", e); }
    }

    @Override public Remote renameDocument(String id, String name, String baseRevision, String requestId) throws IOException {
        try {
            return remote(request("PUT", path(id) + "/name", new JSONObject().put("name", name)
                    .put("base_revision", segment(baseRevision)).put("request_id", segment(requestId))), false);
        } catch (JSONException e) { throw new IOException("Could not encode the filename.", e); }
    }

    @Override public Folder renameFolder(String id, String name) throws IOException {
        try { return parseFolder(request("PUT", "/api/v1/folders/" + segment(id), new JSONObject().put("name", name))); }
        catch (JSONException e) { throw new IOException("Could not encode the folder name.", e); }
    }

    @Override public Remote createDocument(String id, String name, byte[] bytes, String requestId, String folderId) throws IOException {
        try {
            Remote saved = remote(request("PUT", path(id), new JSONObject().put("name", name)
                    .put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("base_revision", JSONObject.NULL)
                    .put("request_id", segment(requestId)).put("folder_id", folderId == null ? JSONObject.NULL : segment(folderId))), false);
            if (!saved.id().equals(id) || !saved.hash().equals(HexFormat.of().formatHex(SaveTransaction.hash(bytes)))
                    || saved.size() != bytes.length || !java.util.Objects.equals(folderId, saved.folderId()))
                throw new IOException("The server did not save this file in its selected folder. Refresh the library before retrying.");
            return saved;
        } catch (JSONException e) { throw new IOException("Could not encode the new file.", e); }
    }

    static Folder parseFolder(JSONObject json) throws JSONException {
        return new Folder(json.getString("id"), json.getString("name"),
                json.isNull("parent_id") ? null : json.getString("parent_id"),
                json.getString("created_at"), json.getString("updated_at"));
    }

    private String path(String id) { return "/api/v1/documents/"+segment(id); }
    private static String segment(String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid server document or revision ID.");
        return value;
    }
    private List<Remote> array(JSONObject json) throws IOException {
        try {
            List<Remote> results = new ArrayList<>(); JSONArray items = json.getJSONArray("documents");
            for (int i = 0; i < items.length(); i++) results.add(remote(items.getJSONObject(i),false));
            return results;
        } catch (JSONException e) { throw new IOException("The server returned an invalid document list.",e); }
    }
    static Remote remote(JSONObject json, boolean withContent) throws IOException {
        try {
            String id = segment(json.getString("id")), rev = segment(json.getString("revision")), hash = json.getString("sha256");
            long size = json.getLong("size");
            if (!hash.matches("[a-f0-9]{64}") || size < 0 || size > TextCodec.MAX_BYTES) throw new IOException("The server returned invalid file metadata.");
            byte[] bytes = withContent ? Base64.decode(json.getString("content_base64"),Base64.DEFAULT) : null;
            if (bytes != null && (bytes.length != size || !HexFormat.of().formatHex(SaveTransaction.hash(bytes)).equals(hash)))
                throw new IOException("Downloaded contents did not match the server checksum.");
            String folderId = json.isNull("folder_id") ? null : segment(json.getString("folder_id"));
            return new Remote(id,json.getString("name"),rev,hash,size,bytes,json.optString("source",""),json.optBoolean("replaced_newer_revision",false),folderId);
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("The server returned an invalid document.",e); }
    }
    private JSONObject request(String method, String path, JSONObject body) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(endpoint+path).toURL().openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization","Bearer "+token); connection.setRequestProperty("Accept","application/json");
        connection.setRequestProperty("Accept-Encoding","gzip");
        if (clientId != null && !clientId.isBlank()) connection.setRequestProperty("X-Textpad-Client",clientId);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type","application/json");
                try (var out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new IOException("The server rejected the API token. Check Sync settings.");
            if (status == 404 && path.equals("/api/v1/documents"))
                throw new IOException("No Textpad sync API was found at "+endpoint+" (HTTP 404). Check Sync → Actions → Server settings. "
                        +"For git23069.hopto.org, use "+DEFAULT_ENDPOINT+".");
            if (status < 200 || status >= 300) {
                String detail = "Sync server returned HTTP " + status + ".";
                try (InputStream rawError = connection.getErrorStream()) {
                    if (rawError != null) {
                        InputStream error = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(rawError) : rawError;
                        String message = new JSONObject(new String(error.readNBytes(65536), StandardCharsets.UTF_8)).optString("error", "");
                        if (!message.isBlank() && message.length() <= 2000) detail = message;
                    }
                } catch (IOException | JSONException ignored) { }
                throw new IOException(detail);
            }
            try (InputStream rawIn = connection.getInputStream();
                 InputStream in = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(rawIn) : rawIn;
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] block = new byte[32768]; int n;
                while ((n = in.read(block)) != -1) {
                    if (out.size()+n > 16*1024*1024) throw new IOException("The server response is too large.");
                    out.write(block,0,n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8));
            } catch (JSONException e) { throw new IOException("The server response was not valid JSON.",e); }
        } finally { connection.disconnect(); }
    }
}
