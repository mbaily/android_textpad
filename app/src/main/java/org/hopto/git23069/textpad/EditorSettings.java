package org.hopto.git23069.textpad;

import android.content.Context;
import android.content.SharedPreferences;

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
    int autosaveSeconds() { return prefs.getInt("autosaveSeconds", 0); }
    void bool(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
}
