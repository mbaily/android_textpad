package org.hopto.git23069.textpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.TextCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements TextpadApplication.Listener {
    private static final int OPEN = 10, CREATE = 11, EXISTING = 12;
    private static final int BG = 0xff101313, SURFACE = 0xff19201d, TEXT = 0xffe3e8df, MUTED = 0xff94a298, ACCENT = 0xffa9d98b;
    private TextpadApplication app;
    private LinearLayout root, tabBar, findPanel, empty;
    private HorizontalScrollView tabScroll;
    private TextView location, status, findResult;
    private CodeEditor editor;
    private EditText find, replacement;
    private CheckBox matchCase;
    private Button saveButton, undoButton, redoButton;
    private EditorTab shownTab;
    private long shownState = -1;
    private boolean changing, started, dialogVisible;
    private final Map<Long, TextView> tabNames = new HashMap<>();
    private final Map<Long, LinearLayout> tabViews = new HashMap<>();
    private final Runnable autosave = new Runnable() {
        @Override public void run() {
            List<EditorDocument> seen = new ArrayList<>();
            for (EditorTab tab : app.tabs) {
                EditorDocument doc = tab.document;
                if (!seen.contains(doc)) {
                    seen.add(doc);
                    if (doc.uri != null && doc.writable && doc.buffer.dirty() && !doc.saving) app.save(doc, null);
                }
            }
            scheduleAutosave();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (TextpadApplication) getApplication();
        buildUi();
        if (state == null) handleIntent(getIntent());
    }

    @Override protected void onStart() { super.onStart(); started = true; app.attach(this); scheduleAutosave(); app.sync.syncAll(false); }
    @Override protected void onStop() {
        saveViewState(); started = false; app.main.removeCallbacks(autosave); app.detach(this); super.onStop();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    private void handleIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            takePermission(intent.getData(), intent.getFlags()); app.open(intent.getData());
        }
    }

    private void buildUi() {
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            var system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            var keyboard = insets.getInsets(WindowInsets.Type.ime());
            root.setPadding(system.left, system.top, system.right, Math.max(system.bottom, keyboard.bottom));
            return insets;
        });
        LinearLayout header = row(); header.setPadding(dp(16), dp(8), dp(8), dp(8)); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = column();
        TextView title = label("Textpad", 23, TEXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView subtitle = label("A LITTLE SPACE TO WRITE", 9, MUTED); subtitle.setLetterSpacing(.15f);
        brand.addView(title); brand.addView(subtitle); header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("+", "New document", v -> { saveViewState(); app.newDocument(); }));
        header.addView(button("Open", "Open file", v -> openPicker()));
        Button menu = button("⋮", "More options", this::showMenu); header.addView(menu);
        root.addView(header);
        root.addView(divider());

        tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false); tabScroll.setFillViewport(true);
        tabBar = row(); tabBar.setPadding(dp(8), dp(5), dp(8), dp(5)); tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabBar); root.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(52)));
        location = label("LOCAL FILES · FULLY OFFLINE", 10, MUTED); location.setSingleLine(true);
        location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); location.setPadding(dp(16), dp(7), dp(16), dp(7));
        root.addView(location); root.addView(divider());

        findPanel = column(); findPanel.setPadding(dp(10), dp(5), dp(10), dp(5)); findPanel.setBackgroundColor(SURFACE);
        LinearLayout queryRow = row(); find = field("Find text");
        queryRow.addView(find, new LinearLayout.LayoutParams(0, dp(48), 1));
        queryRow.addView(button("↑", "Previous match", v -> findNext(false)));
        queryRow.addView(button("↓", "Next match", v -> findNext(true)));
        queryRow.addView(button("×", "Close find", v -> { findPanel.setVisibility(View.GONE); editor.requestFocus(); }));
        findPanel.addView(queryRow);
        LinearLayout replaceRow = row(); replacement = field("Replace with");
        replaceRow.addView(replacement, new LinearLayout.LayoutParams(0, dp(48), 1));
        replaceRow.addView(button("Replace", "Replace selection", v -> replaceOne()));
        replaceRow.addView(button("All", "Replace all matches", v -> replaceAll())); findPanel.addView(replaceRow);
        LinearLayout findOptions = row(); matchCase = new CheckBox(this); matchCase.setText("Match case"); matchCase.setTextSize(12);
        findOptions.addView(matchCase); findResult = label("", 11, MUTED); findOptions.addView(findResult); findPanel.addView(findOptions);
        findPanel.setVisibility(View.GONE); root.addView(findPanel);

        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        editor = new CodeEditor(this); body.addView(editor, new android.widget.FrameLayout.LayoutParams(-1, -1));
        empty = column(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(32), dp(24), dp(32), dp(24));
        TextView emblem = label("T_", 56, ACCENT); emblem.setTypeface(Typeface.MONOSPACE); emblem.setGravity(Gravity.CENTER); empty.addView(emblem);
        TextView welcome = label("Your files. Your words.", 24, TEXT); welcome.setGravity(Gravity.CENTER); empty.addView(welcome);
        TextView intro = label("Open a text file or start a fresh page.\nEverything you write stays on your device\nuntil you choose to sync it.", 14, MUTED);
        intro.setGravity(Gravity.CENTER); intro.setLineSpacing(dp(4), 1); intro.setPadding(0, dp(16), 0, dp(24)); empty.addView(intro);
        LinearLayout emptyActions = row(); emptyActions.addView(button("New document", "New document", v -> app.newDocument()));
        emptyActions.addView(button("Open file", "Open file", v -> openPicker())); empty.addView(emptyActions);
        body.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(divider());
        HorizontalScrollView tools = new HorizontalScrollView(this); tools.setHorizontalScrollBarEnabled(false);
        LinearLayout toolbar = row(); toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        undoButton = button("↶", "Undo", v -> history(false)); redoButton = button("↷", "Redo", v -> history(true));
        toolbar.addView(undoButton); toolbar.addView(redoButton);
        toolbar.addView(button("Find", "Find and replace", v -> showFind()));
        toolbar.addView(button("←|", "Decrease indentation", v -> indent(false)));
        toolbar.addView(button("|→", "Increase indentation", v -> indent(true)));
        saveButton = button("Save", "Save file", v -> { if (app.current() != null) app.save(app.current(), null); });
        saveButton.setTextColor(ACCENT); toolbar.addView(saveButton);
        tools.addView(toolbar); root.addView(tools);
        status = label("OFFLINE · READY", 11, MUTED); status.setSingleLine(true); status.setPadding(dp(16), dp(5), dp(16), dp(9));
        root.addView(status);
        setContentView(root);

        editor.selectionChanged = () -> { if (!changing) { saveViewState(); updateStatus(); } };
        editor.scrolled = () -> { if (!changing) saveViewState(); };
        editor.addTextChangedListener(new TextWatcher() {
            private int start, count;
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { this.start = start; this.count = count; }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable editable) {
                if (changing || shownTab == null) return;
                int addedCount = editable.length() - (shownTab.document.buffer.length() - count);
                if (addedCount < 0) return;
                DocumentBuffer buffer = shownTab.document.buffer;
                buffer.edit(start, start + count, editable.subSequence(start, start + addedCount).toString());
                shownState = buffer.state(); editor.updateGutter(); saveViewState(); updateTabLabels(); updateStatus();
            }
        });
    }

    @Override public void refresh() {
        if (editor == null) return;
        EditorTab selected = app.currentTab();
        if (shownTab != selected || (selected != null && (shownState != selected.document.buffer.state() || editor.buffer != selected.document.buffer))) {
            saveViewState();
            boolean switchTab = shownTab != selected;
            shownTab = selected; changing = true;
            if (selected != null) {
                EditorDocument doc = selected.document;
                boolean differentBuffer = editor.buffer != doc.buffer;
                editor.buffer = doc.buffer;
                if (switchTab || shownState != doc.buffer.state() || differentBuffer) editor.setText(doc.buffer.text());
                editor.configure(app.settings, doc.writable && !doc.replacing);
                int length = editor.length();
                editor.setSelection(Math.min(length, selected.selectionStart), Math.min(length, selected.selectionEnd));
                shownState = doc.buffer.state();
                final long tabId = selected.id;
                editor.post(() -> {
                    if (shownTab != null && shownTab.id == tabId) {
                        changing = true; editor.scrollTo(selected.scrollX, selected.scrollY); changing = false;
                    }
                });
            } else { editor.buffer = null; editor.setText(""); shownState = -1; }
            changing = false;
        }
        editor.setVisibility(selected == null ? View.GONE : View.VISIBLE);
        empty.setVisibility(selected == null ? View.VISIBLE : View.GONE);
        if (selected != null && ((editor.getKeyListener() != null) != (selected.document.writable && !selected.document.replacing)))
            editor.configure(app.settings, selected.document.writable && !selected.document.replacing);
        updateTabLabels(); updateStatus();
    }

    private void updateTabLabels() {
        boolean rebuild = tabNames.size() != app.tabs.size() || app.tabs.stream().anyMatch(t -> !tabNames.containsKey(t.id));
        if (rebuild) {
            tabBar.removeAllViews(); tabNames.clear(); tabViews.clear();
            for (EditorTab tab : app.tabs) {
                LinearLayout view = row(); view.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = label("", 13, TEXT); name.setSingleLine(true); name.setMaxWidth(dp(170));
                name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(dp(13), dp(10), dp(4), dp(10));
                name.setOnClickListener(v -> { saveViewState(); app.selectedTab = tab.id; app.refresh(); });
                name.setOnLongClickListener(v -> { showDocumentInfo(tab.document); return true; });
                view.addView(name); Button close = button("×", "Close " + tab.document.name, v -> closeTab(tab));
                view.addView(close); tabBar.addView(view); tabNames.put(tab.id, name); tabViews.put(tab.id, view);
            }
        }
        for (EditorTab tab : app.tabs) {
            EditorDocument doc = tab.document;
            String label = (doc.buffer.dirty() ? "● " : "") + doc.name;
            tabNames.get(tab.id).setText(label);
            tabNames.get(tab.id).setTextColor(tab.id == app.selectedTab ? ACCENT : MUTED);
            tabViews.get(tab.id).setBackground(rounded(tab.id == app.selectedTab ? 0xff263026 : BG, 8));
        }
        if (app.tabs.isEmpty()) {
            if (tabBar.getChildCount() == 0) tabBar.addView(label(app.opening ? "Opening file…" : "No open documents", 12, MUTED));
        }
    }

    private void updateStatus() {
        EditorDocument doc = app.current();
        undoButton.setEnabled(doc != null && doc.writable && !doc.replacing && doc.buffer.canUndo());
        redoButton.setEnabled(doc != null && doc.writable && !doc.replacing && doc.buffer.canRedo());
        saveButton.setEnabled(doc != null && !doc.saving);
        saveButton.setText(doc != null && doc.saving ? "Saving…" : "Save");
        if (doc == null) { status.setText(app.opening ? "Opening file…" : "OFFLINE · READY"); location.setText("LOCAL FILES · FULLY OFFLINE"); return; }
        location.setText(doc.uri == null ? "NEW DOCUMENT · NOT SAVED" : app.store.location(doc.uri, doc.name));
        int offset = Math.max(0, editor.getSelectionStart()), line = doc.buffer.lineAt(offset);
        int column = offset - doc.buffer.lineStart(line) + 1;
        status.setText(String.format(Locale.ROOT, "Ln %d, Col %d  ·  %s%s  ·  %s%s%s", line + 1, column,
                doc.buffer.encoding().label, doc.buffer.bom() ? " BOM" : "", doc.buffer.endingLabel(),
                !doc.writable ? "  ·  READ ONLY" : doc.buffer.dirty() ? "  ·  UNSAVED" : doc.uri == null ? "  ·  NEW" : "  ·  SAVED",
                doc.syncEnabled ? (app.sync.busy ? "  ·  SYNCING" : "  ·  SYNC") : ""));
    }

    private void saveViewState() {
        if (shownTab == null || changing || editor == null) return;
        shownTab.selectionStart = Math.max(0, editor.getSelectionStart()); shownTab.selectionEnd = Math.max(0, editor.getSelectionEnd());
        shownTab.scrollX = editor.getScrollX(); shownTab.scrollY = editor.getScrollY();
    }

    private void closeTab(EditorTab tab) {
        if (tab.document.saving) { toast("Wait for this file to finish saving."); return; }
        if (!tab.document.buffer.dirty()) { app.close(tab.id); return; }
        new AlertDialog.Builder(this).setTitle("Save changes to " + tab.document.name + "?")
                .setMessage("Your unsaved changes will be lost when the last view of this document closes.")
                .setPositiveButton("Save", (dialog, which) -> app.save(tab.document, tab.id))
                .setNegativeButton("Discard", (dialog, which) -> app.close(tab.id))
                .setNeutralButton("Cancel", null).show();
    }

    private void openPicker() {
        openPicker(null);
    }
    private void openPicker(TextCodec.Encoding encoding) {
        app.nextOpenEncoding = encoding;
        saveViewState();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN);
    }
    private void saveAs(boolean existing) {
        EditorDocument doc = app.current(); if (doc == null || doc.saving) return;
        app.pendingSave = new TextpadApplication.PendingSave(doc, null, existing); launchSavePicker();
    }
    void launchSavePicker() {
        var pending = app.pendingSave; if (pending == null) return;
        Intent intent = new Intent(pending.existing() ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .setType(pending.existing() ? "*/*" : "text/plain").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, pending.document().name);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, pending.existing() ? EXISTING : CREATE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == CREATE || request == EXISTING) app.pendingSave = null;
            return;
        }
        Uri uri = data.getData(); takePermission(uri, data.getFlags());
        if (request == OPEN) { TextCodec.Encoding encoding = app.nextOpenEncoding; app.nextOpenEncoding = null; app.open(uri, encoding); }
        else if (request == CREATE || request == EXISTING) app.saveAsResult(uri);
    }
    private void takePermission(Uri uri, int flags) {
        try { getContentResolver().takePersistableUriPermission(uri, flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
        catch (SecurityException ignored) { /* VIEW intents may offer temporary access only. */ }
    }

    @Override public void event(TextpadApplication.Event event) {
        if (event.title().equals("save-as")) { launchSavePicker(); return; }
        if (dialogVisible) { app.events.addFirst(event); return; }
        dialogVisible = true;
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(event.title()).setMessage(event.message());
        if (event.conflict() != null) {
            var conflict = event.conflict();
            builder.setPositiveButton("Overwrite", (dialog, which) -> app.saveTo(conflict.document(), conflict.target(), conflict.expected(), conflict.snapshot(), conflict.closeTab()));
            builder.setNegativeButton("Cancel", null);
        } else builder.setPositiveButton("OK", null);
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> { dialogVisible = false; app.main.post(app::deliverNext); }); dialog.show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] actions = {"New document", "Open file…", "Recent files…", "Save", "Save As…", "Save to existing file…", "Duplicate tab", "Word wrap", "Line numbers", "Encoding…", "Reload from file…", "Backups…", "Settings…", "Document details…", "Sync…", "Open with encoding…"};
        for (int i = 0; i < actions.length; i++) menu.getMenu().add(0, i, i, actions[i]);
        menu.getMenu().findItem(7).setCheckable(true).setChecked(app.settings.wrap());
        menu.getMenu().findItem(8).setCheckable(true).setChecked(app.settings.lineNumbers());
        for (int i : new int[]{3,4,5,6,9,10,13}) menu.getMenu().findItem(i).setEnabled(app.current() != null);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0 -> { saveViewState(); app.newDocument(); }
                case 1 -> openPicker(); case 2 -> showRecents();
                case 3 -> app.save(app.current(), null); case 4 -> saveAs(false); case 5 -> saveAs(true);
                case 6 -> { saveViewState(); app.duplicate(); }
                case 7 -> { app.settings.bool("wrap", !app.settings.wrap()); applySettings(); }
                case 8 -> { app.settings.bool("lineNumbers", !app.settings.lineNumbers()); applySettings(); }
                case 9 -> showEncoding(); case 10 -> reload(); case 11 -> showBackups();
                case 12 -> showSettings(); case 13 -> showDocumentInfo(app.current()); case 14 -> showSync();
                case 15 -> new AlertDialog.Builder(this).setTitle("Open with encoding")
                        .setItems(java.util.Arrays.stream(TextCodec.Encoding.values()).map(e -> e.label).toArray(String[]::new),
                                (d, which) -> openPicker(TextCodec.Encoding.values()[which])).setNegativeButton("Cancel", null).show();
            }
            return true;
        }); menu.show();
    }

    private void showRecents() {
        List<DocumentStore.Recent> recent = app.store.recents();
        if (recent.isEmpty()) { toast("No recent files yet."); return; }
        String[] labels = recent.stream().map(r -> r.name() + "\n" + app.store.location(r.uri(), r.name())).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Recent files").setItems(labels, (d, i) -> { saveViewState(); app.open(recent.get(i).uri()); }).setNegativeButton("Cancel", null).show();
    }

    private void history(boolean redo) {
        EditorDocument doc = app.current(); if (doc == null || !doc.writable || doc.replacing) return;
        DocumentBuffer.Change change = redo ? doc.buffer.redo() : doc.buffer.undo(); if (change == null) return;
        int start = change.start(), oldLength = redo ? change.removed().length() : change.added().length();
        String added = redo ? change.added() : change.removed();
        changing = true; editor.getText().replace(start, start + oldLength, added);
        editor.setSelection(Math.min(editor.length(), start + added.length())); changing = false;
        shownState = doc.buffer.state(); saveViewState(); editor.updateGutter(); updateTabLabels(); updateStatus();
    }

    private void indent(boolean increase) {
        EditorDocument doc = app.current(); if (doc == null || !doc.writable || doc.replacing) return;
        int start = Math.max(0, editor.getSelectionStart()), end = Math.max(start, editor.getSelectionEnd());
        String text = editor.getText().toString(), unit = app.settings.indent() == 0 ? "\t" : " ".repeat(app.settings.indent());
        if (increase && start == end) { editor.getText().insert(start, unit); return; }
        int first = text.lastIndexOf('\n', Math.max(-1, start - 1)) + 1;
        int last = end > start && text.charAt(end - 1) == '\n' ? end - 1 : end;
        String part = text.substring(first, last), changed;
        if (increase) changed = unit + part.replace("\n", "\n" + unit);
        else {
            StringBuilder out = new StringBuilder(); String[] lines = part.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i]; int remove = 0;
                if (line.startsWith("\t")) remove = 1;
                else while (remove < line.length() && remove < (app.settings.indent() == 0 ? 4 : app.settings.indent()) && line.charAt(remove) == ' ') remove++;
                if (i > 0) out.append('\n'); out.append(line.substring(remove));
            }
            changed = out.toString();
        }
        editor.getText().replace(first, last, changed); editor.setSelection(first, first + changed.length());
    }

    private void showFind() { if (app.current() == null) return; findPanel.setVisibility(View.VISIBLE); find.requestFocus(); }
    private Pattern query() {
        String needle = find.getText().toString(); if (needle.isEmpty()) return null;
        return Pattern.compile(Pattern.quote(needle), matchCase.isChecked() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
    private void findNext(boolean forward) {
        Pattern pattern = query(); if (pattern == null || app.current() == null) return;
        Matcher matcher = pattern.matcher(editor.getText()); int start = -1, end = -1;
        int offset = Math.max(0, forward ? editor.getSelectionEnd() : editor.getSelectionStart());
        if (forward) {
            if (matcher.find(offset) || matcher.find(0)) { start = matcher.start(); end = matcher.end(); }
        } else {
            int lastStart = -1, lastEnd = -1;
            while (matcher.find()) {
                lastStart = matcher.start(); lastEnd = matcher.end();
                if (matcher.end() <= offset) { start = matcher.start(); end = matcher.end(); }
            }
            if (start < 0) { start = lastStart; end = lastEnd; }
        }
        if (start >= 0) { editor.setSelection(start, end); editor.bringPointIntoView(start); findResult.setText("Match selected"); }
        else findResult.setText("No matches");
    }
    private void replaceOne() {
        EditorDocument doc = app.current(); Pattern pattern = query(); if (doc == null || !doc.writable || doc.replacing || pattern == null) return;
        int start = Math.max(0, editor.getSelectionStart()), end = Math.max(start, editor.getSelectionEnd());
        if (pattern.matcher(editor.getText().subSequence(start, end)).matches()) editor.getText().replace(start, end, replacement.getText());
        findNext(true);
    }
    private void replaceAll() {
        EditorDocument doc = app.current(); Pattern pattern = query(); if (doc == null || !doc.writable || doc.replacing || pattern == null) return;
        Matcher matcher = pattern.matcher(editor.getText()); StringBuilder out = new StringBuilder(); int previous = 0, count = 0;
        while (matcher.find()) {
            out.append(editor.getText(), previous, matcher.start()).append(replacement.getText()); previous = matcher.end(); count++;
            if (out.length() > TextCodec.MAX_BYTES) { toast("Replacement would exceed the editor size limit."); return; }
        }
        if (count == 0) { findResult.setText("No matches"); return; }
        out.append(editor.getText(), previous, editor.length());
        if (out.length() > TextCodec.MAX_BYTES) { toast("Replacement would exceed the editor size limit."); return; }
        editor.getText().replace(0, editor.length(), out); findResult.setText(count + " replaced");
    }

    private void showEncoding() {
        EditorDocument doc = app.current(); if (doc == null || doc.replacing) return;
        if (!doc.writable) { toast("Use Save As to change a read-only file's encoding."); return; }
        TextCodec.Encoding[] values = TextCodec.Encoding.values();
        LinearLayout fields = dialogFields(); Spinner encoding = spinner(java.util.Arrays.stream(values).map(e -> e.label).toArray(String[]::new));
        encoding.setSelection(doc.buffer.encoding().ordinal()); fields.addView(encoding);
        CheckBox bom = new CheckBox(this); bom.setText("Include byte-order mark (BOM)"); bom.setChecked(doc.buffer.bom()); fields.addView(bom);
        new AlertDialog.Builder(this).setTitle("Save encoding").setView(fields).setPositiveButton("Apply", (d, w) -> {
            doc.buffer.format(values[encoding.getSelectedItemPosition()], bom.isChecked()); shownState = doc.buffer.state(); updateTabLabels(); updateStatus();
        }).setNegativeButton("Cancel", null).show();
    }

    private void reload() {
        EditorDocument doc = app.current(); if (doc == null || doc.uri == null || doc.saving) return;
        new AlertDialog.Builder(this).setTitle("Reload " + doc.name + "?").setMessage("Replace this document's text with the saved file. Any unsaved edits in its tabs will be discarded.")
                .setPositiveButton("Reload", (d, w) -> {
                    doc.saving = true; doc.replacing = true; app.refresh();
                    app.io.execute(() -> {
                        try {
                            byte[] bytes = app.store.read(doc.uri); DocumentBuffer buffer = new DocumentBuffer(TextCodec.decode(bytes));
                            app.main.post(() -> { doc.buffer = buffer; doc.savedHash = org.hopto.git23069.textpad.core.SaveTransaction.hash(bytes); doc.saving = false; doc.replacing = false; shownState = -1; app.refresh(); });
                        } catch (Exception e) { app.main.post(() -> { doc.saving = false; doc.replacing = false; app.refresh(); app.notice("Could not reload", e.getMessage()); }); }
                    });
                }).setNegativeButton("Cancel", null).show();
    }

    private void showBackups() {
        app.io.execute(() -> {
            try {
                List<DocumentStore.Backup> backups = app.store.backups();
                app.main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (backups.isEmpty()) { app.notice("Backups", "Previous file contents appear here after an overwrite. Backups remain until you delete them or uninstall Textpad."); return; }
                    new AlertDialog.Builder(this).setTitle("Backups · " + backups.size()).setItems(backups.stream().map(DocumentStore.Backup::name).toArray(String[]::new), (d, i) -> {
                        DocumentStore.Backup backup = backups.get(i);
                        new AlertDialog.Builder(this).setTitle(backup.originalName()).setMessage("Open this revision as a new document, then use Save As or Save to existing file to restore it.")
                                .setPositiveButton("Open revision", (dialog, w) -> app.restoreBackup(backup))
                                .setNegativeButton("Cancel", null).setNeutralButton("Delete revision", (dialog, w) -> confirmDeleteBackup(backup)).show();
                    }).setNegativeButton("Close", null).show();
                });
            } catch (Exception e) { app.main.post(() -> app.notice("Could not list backups", e.getMessage())); }
        });
    }
    private void confirmDeleteBackup(DocumentStore.Backup backup) {
        new AlertDialog.Builder(this).setTitle("Delete this revision?").setMessage("This removes only the selected backup.")
                .setPositiveButton("Delete", (d, w) -> app.io.execute(() -> {
                    try { java.nio.file.Files.delete(backup.metadata().toPath()); java.nio.file.Files.delete(backup.content().toPath()); }
                    catch (Exception e) { app.main.post(() -> app.notice("Could not delete revision", e.getMessage())); }
                })).setNegativeButton("Cancel", null).show();
    }

    private void showDocumentInfo(EditorDocument doc) {
        if (doc == null) return;
        String message = (doc.uri == null ? "Not saved to a file" : app.store.location(doc.uri, doc.name))
                + "\n\n" + doc.buffer.length() + " UTF-16 code units · " + doc.buffer.lineCount() + " lines\n"
                + doc.buffer.encoding().label + (doc.buffer.bom() ? " with BOM" : " without BOM") + " · " + doc.buffer.endingLabel()
                + "\n" + (doc.writable ? "Writable" : "Read only") + (doc.syncEnabled ? "\nSync enabled · " + doc.syncId : "\nSync disabled");
        new AlertDialog.Builder(this).setTitle(doc.name).setMessage(message).setPositiveButton("Close", null).show();
    }

    private void showSettings() {
        LinearLayout fields = dialogFields();
        fields.addView(label("EDITOR FONT", 11, MUTED)); Spinner font = spinner(EditorSettings.FONTS);
        for (int i = 0; i < EditorSettings.FONTS.length; i++) if (EditorSettings.FONTS[i].equals(app.settings.font())) font.setSelection(i);
        fields.addView(font);
        TextView sizeLabel = label("Font size · " + app.settings.fontSize() + " sp", 14, TEXT); fields.addView(sizeLabel);
        SeekBar size = new SeekBar(this); size.setMin(8); size.setMax(36); size.setProgress(app.settings.fontSize()); fields.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { sizeLabel.setText("Font size · " + value + " sp"); }
            public void onStartTrackingTouch(SeekBar bar) {} public void onStopTrackingTouch(SeekBar bar) {}
        });
        fields.addView(label("INDENTATION", 11, MUTED)); Spinner indent = spinner(new String[]{"Tab", "2 spaces", "4 spaces", "8 spaces"});
        int[] indents = {0,2,4,8}; for (int i = 0; i < indents.length; i++) if (indents[i] == app.settings.indent()) indent.setSelection(i); fields.addView(indent);
        fields.addView(label("AUTOSAVE WHILE EDITING", 11, MUTED)); Spinner autosaveSetting = spinner(EditorSettings.AUTOSAVE_LABELS);
        for (int i = 0; i < EditorSettings.AUTOSAVE_SECONDS.length; i++) if (EditorSettings.AUTOSAVE_SECONDS[i] == app.settings.autosaveSeconds()) autosaveSetting.setSelection(i);
        fields.addView(autosaveSetting);
        fields.addView(label("Autosave writes existing writable files. New documents use Save As. Tabs and unsaved edits do not survive process restarts.", 12, MUTED));
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        new AlertDialog.Builder(this).setTitle("Editor settings").setView(scroll).setPositiveButton("Apply", (d, w) -> {
            app.settings.prefs.edit().putString("font", EditorSettings.FONTS[font.getSelectedItemPosition()])
                    .putInt("fontSize", size.getProgress()).putInt("indent", indents[indent.getSelectedItemPosition()])
                    .putInt("autosaveSeconds", EditorSettings.AUTOSAVE_SECONDS[autosaveSetting.getSelectedItemPosition()]).apply();
            applySettings();
        }).setNegativeButton("Cancel", null).show();
    }
    private void applySettings() { if (app.current() != null) editor.configure(app.settings, app.current().writable && !app.current().replacing); scheduleAutosave(); updateStatus(); }
    private void scheduleAutosave() {
        app.main.removeCallbacks(autosave);
        if (started && app.settings.autosaveSeconds() > 0) app.main.postDelayed(autosave, app.settings.autosaveSeconds() * 1000L);
    }

    private void showSync() { new SyncUi(this, app).show(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_S -> { if (event.isShiftPressed()) saveAs(false); else if (app.current() != null) app.save(app.current(), null); return true; }
                case KeyEvent.KEYCODE_O -> { openPicker(); return true; }
                case KeyEvent.KEYCODE_N -> { saveViewState(); app.newDocument(); return true; }
                case KeyEvent.KEYCODE_F -> { showFind(); return true; }
                case KeyEvent.KEYCODE_Z -> { history(event.isShiftPressed()); return true; }
                case KeyEvent.KEYCODE_Y -> { history(true); return true; }
                case KeyEvent.KEYCODE_W -> { if (app.currentTab() != null) closeTab(app.currentTab()); return true; }
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_TAB && app.current() != null && editor.hasFocus()) { indent(!event.isShiftPressed()); return true; }
        return super.dispatchKeyEvent(event);
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String text, String description, View.OnClickListener action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(13); button.setAllCaps(false);
        button.setTextColor(TEXT); button.setMinWidth(dp(44)); button.setMinimumWidth(dp(44)); button.setMinHeight(dp(44)); button.setMinimumHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0); button.setBackground(rounded(SURFACE, 7));
        button.setContentDescription(description); button.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44)); lp.setMargins(dp(2), 0, dp(2), 0); button.setLayoutParams(lp); return button;
    }
    private EditText field(String hint) { EditText view = new EditText(this); view.setHint(hint); view.setSingleLine(true); view.setTextSize(14); view.setSaveEnabled(false); return view; }
    private Spinner spinner(String[] labels) { Spinner view = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels); view.setAdapter(adapter); return view; }
    private LinearLayout dialogFields() { LinearLayout fields = column(); fields.setPadding(dp(22), dp(12), dp(22), dp(12)); return fields; }
    private View divider() { View view = new View(this); view.setBackgroundColor(0xff29322c); view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return view; }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
