package org.hopto.git23069.textpad;

import android.util.Base64;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

final class SyncHttpClient implements RemoteApi {
    static final String DEFAULT_ENDPOINT = "https://git23069.hopto.org:8443";
    private final String endpoint, token;
    SyncHttpClient(String endpoint, String token) {
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Use an HTTPS server address, without credentials, a query, or a fragment.");
        this.endpoint = endpoint.replaceAll("/+$", ""); this.token = token;
    }
    @Override public List<Remote> list() throws IOException { return array(request("GET", "/api/v1/documents",null)); }
    @Override public Remote get(String id) throws IOException { return remote(request("GET",path(id),null),true); }
    @Override public List<Remote> revisions(String id) throws IOException { return array(request("GET",path(id)+"/revisions",null)); }
    @Override public Remote revision(String id, String rev) throws IOException { return remote(request("GET",path(id)+"/revisions/"+segment(rev),null),true); }
    @Override public Remote put(String id, String name, byte[] bytes, String baseRevision, String requestId) throws IOException {
        try {
            JSONObject body = new JSONObject().put("name",name).put("content_base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
                    .put("base_revision",baseRevision == null ? JSONObject.NULL : baseRevision).put("request_id",requestId);
            return remote(request("PUT",path(id),body),false);
        } catch (JSONException e) { throw new IOException("Could not encode the sync request.",e); }
    }
    private String path(String id) { return "/api/v1/documents/"+segment(id); }
    private String segment(String value) {
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
    private Remote remote(JSONObject json, boolean withContent) throws IOException {
        try {
            String id = segment(json.getString("id")), rev = segment(json.getString("revision")), hash = json.getString("sha256");
            long size = json.getLong("size");
            if (!hash.matches("[a-f0-9]{64}") || size < 0 || size > TextCodec.MAX_BYTES) throw new IOException("The server returned invalid file metadata.");
            byte[] bytes = withContent ? Base64.decode(json.getString("content_base64"),Base64.DEFAULT) : null;
            if (bytes != null && (bytes.length != size || !HexFormat.of().formatHex(SaveTransaction.hash(bytes)).equals(hash)))
                throw new IOException("Downloaded contents did not match the server checksum.");
            return new Remote(id,json.getString("name"),rev,hash,size,bytes);
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("The server returned an invalid document.",e); }
    }
    private JSONObject request(String method, String path, JSONObject body) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(endpoint+path).toURL().openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization","Bearer "+token); connection.setRequestProperty("Accept","application/json");
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type","application/json");
                try (var out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new IOException("The server rejected the API token. Check Sync settings.");
            if (status < 200 || status >= 300) throw new IOException("Sync server returned HTTP "+status+".");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
