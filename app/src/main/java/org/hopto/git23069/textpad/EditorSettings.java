package org.hopto.git23069.textpad;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.hopto.git23069.textpad.core.Hashtags;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

final class EditorSettings {
    static final int[] AUTOSAVE_SECONDS = {0, 30, 60, 120, 180, 300, 480, 600, 900};
    static final String[] AUTOSAVE_LABELS = {"Off", "30 seconds", "1 minute", "2 minutes", "3 minutes", "5 minutes", "8 minutes", "10 minutes", "15 minutes"};
    static final String[] FONTS = {"monospace", "sans-serif", "serif", "sans-serif-condensed"};
    final SharedPreferences prefs;
    EditorSettings(Context context) { prefs = context.getSharedPreferences("settings", 0); }
    boolean wrap() { return prefs.getBoolean("wrap", true); }
    boolean lineNumbers() { return prefs.getBoolean("lineNumbers", true); }
    int fontSize() { return prefs.getInt("fontSize", 16); }
    String font() { return prefs.getString("font", "monospace"); }
    int indent() { return prefs.getInt("indent", 4); }
    int dividerDashes() { return Math.max(1, Math.min(200, prefs.getInt("dividerDashes", 40))); }
    int autosaveSeconds() { return prefs.getInt("autosaveSeconds", 0); }
    void bool(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }

    List<String> errandsHashtags() {
        return Hashtags.parseTagList(prefs.getString("errandsHashtags", ""));
    }
    String errandsUpdatedAt() {
        return prefs.getString("errandsUpdatedAt", "");
    }
    boolean errandsDirty() {
        return prefs.getBoolean("errandsDirty", false);
    }
    void setErrandsHashtags(List<String> tags, String updatedAt, boolean dirty) {
        List<String> normalized = Hashtags.parseTagList(tags == null ? "" : String.join(" ", tags));
        prefs.edit()
                .putString("errandsHashtags", Hashtags.formatTagList(normalized))
                .putString("errandsUpdatedAt", updatedAt == null ? "" : updatedAt)
                .putBoolean("errandsDirty", dirty)
                .apply();
    }

    List<Uri> searchTrees() {
        List<Uri> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("searchTrees", "[]"));
            for (int i = 0; i < array.length(); i++) {
                String s = array.optString(i, "");
                if (!s.isEmpty()) list.add(Uri.parse(s));
            }
        } catch (JSONException ignored) {}
        return list;
    }
    void addSearchTree(Uri treeUri) {
        if (treeUri == null) return;
        List<Uri> current = searchTrees();
        for (Uri existing : current) if (existing.equals(treeUri)) return;
        current.add(treeUri);
        saveSearchTrees(current);
    }
    void removeSearchTree(Uri treeUri) {
        List<Uri> current = searchTrees();
        current.removeIf(u -> u.equals(treeUri));
        saveSearchTrees(current);
    }
    private void saveSearchTrees(List<Uri> trees) {
        JSONArray array = new JSONArray();
        for (Uri u : trees) array.put(u.toString());
        prefs.edit().putString("searchTrees", array.toString()).apply();
    }
}

