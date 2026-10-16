package org.hopto.git23069.textpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
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
import java.time.LocalDate;
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
    private Button saveButton, undoButton, redoButton, findButton, indentLessButton, indentMoreButton, todoButton, dateButton, dividerButton, bottomButton;
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

    @Override protected void onStart() { super.onStart(); started = true; app.attach(this); scheduleAutosave(); app.sync.setForeground(true); app.sync.syncAll(false); }
    @Override protected void onStop() {
        saveViewState(); started = false; app.main.removeCallbacks(autosave); app.sync.setForeground(false); app.detach(this); super.onStop();
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
        header.addView(iconButton(Icon.NEW, TEXT, "New document", v -> { saveViewState(); app.newDocument(); }));
        header.addView(iconButton(Icon.OPEN, TEXT, "Open file", v -> openPicker()));
        Button menu = iconButton(Icon.MENU, TEXT, "More options", this::showMenu); header.addView(menu);
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
        queryRow.addView(iconButton(Icon.PREV, TEXT, "Previous match", v -> findNext(false)));
        queryRow.addView(iconButton(Icon.NEXT, TEXT, "Next match", v -> findNext(true)));
        queryRow.addView(iconButton(Icon.CLOSE, TEXT, "Close find", v -> { findPanel.setVisibility(View.GONE); editor.requestFocus(); }));
        findPanel.addView(queryRow);
        LinearLayout replaceRow = row(); replacement = field("Replace with");
        replaceRow.addView(replacement, new LinearLayout.LayoutParams(0, dp(48), 1));
        replaceRow.addView(iconButton(Icon.REPLACE, TEXT, "Replace selection", v -> replaceOne()));
        replaceRow.addView(iconButton(Icon.REPLACE_ALL, TEXT, "Replace all matches", v -> replaceAll())); findPanel.addView(replaceRow);
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
        LinearLayout emptyActions = row(); emptyActions.addView(iconButton(Icon.NEW, ACCENT, "New document", v -> app.newDocument()));
        emptyActions.addView(iconButton(Icon.OPEN, TEXT, "Open file", v -> openPicker())); empty.addView(emptyActions);
        body.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(divider());
        HorizontalScrollView tools = new HorizontalScrollView(this); tools.setHorizontalScrollBarEnabled(false);
        LinearLayout toolbar = row(); toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        undoButton = iconButton(Icon.UNDO, TEXT, "Undo", v -> history(false));
        redoButton = iconButton(Icon.REDO, TEXT, "Redo", v -> history(true));
        findButton = iconButton(Icon.FIND, TEXT, "Find and replace", v -> showFind());
        indentLessButton = iconButton(Icon.INDENT_LESS, TEXT, "Decrease indentation", v -> indent(false));
        indentMoreButton = iconButton(Icon.INDENT_MORE, TEXT, "Increase indentation", v -> indent(true));
        todoButton = iconButton(Icon.TODO, TEXT, "Toggle todo", v -> toggleTodo());
        dateButton = iconButton(Icon.DATE, TEXT, "Insert date", v -> insertSnippet(LocalDate.now().toString()));
        dividerButton = iconButton(Icon.DIVIDER, TEXT, "Insert dividing line", v -> insertDivider());
        bottomButton = iconButton(Icon.BOTTOM, TEXT, "Go to bottom of file", v -> goToBottom());
        saveButton = iconButton(Icon.SAVE, ACCENT, "Save file", v -> { if (app.current() != null) app.save(app.current(), null); });
        toolbar.addView(undoButton); toolbar.addView(redoButton); toolbar.addView(findButton);
        toolbar.addView(indentLessButton); toolbar.addView(indentMoreButton);
        toolbar.addView(todoButton); toolbar.addView(dateButton); toolbar.addView(dividerButton); toolbar.addView(bottomButton); toolbar.addView(saveButton);
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
                view.addView(name); Button close = iconButton(Icon.CLOSE, TEXT, "Close " + tab.document.name, v -> closeTab(tab));
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
        setButtonEnabled(undoButton, doc != null && doc.writable && !doc.replacing && doc.buffer.canUndo());
        setButtonEnabled(todoButton, doc != null && doc.writable && !doc.replacing);
        setButtonEnabled(redoButton, doc != null && doc.writable && !doc.replacing && doc.buffer.canRedo());
        setButtonEnabled(dateButton, doc != null && doc.writable && !doc.replacing);
        setButtonEnabled(dividerButton, doc != null && doc.writable && !doc.replacing);
        setButtonEnabled(findButton, doc != null);
        setButtonEnabled(indentLessButton, doc != null && doc.writable && !doc.replacing);
        setButtonEnabled(indentMoreButton, doc != null && doc.writable && !doc.replacing);
        setButtonEnabled(bottomButton, doc != null);
        setButtonEnabled(saveButton, doc != null && !doc.saving);
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
    private void moveDocument() {
        EditorDocument doc = app.current(); if (doc == null || doc.uri == null || !doc.writable || doc.saving) return;
        app.pendingSave = new TextpadApplication.PendingSave(doc, null, false, true); launchSavePicker();
    }
    void launchSavePicker() {
        var pending = app.pendingSave; if (pending == null) return;
        Intent intent = new Intent(pending.existing() ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .setType(pending.existing() ? "*/*" : "text/plain").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, pending.document().name);
        if (pending.document().uri != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pending.document().uri);
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
            builder.setPositiveButton("Overwrite", (dialog, which) -> app.saveTo(conflict.document(), conflict.target(), conflict.expected(), conflict.snapshot(), conflict.closeTab(), conflict.move()));
            builder.setNegativeButton("Cancel", null);
        } else builder.setPositiveButton("OK", null);
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> { dialogVisible = false; app.main.post(app::deliverNext); }); dialog.show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] actions = {"New document", "Open file…", "Recent files…", "Save", "Save As…", "Save to existing file…", "Move / rename…", "Duplicate tab", "Word wrap", "Line numbers", "Encoding…", "Reload from file…", "Backups…", "Settings…", "Document details…", "Sync…", "Open with encoding…"};
        for (int i = 0; i < actions.length; i++) menu.getMenu().add(0, i, i, actions[i]);
        menu.getMenu().findItem(8).setCheckable(true).setChecked(app.settings.wrap());
        menu.getMenu().findItem(9).setCheckable(true).setChecked(app.settings.lineNumbers());
        for (int i : new int[]{3,4,5,7,10,11,14}) menu.getMenu().findItem(i).setEnabled(app.current() != null);
        EditorDocument current = app.current();
        menu.getMenu().findItem(6).setEnabled(current != null && current.uri != null && current.writable && !current.saving);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0 -> { saveViewState(); app.newDocument(); }
                case 1 -> openPicker(); case 2 -> showRecents();
                case 3 -> app.save(app.current(), null); case 4 -> saveAs(false); case 5 -> saveAs(true);
                case 6 -> moveDocument();
                case 7 -> { saveViewState(); app.duplicate(); }
                case 8 -> { app.settings.bool("wrap", !app.settings.wrap()); applySettings(); }
                case 9 -> { app.settings.bool("lineNumbers", !app.settings.lineNumbers()); applySettings(); }
                case 10 -> showEncoding(); case 11 -> reload(); case 12 -> showBackups();
                case 13 -> showSettings(); case 14 -> showDocumentInfo(app.current()); case 15 -> showSync();
                case 16 -> new AlertDialog.Builder(this).setTitle("Open with encoding")
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

    private void insertSnippet(String snippet) {
        EditorDocument doc = app.current(); if (doc == null || !doc.writable || doc.replacing) return;
        int start = Math.max(0, editor.getSelectionStart()), end = Math.max(start, editor.getSelectionEnd());
        if (editor.length() - (end - start) + snippet.length() > TextCodec.MAX_BYTES) {
            toast("Insertion would exceed the editor size limit."); return;
        }
        editor.requestFocus();
        editor.getText().replace(start, end, snippet);
        int cursor = start + snippet.length();
        editor.setSelection(cursor);
        if (editor.getLayout() != null) editor.bringPointIntoView(cursor);
        saveViewState(); updateStatus();
    }

    private void insertDivider() {
        insertSnippet("-".repeat(app.settings.dividerDashes()));
    }

    private void toggleTodo() {
        EditorDocument doc = app.current(); if (doc == null || !doc.writable || doc.replacing) return;
        int cursor = Math.max(0, editor.getSelectionStart());
        String text = editor.getText().toString();
        int lineStart = text.lastIndexOf('\n', Math.max(-1, cursor - 1)) + 1;
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        if (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r') lineEnd--;
        String line = text.substring(lineStart, lineEnd);
        editor.requestFocus();
        if (line.startsWith("[ ]")) {
            editor.getText().replace(lineStart, lineStart + 3, "[X]");
            editor.setSelection(Math.min(editor.length(), Math.max(lineStart + 3, cursor)));
        } else if (line.startsWith("[X]") || line.startsWith("[x]")) {
            int removeLen = line.length() > 3 && line.charAt(3) == ' ' ? 4 : 3;
            editor.getText().replace(lineStart, lineStart + removeLen, "");
            editor.setSelection(Math.min(editor.length(), Math.max(lineStart, cursor - removeLen)));
        } else {
            if (editor.length() + 4 > TextCodec.MAX_BYTES) { toast("Insertion would exceed the editor size limit."); return; }
            editor.getText().insert(lineStart, "[ ] ");
            editor.setSelection(Math.min(editor.length(), cursor + 4));
        }
        saveViewState(); updateStatus();
    }

    private void goToBottom() {
        if (app.current() == null) return;
        int end = editor.length();
        editor.requestFocus();
        editor.setSelection(end);
        if (editor.getLayout() != null) editor.bringPointIntoView(end);
        saveViewState();
        updateStatus();
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
        fields.addView(label("DIVIDING LINE DASHES", 11, MUTED));
        EditText dashes = field("40");
        dashes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dashes.setText(String.valueOf(app.settings.dividerDashes()));
        fields.addView(dashes);
        fields.addView(label("AUTOSAVE WHILE EDITING", 11, MUTED)); Spinner autosaveSetting = spinner(EditorSettings.AUTOSAVE_LABELS);
        for (int i = 0; i < EditorSettings.AUTOSAVE_SECONDS.length; i++) if (EditorSettings.AUTOSAVE_SECONDS[i] == app.settings.autosaveSeconds()) autosaveSetting.setSelection(i);
        fields.addView(autosaveSetting);
        fields.addView(label("Autosave writes existing writable files. New documents use Save As. Tabs and unsaved edits do not survive process restarts.", 12, MUTED));
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        new AlertDialog.Builder(this).setTitle("Editor settings").setView(scroll).setPositiveButton("Apply", (d, w) -> {
            int dashCount = app.settings.dividerDashes();
            try { dashCount = Math.max(1, Math.min(200, Integer.parseInt(dashes.getText().toString().trim()))); }
            catch (NumberFormatException ignored) { }
            app.settings.prefs.edit().putString("font", EditorSettings.FONTS[font.getSelectedItemPosition()])
                    .putInt("fontSize", size.getProgress()).putInt("indent", indents[indent.getSelectedItemPosition()])
                    .putInt("dividerDashes", dashCount)
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
    private enum Icon { NEW, OPEN, MENU, PREV, NEXT, CLOSE, REPLACE, REPLACE_ALL, UNDO, REDO, FIND, INDENT_LESS, INDENT_MORE, TODO, DATE, DIVIDER, BOTTOM, SAVE }
    private void setButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled); button.setAlpha(enabled ? 1f : 0.4f); button.invalidate();
    }
    private Button iconButton(Icon icon, int color, String description, View.OnClickListener action) {
        Button button = new Button(this); button.setText(""); button.setAllCaps(false);
        button.setMinWidth(dp(44)); button.setMinimumWidth(dp(44)); button.setMinHeight(dp(44)); button.setMinimumHeight(dp(44));
        button.setPadding(0, 0, 0, 0); button.setBackground(iconDrawable(icon, color));
        button.setContentDescription(description); button.setTooltipText(description); button.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44)); lp.setMargins(dp(2), 0, dp(2), 0); button.setLayoutParams(lp); return button;
    }
    private Drawable iconDrawable(Icon icon, int color) {
        return new Drawable() {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                bgPaint.setColor(SURFACE);
                strokePaint.setColor(color); strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(dp(2)); strokePaint.setStrokeCap(Paint.Cap.ROUND); strokePaint.setStrokeJoin(Paint.Join.ROUND);
                fillPaint.setColor(color); fillPaint.setStyle(Paint.Style.FILL);
            }
            @Override public void draw(Canvas canvas) {
                RectF bounds = new RectF(getBounds());
                float r = dp(7);
                canvas.drawRoundRect(bounds, r, r, bgPaint);
                float size = dp(18), ox = bounds.centerX() - size / 2f, oy = bounds.centerY() - size / 2f, s = size / 24f;
                Path p = new Path();
                switch (icon) {
                    case NEW -> { p.moveTo(ox + 5 * s, oy + 12 * s); p.lineTo(ox + 19 * s, oy + 12 * s); p.moveTo(ox + 12 * s, oy + 5 * s); p.lineTo(ox + 12 * s, oy + 19 * s); }
                    case OPEN -> {
                        p.moveTo(ox + 3 * s, oy + 6 * s); p.lineTo(ox + 9 * s, oy + 6 * s); p.lineTo(ox + 11 * s, oy + 8 * s);
                        p.lineTo(ox + 21 * s, oy + 8 * s); p.lineTo(ox + 21 * s, oy + 19 * s); p.lineTo(ox + 3 * s, oy + 19 * s); p.close();
                    }
                    case MENU -> {
                        for (float y : new float[]{5f, 12f, 19f}) canvas.drawCircle(ox + 12 * s, oy + y * s, 1.8f * s, fillPaint);
                    }
                    case PREV -> {
                        p.moveTo(ox + 12 * s, oy + 19 * s); p.lineTo(ox + 12 * s, oy + 5 * s);
                        p.moveTo(ox + 7 * s, oy + 10 * s); p.lineTo(ox + 12 * s, oy + 5 * s); p.lineTo(ox + 17 * s, oy + 10 * s);
                    }
                    case NEXT -> {
                        p.moveTo(ox + 12 * s, oy + 5 * s); p.lineTo(ox + 12 * s, oy + 19 * s);
                        p.moveTo(ox + 7 * s, oy + 14 * s); p.lineTo(ox + 12 * s, oy + 19 * s); p.lineTo(ox + 17 * s, oy + 14 * s);
                    }
                    case CLOSE -> {
                        p.moveTo(ox + 7 * s, oy + 7 * s); p.lineTo(ox + 17 * s, oy + 17 * s);
                        p.moveTo(ox + 17 * s, oy + 7 * s); p.lineTo(ox + 7 * s, oy + 17 * s);
                    }
                    case REPLACE -> {
                        p.moveTo(ox + 4 * s, oy + 8 * s); p.lineTo(ox + 19 * s, oy + 8 * s);
                        p.moveTo(ox + 15 * s, oy + 4 * s); p.lineTo(ox + 19 * s, oy + 8 * s); p.lineTo(ox + 15 * s, oy + 12 * s);
                        p.moveTo(ox + 20 * s, oy + 16 * s); p.lineTo(ox + 5 * s, oy + 16 * s);
                        p.moveTo(ox + 9 * s, oy + 12 * s); p.lineTo(ox + 5 * s, oy + 16 * s); p.lineTo(ox + 9 * s, oy + 20 * s);
                    }
                    case REPLACE_ALL -> {
                        p.moveTo(ox + 2 * s, oy + 12 * s); p.lineTo(ox + 7 * s, oy + 17 * s); p.lineTo(ox + 16 * s, oy + 7 * s);
                        p.moveTo(ox + 9 * s, oy + 12 * s); p.lineTo(ox + 14 * s, oy + 17 * s); p.lineTo(ox + 22 * s, oy + 7 * s);
                    }
                    case UNDO -> {
                        p.moveTo(ox + 7 * s, oy + 5 * s); p.lineTo(ox + 3 * s, oy + 9 * s); p.lineTo(ox + 7 * s, oy + 13 * s);
                        p.moveTo(ox + 3 * s, oy + 9 * s); p.lineTo(ox + 14 * s, oy + 9 * s);
                        p.arcTo(new RectF(ox + 9 * s, oy + 9 * s, ox + 19 * s, oy + 19 * s), -90f, 180f, false);
                        p.lineTo(ox + 9 * s, oy + 19 * s);
                    }
                    case REDO -> {
                        p.moveTo(ox + 17 * s, oy + 5 * s); p.lineTo(ox + 21 * s, oy + 9 * s); p.lineTo(ox + 17 * s, oy + 13 * s);
                        p.moveTo(ox + 21 * s, oy + 9 * s); p.lineTo(ox + 10 * s, oy + 9 * s);
                        p.arcTo(new RectF(ox + 5 * s, oy + 9 * s, ox + 15 * s, oy + 19 * s), -90f, -180f, false);
                        p.lineTo(ox + 15 * s, oy + 19 * s);
                    }
                    case FIND -> {
                        canvas.drawCircle(ox + 11 * s, oy + 11 * s, 6.5f * s, strokePaint);
                        p.moveTo(ox + 16 * s, oy + 16 * s); p.lineTo(ox + 20.5f * s, oy + 20.5f * s);
                    }
                    case INDENT_LESS -> {
                        p.moveTo(ox + 4 * s, oy + 5 * s); p.lineTo(ox + 4 * s, oy + 19 * s);
                        p.moveTo(ox + 20 * s, oy + 12 * s); p.lineTo(ox + 9 * s, oy + 12 * s);
                        p.moveTo(ox + 13 * s, oy + 8 * s); p.lineTo(ox + 9 * s, oy + 12 * s); p.lineTo(ox + 13 * s, oy + 16 * s);
                    }
                    case INDENT_MORE -> {
                        p.moveTo(ox + 4 * s, oy + 5 * s); p.lineTo(ox + 4 * s, oy + 19 * s);
                        p.moveTo(ox + 8 * s, oy + 12 * s); p.lineTo(ox + 19 * s, oy + 12 * s);
                        p.moveTo(ox + 15 * s, oy + 8 * s); p.lineTo(ox + 19 * s, oy + 12 * s); p.lineTo(ox + 15 * s, oy + 16 * s);
                    }
                    case TODO -> {
                        canvas.drawRoundRect(new RectF(ox + 3 * s, oy + 3 * s, ox + 21 * s, oy + 21 * s), 2 * s, 2 * s, strokePaint);
                        p.moveTo(ox + 8.5f * s, oy + 12.5f * s); p.lineTo(ox + 11 * s, oy + 15 * s); p.lineTo(ox + 16 * s, oy + 9.5f * s);
                    }
                    case DATE -> {
                        canvas.drawRoundRect(new RectF(ox + 3 * s, oy + 5 * s, ox + 21 * s, oy + 21 * s), 2 * s, 2 * s, strokePaint);
                        p.moveTo(ox + 8 * s, oy + 3 * s); p.lineTo(ox + 8 * s, oy + 7 * s);
                        p.moveTo(ox + 16 * s, oy + 3 * s); p.lineTo(ox + 16 * s, oy + 7 * s);
                        p.moveTo(ox + 3 * s, oy + 10 * s); p.lineTo(ox + 21 * s, oy + 10 * s);
                    }
                    case DIVIDER -> {
                        p.moveTo(ox + 3 * s, oy + 12 * s); p.lineTo(ox + 7 * s, oy + 12 * s);
                        p.moveTo(ox + 10 * s, oy + 12 * s); p.lineTo(ox + 14 * s, oy + 12 * s);
                        p.moveTo(ox + 17 * s, oy + 12 * s); p.lineTo(ox + 21 * s, oy + 12 * s);
                    }
                    case BOTTOM -> {
                        p.moveTo(ox + 12 * s, oy + 4 * s); p.lineTo(ox + 12 * s, oy + 16 * s);
                        p.moveTo(ox + 7 * s, oy + 11 * s); p.lineTo(ox + 12 * s, oy + 16 * s); p.lineTo(ox + 17 * s, oy + 11 * s);
                        p.moveTo(ox + 5 * s, oy + 20 * s); p.lineTo(ox + 19 * s, oy + 20 * s);
                    }
                    case SAVE -> {
                        p.moveTo(ox + 5 * s, oy + 3 * s); p.lineTo(ox + 16 * s, oy + 3 * s); p.lineTo(ox + 20 * s, oy + 7 * s);
                        p.lineTo(ox + 20 * s, oy + 20 * s); p.lineTo(ox + 4 * s, oy + 20 * s); p.lineTo(ox + 4 * s, oy + 3 * s); p.close();
                        p.moveTo(ox + 8 * s, oy + 3 * s); p.lineTo(ox + 8 * s, oy + 8 * s); p.lineTo(ox + 15 * s, oy + 8 * s); p.lineTo(ox + 15 * s, oy + 3 * s);
                        p.moveTo(ox + 7 * s, oy + 20 * s); p.lineTo(ox + 7 * s, oy + 13 * s); p.lineTo(ox + 17 * s, oy + 13 * s); p.lineTo(ox + 17 * s, oy + 20 * s);
                    }
                }
                if (!p.isEmpty()) canvas.drawPath(p, strokePaint);
            }
            @Override public void setAlpha(int alpha) { bgPaint.setAlpha(alpha); strokePaint.setAlpha(alpha); fillPaint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { strokePaint.setColorFilter(filter); fillPaint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
    private EditText field(String hint) { EditText view = new EditText(this); view.setHint(hint); view.setSingleLine(true); view.setTextSize(14); view.setSaveEnabled(false); return view; }
    private Spinner spinner(String[] labels) { Spinner view = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels); view.setAdapter(adapter); return view; }
    private LinearLayout dialogFields() { LinearLayout fields = column(); fields.setPadding(dp(22), dp(12), dp(22), dp(12)); return fields; }
    private View divider() { View view = new View(this); view.setBackgroundColor(0xff29322c); view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return view; }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
