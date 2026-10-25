package org.hopto.git23069.textpad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
import org.hopto.git23069.textpad.core.LineInsertion;
import org.hopto.git23069.textpad.core.TaskFilter;
import org.hopto.git23069.textpad.core.TextCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements TextpadApplication.Listener {
    private static final int OPEN = 10, CREATE = 11, EXISTING = 12, SEARCH_TREE = 13;
    private static final int BG = 0xff101313, SURFACE = 0xff19201d, TEXT = 0xffe3e8df, MUTED = 0xff94a298, ACCENT = 0xffa9d98b;
    static final int SYNC_DOT_SYNCED = ACCENT, SYNC_DOT_SYNCING = 0xff7bb8d9, SYNC_DOT_PENDING = 0xffd9a57b;
    private TextpadApplication app;
    private LinearLayout root, tabBar, findPanel, empty, recentFileList;
    private List<DocumentStore.Recent> shownRecentFiles;
    private HorizontalScrollView tabScroll;
    private TextView location, status, findTitle, findResult;
    private CodeEditor editor;
    private EditText find, replacement;
    private LinearLayout lineComposer, lineComposerBody;
    private EditText lineInput;
    private TextView lineInputStatus;
    private Button lineComposerToggle, lineLessButton, lineMoreButton, lineAddButton;
    private boolean updatingLineInput, lineComposerCollapsed;
    private CheckBox matchCase;
    private Button saveButton, undoButton, redoButton, findButton, findAllButton, hashtagsButton, indentLessButton, indentMoreButton, todoButton, taskFilterButton, dateButton, timeButton, dividerButton, moveUpButton, moveDownButton, bottomButton, readOnlyButton, replaceOneButton, replaceAllButton;
    private HashtagUi activeHashtagUi;
    private LibraryUi libraryUi;
    private HorizontalScrollView editorTools;
    private LinearLayout headerFileActions;
    private Button filesTab;
    private boolean libraryBackRegistered;
    private final android.window.OnBackInvokedCallback libraryBack = this::navigateBackFromFiles;
    private EditorTab shownTab;
    private long shownState = -1;
    private boolean changing, started, dialogVisible, findAllFiles;
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
        lineComposerCollapsed = app.settings.prefs.getBoolean("lineComposerCollapsed", false);
        if (state != null) app.readOnlyMode = state.getBoolean("readOnlyMode", app.readOnlyMode);
        if (state != null) app.filesVisible = state.getBoolean("filesVisible", app.filesVisible);
        buildUi();
        if (state == null) handleIntent(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("readOnlyMode", app.readOnlyMode);
        outState.putBoolean("filesVisible", app.filesVisible);
    }

    @Override protected void onStart() {
        super.onStart(); started = true; app.attach(this); scheduleAutosave(); app.sync.setForeground(true); app.sync.syncAll(false);
        if (app.filesVisible) app.library.refresh();
    }
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
        saveButton = iconButton(Icon.SAVE, ACCENT, "Save file", v -> { if (app.current() != null) app.save(app.current(), null); });
        headerFileActions = row(); headerFileActions.addView(saveButton);
        headerFileActions.addView(iconButton(Icon.NEW, TEXT, "New document", v -> { saveViewState(); app.newDocument(); }));
        headerFileActions.addView(iconButton(Icon.OPEN, TEXT, "Open file", v -> openPicker()));
        header.addView(headerFileActions);
        Button menu = iconButton(Icon.MENU, TEXT, "More options", this::showMenu); header.addView(menu);
        root.addView(header);
        root.addView(divider());

        tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false); tabScroll.setFillViewport(true);
        tabBar = row(); tabBar.setPadding(dp(8), dp(5), dp(8), dp(5)); tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabBar); root.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(52)));
        location = label("LOCAL FILES · FULLY OFFLINE", 10, MUTED); location.setSingleLine(true);
        location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); location.setPadding(dp(16), dp(7), dp(16), dp(7));
        root.addView(location); root.addView(divider());

        findPanel = column(); findPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable findBg = rounded(SURFACE, 10); findBg.setStroke(dp(1), 0xff465b3c); findPanel.setBackground(findBg);
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(-1, -2);
        findParams.setMargins(dp(12), dp(8), dp(12), dp(4)); findPanel.setLayoutParams(findParams);
        LinearLayout findHeader = row();
        findTitle = label("Find in file", 13, ACCENT); findTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        findHeader.addView(findTitle, new LinearLayout.LayoutParams(0, -2, 1));
        findHeader.addView(iconButton(Icon.CLOSE, TEXT, "Close find", v -> closeFind()));
        findPanel.addView(findHeader);
        LinearLayout queryRow = row(); find = field("Find text");
        find.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) { updateSearchHighlight(); }
        });
        find.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) { findNext(!event.isShiftPressed()); return true; }
            return false;
        });
        queryRow.addView(find, new LinearLayout.LayoutParams(0, dp(48), 1));
        queryRow.addView(iconButton(Icon.PREV, TEXT, "Previous match", v -> findNext(false)));
        queryRow.addView(iconButton(Icon.NEXT, TEXT, "Next match", v -> findNext(true)));
        findPanel.addView(queryRow);
        LinearLayout replaceRow = row(); replacement = field("Replace with");
        replaceRow.addView(replacement, new LinearLayout.LayoutParams(0, dp(48), 1));
        replaceOneButton = iconButton(Icon.REPLACE, TEXT, "Replace selection", v -> replaceOne());
        replaceAllButton = iconButton(Icon.REPLACE_ALL, TEXT, "Replace all matches", v -> replaceAll());
        replaceRow.addView(replaceOneButton);
        replaceRow.addView(replaceAllButton); findPanel.addView(replaceRow);
        LinearLayout findOptions = row(); matchCase = new CheckBox(this); matchCase.setText("Match case"); matchCase.setTextSize(12);
        matchCase.setOnCheckedChangeListener((buttonView, isChecked) -> updateSearchHighlight());
        findOptions.addView(matchCase); findResult = label("", 11, MUTED); findOptions.addView(findResult); findPanel.addView(findOptions);
        findPanel.setVisibility(View.GONE); root.addView(findPanel);

        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        editor = new CodeEditor(this); body.addView(editor, new android.widget.FrameLayout.LayoutParams(-1, -1));
        empty = column(); empty.setId(R.id.recent_files); empty.setPadding(dp(20), dp(16), dp(20), 0);
        LinearLayout recentHeader = row(); recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView recentTitle = label("Recent files", 22, TEXT);
        recentHeader.addView(recentTitle, new LinearLayout.LayoutParams(0, -2, 1));
        recentHeader.addView(iconButton(Icon.NEW, ACCENT, "New document", v -> app.newDocument()));
        recentHeader.addView(iconButton(Icon.OPEN, TEXT, "Open file", v -> openPicker()));
        empty.addView(recentHeader);
        Button browse = new Button(this); browse.setText("Browse shared files and folders"); browse.setAllCaps(false);
        browse.setContentDescription("Browse shared files and folders"); browse.setOnClickListener(v -> showFiles()); empty.addView(browse);
        ScrollView recentScroll = new ScrollView(this); recentScroll.setFillViewport(true);
        recentFileList = column(); recentFileList.setId(R.id.recent_file_list); recentFileList.setPadding(0, dp(12), 0, dp(20));
        recentScroll.addView(recentFileList);
        empty.addView(recentScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        libraryUi = new LibraryUi(this, app); body.addView(libraryUi.view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        buildLineComposer();
        root.addView(divider());
        HorizontalScrollView tools = new HorizontalScrollView(this); tools.setHorizontalScrollBarEnabled(false);
        editorTools = tools;
        LinearLayout toolbar = row(); toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        undoButton = iconButton(Icon.UNDO, TEXT, "Undo", v -> history(false));
        redoButton = iconButton(Icon.REDO, TEXT, "Redo", v -> history(true));
        findButton = iconButton(Icon.FIND, TEXT, "Find in file", v -> showFind(false));
        findAllButton = iconButton(Icon.FIND_ALL, TEXT, "Find in open files", v -> showFind(true));
        hashtagsButton = iconButton(Icon.HASHTAG, TEXT, "Hashtags & Tag groups", v -> showHashtags(false));
        indentLessButton = iconButton(Icon.INDENT_LESS, TEXT, "Decrease indentation", v -> indent(false));
        indentMoreButton = iconButton(Icon.INDENT_MORE, TEXT, "Increase indentation", v -> indent(true));
        todoButton = iconButton(Icon.TODO, TEXT, "Toggle todo", v -> toggleTodo());
        todoButton.setTooltipText("Toggle todo ([ ] → [X] → [C] → [ ])");
        taskFilterButton = iconButton(Icon.TASK_FILTER, TEXT, "Filter uncompleted tasks", v -> toggleTasksFilter());
        dateButton = iconButton(Icon.DATE, TEXT, "Insert date", v -> insertSnippet(LocalDate.now().toString()));
        timeButton = iconButton(Icon.TIME, TEXT, "Insert time", v -> insertSnippet(currentTimeString()));
        dividerButton = iconButton(Icon.DIVIDER, TEXT, "Insert dividing line", v -> insertDivider());
        moveUpButton = iconButton(Icon.MOVE_LINE_UP, TEXT, "Move line up", v -> moveLines(true));
        moveDownButton = iconButton(Icon.MOVE_LINE_DOWN, TEXT, "Move line down", v -> moveLines(false));
        bottomButton = iconButton(Icon.BOTTOM, TEXT, "Go to bottom of file", v -> goToBottom());
        readOnlyButton = iconButton(Icon.READ_ONLY, TEXT, "Toggle read-only mode", v -> toggleReadOnly());
        toolbar.addView(undoButton); toolbar.addView(redoButton); toolbar.addView(bottomButton); toolbar.addView(readOnlyButton); toolbar.addView(findButton); toolbar.addView(findAllButton); toolbar.addView(hashtagsButton);
        toolbar.addView(indentLessButton); toolbar.addView(indentMoreButton);
        toolbar.addView(todoButton); toolbar.addView(taskFilterButton); toolbar.addView(dateButton); toolbar.addView(timeButton); toolbar.addView(dividerButton);
        toolbar.addView(moveUpButton); toolbar.addView(moveDownButton);
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
                if (changing || shownTab == null || !isEditable(shownTab.document)) return;
                DocumentBuffer currentBuf = editor.buffer;
                if (currentBuf == null) return;
                int addedCount = editable.length() - (currentBuf.length() - count);
                if (addedCount < 0) return;
                String replacement = editable.subSequence(start, start + addedCount).toString();
                if (currentBuf.edit(start, start + count, replacement) == null) return;
                if (shownTab.tasksOnly && shownTab.taskFilterResult != null && shownTab.taskFilterResult.visibleLines().length > 0) {
                    int v = Math.max(0, Math.min(currentBuf.lineAt(start), shownTab.taskFilterResult.visibleLines().length - 1));
                    int colStart = start - currentBuf.lineStart(v);
                    int colEnd = colStart + count;
                    int realLine = shownTab.taskFilterResult.visibleLines()[v];
                    int realLineStart = shownTab.document.buffer.lineStart(realLine);
                    int realStart = realLineStart + colStart;
                    int realEnd = realLineStart + colEnd;
                    shownTab.document.buffer.edit(realStart, realEnd, replacement);

                    int lineStart = currentBuf.lineStart(v);
                    int lineEnd = v + 1 < currentBuf.lineCount() ? currentBuf.lineStart(v + 1) - 1 : currentBuf.length();
                    String lineText = currentBuf.text().substring(lineStart, lineEnd);
                    boolean isContext = realLine < shownTab.taskFilterResult.isContext().length && shownTab.taskFilterResult.isContext()[realLine];
                    if (TaskFilter.isClosedTask(lineText) || isContext) {
                        editable.setSpan(new ForegroundColorSpan(0x88e3e8df), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        ForegroundColorSpan[] spans = editable.getSpans(lineStart, lineEnd, ForegroundColorSpan.class);
                        for (ForegroundColorSpan s : spans) editable.removeSpan(s);
                    }
                }
                shownState = shownTab.document.buffer.state(); editor.updateGutter(); saveViewState(); updateTabLabels(); updateStatus();
            }
        });
    }

    @Override public void refresh() {
        refresh(true);
    }

    private void refresh(boolean saveCurrentViewState) {
        if (editor == null) return;
        boolean wasBrowsing = libraryUi.view.getVisibility() == View.VISIBLE;
        EditorTab selected = app.currentTab();
        DocumentBuffer targetBuffer = selected == null ? null : (selected.tasksOnly && selected.filteredBuffer != null ? selected.filteredBuffer : selected.document.buffer);
        if (shownTab != selected || (selected != null && (shownState != selected.document.buffer.state() || editor.buffer != targetBuffer))) {
            if (saveCurrentViewState && !wasBrowsing) saveViewState();
            boolean switchTab = shownTab != selected;
            shownTab = selected; changing = true;
            if (selected != null) {
                EditorDocument doc = selected.document;
                boolean differentBuffer = editor.buffer != targetBuffer;
                editor.buffer = targetBuffer;
                if (selected.tasksOnly) {
                    if (switchTab || shownState != doc.buffer.state() || selected.filteredBuffer == null || selected.taskFilterResult == null) {
                        selected.taskFilterResult = TaskFilter.filter(doc.buffer.text(), app.settings.indent());
                        String filteredText = buildFilteredText(doc.buffer, selected.taskFilterResult.visibleLines());
                        selected.filteredBuffer = new DocumentBuffer(TextCodec.split(filteredText, doc.buffer.encoding(), false));
                        editor.buffer = selected.filteredBuffer;
                    }
                    editor.setTasksOnly(true, selected.taskFilterResult.visibleLines(), () -> {
                        Toast.makeText(this, "Lines cannot be added or deleted in this view", Toast.LENGTH_SHORT).show();
                    });
                    if (switchTab || shownState != doc.buffer.state() || differentBuffer) {
                        editor.setDocumentText(buildFilteredSpanned(selected.filteredBuffer.text(), selected.taskFilterResult));
                    }
                } else {
                    editor.setTasksOnly(false, null, null);
                    if (switchTab || shownState != doc.buffer.state() || differentBuffer) {
                        editor.setDocumentText(doc.buffer.text());
                    }
                }
                editor.configure(app.settings, isEditable(doc));
                int length = editor.length();
                editor.setSelection(Math.min(length, selected.selectionStart), Math.min(length, selected.selectionEnd));
                shownState = doc.buffer.state();
                final long tabId = selected.id;
                editor.post(() -> {
                    if (shownTab != null && shownTab.id == tabId) {
                        changing = true; editor.scrollTo(selected.scrollX, selected.scrollY); changing = false;
                    }
                });
            } else { editor.setTasksOnly(false, null, null); editor.buffer = null; editor.setDocumentText(""); shownState = -1; }
            changing = false;
        }
        editor.setVisibility(selected == null || app.filesVisible ? View.GONE : View.VISIBLE);
        empty.setVisibility(selected == null && !app.filesVisible ? View.VISIBLE : View.GONE);
        libraryUi.view.setVisibility(app.filesVisible ? View.VISIBLE : View.GONE);
        if (app.filesVisible != libraryBackRegistered) {
            if (app.filesVisible) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, libraryBack);
            else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(libraryBack);
            libraryBackRegistered = app.filesVisible;
        }
        editorTools.setVisibility(app.filesVisible ? View.GONE : View.VISIBLE);
        headerFileActions.setVisibility(app.filesVisible ? View.GONE : View.VISIBLE);
        location.setVisibility(app.filesVisible ? View.GONE : View.VISIBLE);
        if (app.filesVisible) libraryUi.render();
        else if (wasBrowsing && selected != null) editor.post(() -> {
            if (app.filesVisible || app.currentTab() != selected) return;
            changing = true;
            editor.setSelection(Math.min(editor.length(), selected.selectionStart), Math.min(editor.length(), selected.selectionEnd));
            editor.scrollTo(selected.scrollX, selected.scrollY); changing = false;
        });
        if (selected == null) updateRecentFiles();
        if (selected != null && ((editor.getKeyListener() != null) != isEditable(selected.document)))
            editor.configure(app.settings, isEditable(selected.document));
        updateTabLabels(); updateStatus();
    }

    private boolean isEditable(EditorDocument doc) {
        return doc != null && doc.writable && !doc.replacing && !app.readOnlyMode;
    }

    private void buildLineComposer() {
        lineComposer = column();
        lineComposer.setBackgroundColor(SURFACE);
        lineComposerToggle = new Button(this); lineComposerToggle.setId(R.id.line_composer_toggle);
        lineComposerToggle.setText(""); lineComposerToggle.setMinHeight(0); lineComposerToggle.setMinimumHeight(0);
        lineComposerToggle.setPadding(0, 0, 0, 0); lineComposerToggle.setBackground(lineComposerHandle());
        lineComposerToggle.setOnClickListener(v -> toggleLineComposer());
        lineComposer.addView(lineComposerToggle, new LinearLayout.LayoutParams(-1, dp(13)));
        lineComposerBody = column(); lineComposerBody.setId(R.id.line_composer_body);
        lineComposerBody.setPadding(dp(12), dp(6), dp(12), dp(6));
        lineInputStatus = label("", 11, MUTED); lineInputStatus.setId(R.id.line_input_status);
        lineComposerBody.addView(lineInputStatus);
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER_VERTICAL);
        lineLessButton = iconButton(Icon.INDENT_LESS, TEXT, "Decrease new line indentation", v -> adjustLineIndent(false));
        lineMoreButton = iconButton(Icon.INDENT_MORE, TEXT, "Increase new line indentation", v -> adjustLineIndent(true));
        controls.addView(lineLessButton); controls.addView(lineMoreButton);
        lineInput = field("Add one line…"); lineInput.setId(R.id.line_input);
        lineInput.setContentDescription("New line text"); lineInput.setSingleLine(true);
        lineInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        lineInput.setFilters(new android.text.InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
                    toast("Enter one line at a time"); return dest.subSequence(dstart, dend);
                }
            }
            return null;
        }});
        lineInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable text) {
                if (!updatingLineInput && shownTab != null) {
                    shownTab.lineDraft = text.toString(); updateLineComposer();
                }
            }
        });
        lineInput.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) addLine();
                return true;
            }
            return false;
        });
        controls.addView(lineInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        lineAddButton = new Button(this); lineAddButton.setText("Add"); lineAddButton.setAllCaps(false);
        lineAddButton.setContentDescription("Add line below cursor"); lineAddButton.setMinWidth(0); lineAddButton.setMinimumWidth(0);
        lineAddButton.setOnClickListener(v -> addLine()); controls.addView(lineAddButton);
        lineComposerBody.addView(controls); lineComposer.addView(lineComposerBody);
        lineComposer.setVisibility(View.GONE); root.addView(lineComposer);
    }

    private Drawable lineComposerHandle() {
        return new Drawable() {
            private final Paint background = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path chevron = new Path();
            {
                background.setColor(0xff263128); stroke.setColor(ACCENT);
                stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(1));
                stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
            }
            @Override public void draw(Canvas canvas) {
                canvas.drawRect(getBounds(), background);
                float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
                float direction = lineComposerCollapsed ? -1 : 1, halfHeight = dp(3) / 2f;
                chevron.reset(); chevron.moveTo(x - dp(4), y - direction * halfHeight);
                chevron.lineTo(x, y + direction * halfHeight);
                chevron.lineTo(x + dp(4), y - direction * halfHeight);
                canvas.drawPath(chevron, stroke);
            }
            @Override public void setAlpha(int alpha) { background.setAlpha(alpha); stroke.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { background.setColorFilter(filter); stroke.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private void toggleLineComposer() {
        lineComposerCollapsed = !lineComposerCollapsed;
        app.settings.bool("lineComposerCollapsed", lineComposerCollapsed);
        if (lineComposerCollapsed && lineInput.hasFocus()) {
            editor.requestFocus();
            var keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(lineInput.getWindowToken(), 0);
        }
        updateStatus();
    }

    private boolean canAddLine() {
        EditorDocument doc = app.current();
        return app.readOnlyMode && doc != null && doc.writable && !doc.replacing && !app.filesVisible
                && shownTab != null && !shownTab.tasksOnly && !lineComposerCollapsed;
    }

    private void updateLineComposer() {
        boolean visible = app.readOnlyMode && shownTab != null && !app.filesVisible;
        lineComposer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        lineComposerToggle.setContentDescription(lineComposerCollapsed ? "Expand Add one line" : "Collapse Add one line");
        lineComposerToggle.setTooltipText(lineComposerToggle.getContentDescription());
        lineComposerToggle.setStateDescription(lineComposerCollapsed ? "Collapsed" : "Expanded");
        lineComposerToggle.invalidate();
        lineComposerBody.setVisibility(lineComposerCollapsed ? View.GONE : View.VISIBLE);
        int offset = Math.max(0, editor.getSelectionEnd());
        int line = editor.buffer.lineAt(Math.min(offset, editor.length()));
        String sourceIndent = LineInsertion.indentation(editor.getText(), offset);
        int lineEnd = line + 1 < editor.buffer.lineCount() ? editor.buffer.lineStart(line + 1) - 1 : editor.length();
        boolean blank = sourceIndent.length() == lineEnd - editor.buffer.lineStart(line);
        if (shownTab.lineAnchor != line || !sourceIndent.equals(shownTab.lineSourceIndent)) {
            shownTab.lineAnchor = line; shownTab.lineSourceIndent = sourceIndent; shownTab.lineIndent = sourceIndent;
        }
        if (!lineInput.getText().toString().equals(shownTab.lineDraft)) {
            updatingLineInput = true; lineInput.setText(shownTab.lineDraft); lineInput.setSelection(lineInput.length()); updatingLineInput = false;
        }
        boolean enabled = canAddLine();
        lineInput.setEnabled(enabled); lineMoreButton.setEnabled(enabled);
        lineLessButton.setEnabled(enabled && !shownTab.lineIndent.isEmpty());
        lineAddButton.setEnabled(enabled && !shownTab.lineDraft.isEmpty());
        lineAddButton.setContentDescription(blank ? "Fill blank line" : "Add line below cursor");
        lineInputStatus.setText(!app.current().writable ? "Use Save As to add lines to this file" : shownTab.tasksOnly
                ? "Switch to the full view to add lines" : app.current().replacing ? "Updating file…"
                : (blank ? "Fill line " : "Add below line ") + (line + 1) + " · Indent: " + shownTab.lineIndent.length()
                + (shownTab.lineIndent.indexOf('\t') >= 0 ? " characters (tabs)" : " spaces"));
    }

    private void adjustLineIndent(boolean increase) {
        if (!canAddLine()) return;
        shownTab.lineIndent = LineInsertion.adjustIndent(shownTab.lineIndent,
                LineInsertion.indentUnit(editor.getText(), editor.getSelectionEnd(), app.settings.indent()), increase);
        updateLineComposer();
    }

    private void addLine() {
        if (!canAddLine() || shownTab.lineDraft.isEmpty()) return;
        LineInsertion.Insertion insertion;
        try { insertion = LineInsertion.below(editor.getText(), editor.getSelectionEnd(), shownTab.lineIndent, shownTab.lineDraft); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        if (editor.length() - (insertion.end() - insertion.offset()) + insertion.text().length() > TextCodec.MAX_BYTES) { toast("The document is too large"); return; }
        EditorDocument doc = shownTab.document;
        doc.buffer.edit(insertion.offset(), insertion.end(), insertion.text());
        shownTab.selectionStart = shownTab.selectionEnd = insertion.cursor(); shownTab.lineDraft = "";
        shownTab.lineAnchor = -1;
        refresh(false);
        editor.setSelection(insertion.cursor());
        editor.bringPointIntoView(insertion.cursor()); saveViewState();
        lineInput.requestFocus();
    }

    private void toggleReadOnly() {
        app.readOnlyMode = !app.readOnlyMode;
        EditorDocument doc = app.current();
        if (doc != null) {
            saveViewState();
            int selStart = Math.max(0, editor.getSelectionStart()), selEnd = Math.max(selStart, editor.getSelectionEnd());
            int sx = editor.getScrollX(), sy = editor.getScrollY();
            changing = true;
            editor.configure(app.settings, isEditable(doc));
            int len = editor.length();
            editor.setSelection(Math.min(len, selStart), Math.min(len, selEnd));
            editor.scrollTo(sx, sy);
            changing = false;
            if (app.readOnlyMode) {
                var imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (imm != null) imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
            }
        }
        updateStatus();
    }

    private void updateTabLabels() {
        boolean rebuild = filesTab == null || tabNames.size() != app.tabs.size() || app.tabs.stream().anyMatch(t -> !tabNames.containsKey(t.id));
        if (rebuild) {
            tabBar.removeAllViews(); tabNames.clear(); tabViews.clear();
            filesTab = iconButton(Icon.OPEN, MUTED, "Browse shared files", v -> showFiles()); filesTab.setId(R.id.files_tab);
            filesTab.setTooltipText("Files");
            tabBar.addView(filesTab, new LinearLayout.LayoutParams(dp(44), dp(44)));
            for (EditorTab tab : app.tabs) {
                LinearLayout view = row(); view.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = label("", 13, TEXT); name.setSingleLine(true); name.setMaxWidth(dp(170));
                name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(dp(13), dp(10), dp(4), dp(10));
                name.setOnClickListener(v -> { saveViewState(); app.filesVisible = false; app.selectedTab = tab.id; app.refresh(); app.sync.configurationChanged(); });
                name.setOnLongClickListener(v -> { showDocumentInfo(tab.document); return true; });
                view.addView(name); Button close = iconButton(Icon.CLOSE, TEXT, "Close " + tab.document.name, v -> closeTab(tab));
                view.addView(close); tabBar.addView(view); tabNames.put(tab.id, name); tabViews.put(tab.id, view);
            }
        }
        for (EditorTab tab : app.tabs) {
            EditorDocument doc = tab.document;
            String label = (doc.buffer.dirty() ? "● " : "") + doc.name;
            tabNames.get(tab.id).setText(label);
            tabNames.get(tab.id).setTextColor(!app.filesVisible && tab.id == app.selectedTab ? ACCENT : MUTED);
            tabViews.get(tab.id).setBackground(rounded(!app.filesVisible && tab.id == app.selectedTab ? 0xff263026 : BG, 8));
        }
        filesTab.setSelected(app.filesVisible);
        filesTab.setBackground(iconDrawable(Icon.OPEN, app.filesVisible ? ACCENT : MUTED, app.filesVisible ? 0xff263026 : BG));
        if (app.tabs.isEmpty()) {
            if (tabBar.getChildCount() == 1) tabBar.addView(label(app.opening ? "Opening file…" : "No open documents", 12, MUTED));
        }
    }

    private void updateStatus() {
        EditorDocument doc = app.current();
        updateLineComposer();
        boolean editable = isEditable(doc);
        setButtonEnabled(undoButton, editable && doc.buffer.canUndo());
        setButtonEnabled(todoButton, editable || canAddLine());
        setButtonEnabled(taskFilterButton, doc != null);
        taskFilterButton.setBackground(iconDrawable(Icon.TASK_FILTER, shownTab != null && shownTab.tasksOnly ? ACCENT : TEXT));
        setButtonEnabled(moveUpButton, editable && (shownTab == null || !shownTab.tasksOnly));
        setButtonEnabled(moveDownButton, editable && (shownTab == null || !shownTab.tasksOnly));
        setButtonEnabled(redoButton, editable && doc.buffer.canRedo());
        boolean canInsertSnippet = editable || canAddLine();
        setButtonEnabled(timeButton, canInsertSnippet);
        setButtonEnabled(dateButton, canInsertSnippet);
        setButtonEnabled(dividerButton, canInsertSnippet);
        setButtonEnabled(findAllButton, doc != null);
        setButtonEnabled(findButton, doc != null);
        setButtonEnabled(indentLessButton, editable);
        setButtonEnabled(indentMoreButton, editable);
        setButtonEnabled(bottomButton, doc != null);
        setButtonEnabled(readOnlyButton, doc != null);
        readOnlyButton.setBackground(iconDrawable(Icon.READ_ONLY, app.readOnlyMode ? ACCENT : TEXT));
        if (replaceOneButton != null) setButtonEnabled(replaceOneButton, editable);
        if (replaceAllButton != null) setButtonEnabled(replaceAllButton, editable);
        boolean canSave = doc != null && !doc.saving && !doc.replacing && doc.buffer.dirty();
        saveButton.setBackground(iconDrawable(Icon.SAVE, canSave ? ACCENT : TEXT));
        setButtonEnabled(saveButton, canSave);
        if (app.filesVisible) {
            status.setText(app.library.busy ? "Shared library · Working…" : app.library.connected ? "Shared library · Connected" : "Shared library · Cached files");
            return;
        }
        if (doc == null) { status.setText(app.opening ? "Opening file…" : "OFFLINE · READY"); location.setText("LOCAL FILES · FULLY OFFLINE"); return; }
        location.setText(doc.uri == null ? "NEW DOCUMENT · NOT SAVED" : app.store.location(doc.uri, doc.name));
        int offset = Math.max(0, editor.getSelectionStart());
        int line = (shownTab != null && shownTab.tasksOnly) ? toRealLine(shownTab, offset) : doc.buffer.lineAt(offset);
        int column = (shownTab != null && shownTab.tasksOnly) ? toRealColumn(shownTab, offset) : offset - doc.buffer.lineStart(line) + 1;
        String baseStatus = String.format(Locale.ROOT, "Ln %d, Col %d  ·  %s%s  ·  %s%s", line + 1, column,
                doc.buffer.encoding().label, doc.buffer.bom() ? " BOM" : "", doc.buffer.endingLabel(),
                (!doc.writable || app.readOnlyMode) ? "  ·  READ ONLY" : doc.buffer.dirty() ? "  ·  UNSAVED" : doc.uri == null ? "  ·  NEW" : "  ·  SAVED");
        if (doc.syncEnabled) {
            SpannableStringBuilder sb = new SpannableStringBuilder(baseStatus).append("  ·  ●");
            boolean synced = !doc.buffer.dirty() && doc.savedHash != null && doc.syncedHash != null && Arrays.equals(doc.savedHash, doc.syncedHash);
            int dotColor = app.sync.busy ? SYNC_DOT_SYNCING : synced ? SYNC_DOT_SYNCED : SYNC_DOT_PENDING;
            sb.setSpan(new ForegroundColorSpan(dotColor), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            status.setText(sb);
        } else {
            status.setText(baseStatus);
        }
    }

    private void saveViewState() {
        if (shownTab == null || changing || editor == null || app.filesVisible) return;
        shownTab.selectionStart = Math.max(0, editor.getSelectionStart()); shownTab.selectionEnd = Math.max(0, editor.getSelectionEnd());
        shownTab.scrollX = editor.getScrollX(); shownTab.scrollY = editor.getScrollY();
    }

    private void closeTab(EditorTab tab) {
        if (tab.document.saving) { toast("Wait for this file to finish saving."); return; }
        long views = app.tabs.stream().filter(t -> t.document == tab.document).count();
        if (!tab.document.buffer.dirty() || views > 1) { app.close(tab.id); return; }
        new AlertDialog.Builder(this).setTitle("Save changes to " + tab.document.name + "?")
                .setMessage("Your unsaved changes will be lost when the last view of this document closes.")
                .setPositiveButton("Save", (dialog, which) -> app.save(tab.document, tab.id))
                .setNegativeButton("Don't Save", (dialog, which) -> app.close(tab.id))
                .setNeutralButton("Cancel", null).show();
    }

    void exitApp() {
        for (EditorTab tab : app.tabs) {
            if (tab.document.saving) { toast("Wait for this file to finish saving."); return; }
        }
        saveViewState();
        app.exiting = true;
        continueExit();
    }

    private void continueExit() {
        if (!app.exiting) return;
        while (!app.tabs.isEmpty()) {
            EditorTab tab = app.tabs.get(0);
            if (tab.document.saving) return;
            long views = app.tabs.stream().filter(t -> t.document == tab.document).count();
            if (!tab.document.buffer.dirty() || views > 1) {
                app.close(tab.id);
                continue;
            }
            if (app.selectedTab != tab.id) {
                saveViewState();
                app.selectedTab = tab.id;
                app.refresh();
            }
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Save changes to " + tab.document.name + "?")
                    .setMessage("Your unsaved changes will be lost when closing Textpad.")
                    .setPositiveButton("Save", (d, w) -> app.save(tab.document, tab.id))
                    .setNegativeButton("Don't Save", (d, w) -> { app.close(tab.id); continueExit(); })
                    .setNeutralButton("Cancel", (d, w) -> app.exiting = false).create();
            dialog.setOnCancelListener(d -> app.exiting = false);
            dialog.show();
            return;
        }
        app.exiting = false;
        finishAndRemoveTask();
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
    void launchSearchTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, SEARCH_TREE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == CREATE || request == EXISTING) { app.pendingSave = null; app.exiting = false; }
            return;
        }
        Uri uri = data.getData(); takePermission(uri, data.getFlags());
        if (request == OPEN) { TextCodec.Encoding encoding = app.nextOpenEncoding; app.nextOpenEncoding = null; app.open(uri, encoding); }
        else if (request == CREATE || request == EXISTING) app.saveAsResult(uri);
        else if (request == SEARCH_TREE) {
            app.settings.addSearchTree(uri);
            if (activeHashtagUi != null) activeHashtagUi.onFolderTreeGranted();
        }
    }
    private void takePermission(Uri uri, int flags) {
        try { getContentResolver().takePersistableUriPermission(uri, flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
        catch (SecurityException ignored) { /* VIEW intents may offer temporary access only. */ }
    }

    @Override public void event(TextpadApplication.Event event) {
        if (event.title().equals("save-as")) { launchSavePicker(); return; }
        if (event.title().equals("continue-exit")) { continueExit(); return; }
        if (dialogVisible) { app.events.addFirst(event); return; }
        dialogVisible = true;
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(event.title()).setMessage(event.message());
        if (event.conflict() != null) {
            var conflict = event.conflict();
            builder.setPositiveButton("Overwrite", (dialog, which) -> app.saveTo(conflict.document(), conflict.target(), conflict.expected(), conflict.snapshot(), conflict.closeTab(), conflict.move()));
            builder.setNegativeButton("Cancel", (dialog, which) -> app.exiting = false);
        } else builder.setPositiveButton("OK", null);
        AlertDialog dialog = builder.create();
        dialog.setOnCancelListener(d -> { if (event.conflict() != null) app.exiting = false; });
        dialog.setOnDismissListener(d -> { dialogVisible = false; app.main.post(app::deliverNext); }); dialog.show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] actions = {"New document", "Open file…", "Recent files…", "Hashtags & Tag groups…", "Save", "Save As…", "Save to existing file…", "Move / rename…", "Duplicate tab", "Word wrap", "Line numbers", "Encoding…", "Reload from file…", "Backups…", "Settings…", "Document details…", "Sync…", "Open with encoding…", "Exit", "Files and folders…"};
        for (int i = 0; i < actions.length; i++) menu.getMenu().add(0, i, i, actions[i]);
        menu.getMenu().findItem(9).setCheckable(true).setChecked(app.settings.wrap());
        menu.getMenu().findItem(10).setCheckable(true).setChecked(app.settings.lineNumbers());
        EditorDocument current = app.current();
        menu.getMenu().findItem(4).setEnabled(current != null && !current.saving && !current.replacing && current.buffer.dirty());
        for (int i : new int[]{5,6,8,11,12,15}) menu.getMenu().findItem(i).setEnabled(current != null);
        menu.getMenu().findItem(7).setEnabled(current != null && current.uri != null && current.writable && !current.saving);
        menu.setOnMenuItemClickListener(item -> {
            if (app.filesVisible && java.util.Set.of(4, 5, 6, 7, 8, 11, 12, 15).contains(item.getItemId())) showEditor();
            switch (item.getItemId()) {
                case 0 -> { saveViewState(); app.newDocument(); }
                case 1 -> openPicker(); case 2 -> showRecents();
                case 3 -> showHashtags(false);
                case 4 -> app.save(app.current(), null); case 5 -> saveAs(false); case 6 -> saveAs(true);
                case 7 -> moveDocument();
                case 8 -> { saveViewState(); app.duplicate(); }
                case 9 -> { app.settings.bool("wrap", !app.settings.wrap()); applySettings(); }
                case 10 -> { app.settings.bool("lineNumbers", !app.settings.lineNumbers()); applySettings(); }
                case 11 -> showEncoding(); case 12 -> reload(); case 13 -> showBackups();
                case 14 -> showSettings(); case 15 -> showDocumentInfo(app.current()); case 16 -> showSync();
                case 17 -> new AlertDialog.Builder(this).setTitle("Open with encoding")
                        .setItems(java.util.Arrays.stream(TextCodec.Encoding.values()).map(e -> e.label).toArray(String[]::new),
                                (d, which) -> openPicker(TextCodec.Encoding.values()[which])).setNegativeButton("Cancel", null).show();
                case 18 -> exitApp();
                case 19 -> showFiles();
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

    private void updateRecentFiles() {
        List<DocumentStore.Recent> recent = app.store.recents();
        if (!recent.equals(shownRecentFiles)) {
            shownRecentFiles = recent;
            recentFileList.removeAllViews();
            if (recent.isEmpty()) {
                TextView message = label("No recent files yet.", 14, MUTED);
                message.setId(R.id.recent_files_empty); message.setPadding(0, dp(12), 0, 0);
                recentFileList.addView(message);
            }
            for (DocumentStore.Recent file : recent) {
                String location = app.store.location(file.uri(), file.name());
                SpannableStringBuilder text = new SpannableStringBuilder(file.name());
                if (!location.equals(file.name())) {
                    int detailStart = text.length() + 1;
                    text.append('\n').append(location);
                    text.setSpan(new ForegroundColorSpan(MUTED), detailStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new RelativeSizeSpan(.78f), detailStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                Button item = new Button(this); item.setText(text); item.setAllCaps(false); item.setTextColor(TEXT); item.setTextSize(16);
                item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); item.setPadding(dp(16), dp(12), dp(16), dp(12));
                item.setMinHeight(dp(72)); item.setMaxLines(3); item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22a9d98b), rounded(SURFACE, 10), null));
                item.setContentDescription("Open recent file: " + file.name() + (location.equals(file.name()) ? "" : ", " + location));
                item.setOnClickListener(v -> { saveViewState(); app.open(file.uri()); });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8);
                recentFileList.addView(item, params);
            }
        }
        for (int i = 0; i < recentFileList.getChildCount(); i++) {
            if (recentFileList.getChildAt(i) instanceof Button item) item.setEnabled(!app.opening);
        }
    }

    private void history(boolean redo) {
        EditorDocument doc = app.current(); if (!isEditable(doc)) return;
        EditorTab tab = shownTab;
        if (tab != null && tab.tasksOnly && tab.filteredBuffer != null) {
            DocumentBuffer.Change change = redo ? tab.filteredBuffer.redo() : tab.filteredBuffer.undo();
            if (change == null) return;
            if (redo) doc.buffer.redo(); else doc.buffer.undo();
            int start = change.start(), oldLength = redo ? change.removed().length() : change.added().length();
            String added = redo ? change.added() : change.removed();
            changing = true; editor.getText().replace(start, start + oldLength, added);
            editor.setSelection(Math.min(editor.length(), start + added.length())); changing = false;
            shownState = doc.buffer.state(); saveViewState(); editor.updateGutter(); updateTabLabels(); updateStatus();
            return;
        }
        DocumentBuffer.Change change = redo ? doc.buffer.redo() : doc.buffer.undo(); if (change == null) return;
        int start = change.start(), oldLength = redo ? change.removed().length() : change.added().length();
        String added = redo ? change.added() : change.removed();
        changing = true; editor.getText().replace(start, start + oldLength, added);
        editor.setSelection(Math.min(editor.length(), start + added.length())); changing = false;
        shownState = doc.buffer.state(); saveViewState(); editor.updateGutter(); updateTabLabels(); updateStatus();
    }

    private void indent(boolean increase) {
        EditorDocument doc = app.current(); if (!isEditable(doc)) return;
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
        if (canAddLine()) {
            int start = Math.max(0, Math.min(lineInput.getSelectionStart(), lineInput.getSelectionEnd()));
            int end = Math.max(start, Math.max(lineInput.getSelectionStart(), lineInput.getSelectionEnd()));
            if (lineInput.length() - (end - start) + snippet.length() > TextCodec.MAX_BYTES) {
                toast("Insertion would exceed the editor size limit."); return;
            }
            lineInput.getText().replace(start, end, snippet);
            lineInput.setSelection(start + snippet.length());
            lineInput.requestFocus();
            return;
        }
        EditorDocument doc = app.current(); if (!isEditable(doc)) return;
        if (shownTab != null && shownTab.tasksOnly && snippet.contains("\n")) {
            toast("Lines cannot be added or deleted in this view"); return;
        }
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

    private static String currentTimeString() {
        LocalTime now = LocalTime.now();
        int h24 = now.getHour(), h12 = h24 % 12 == 0 ? 12 : h24 % 12;
        return String.format(Locale.US, "%d:%02d %s", h12, now.getMinute(), h24 >= 12 ? "PM" : "AM");
    }

    private void insertDivider() {
        if (shownTab != null && shownTab.tasksOnly) {
            toast("Lines cannot be added or deleted in this view"); return;
        }
        insertSnippet("-".repeat(app.settings.dividerDashes()));
    }

    private void moveLines(boolean up) {
        EditorDocument doc = app.current(); if (!isEditable(doc)) return;
        if (shownTab != null && shownTab.tasksOnly) {
            toast("Lines cannot be added or deleted in this view"); return;
        }
        int selStart = Math.max(0, editor.getSelectionStart()), selEnd = Math.max(selStart, editor.getSelectionEnd());
        String text = editor.getText().toString();
        int blockStart = text.lastIndexOf('\n', Math.max(-1, selStart - 1)) + 1;
        int effectiveEnd = selEnd > selStart && text.charAt(selEnd - 1) == '\n' ? selEnd - 1 : selEnd;
        int blockEnd = text.indexOf('\n', effectiveEnd);
        if (blockEnd < 0) blockEnd = text.length();
        editor.requestFocus();
        if (up) {
            if (blockStart == 0) return;
            int prevEnd = blockStart - 1;
            int prevStart = text.lastIndexOf('\n', Math.max(-1, prevEnd - 1)) + 1;
            String prevLine = text.substring(prevStart, prevEnd);
            String block = text.substring(blockStart, blockEnd);
            int delta = -(prevLine.length() + 1);
            editor.getText().replace(prevStart, blockEnd, block + "\n" + prevLine);
            editor.setSelection(Math.max(0, selStart + delta), Math.max(0, selEnd + delta));
        } else {
            if (blockEnd >= text.length()) return;
            int nextStart = blockEnd + 1;
            int nextEnd = text.indexOf('\n', nextStart);
            if (nextEnd < 0) nextEnd = text.length();
            String nextLine = text.substring(nextStart, nextEnd);
            String block = text.substring(blockStart, blockEnd);
            int delta = nextLine.length() + 1;
            editor.getText().replace(blockStart, nextEnd, nextLine + "\n" + block);
            int len = editor.length();
            editor.setSelection(Math.min(len, selStart + delta), Math.min(len, selEnd + delta));
        }
        scrollToOffset(editor.getSelectionStart());
        saveViewState(); updateStatus();
    }

    private void toggleTodo() {
        boolean composing = canAddLine();
        EditorDocument doc = app.current(); if (!composing && !isEditable(doc)) return;
        EditText target = composing ? lineInput : editor;
        int selStart = Math.max(0, target.getSelectionStart()), selEnd = Math.max(selStart, target.getSelectionEnd());
        String text = target.getText().toString();
        int blockStart = text.lastIndexOf('\n', Math.max(-1, selStart - 1)) + 1;
        int effectiveEnd = selEnd > selStart && text.charAt(selEnd - 1) == '\n' ? selEnd - 1 : selEnd;
        int blockEnd = text.indexOf('\n', effectiveEnd);
        if (blockEnd < 0) blockEnd = text.length();
        String block = text.substring(blockStart, blockEnd);
        String[] rawLines = block.split("\n", -1);
        int n = rawLines.length;
        int[] markerOffsets = new int[n];
        int[] states = new int[n];
        boolean[] blanks = new boolean[n];
        boolean hasNonBlank = false;
        for (int i = 0; i < n; i++) {
            String raw = rawLines[i];
            int len = raw.endsWith("\r") ? raw.length() - 1 : raw.length();
            int ie = 0;
            while (ie < len && (raw.charAt(ie) == ' ' || raw.charAt(ie) == '\t')) ie++;
            blanks[i] = ie == len;
            if (!blanks[i]) hasNonBlank = true;
            if (ie + 1 < len && "-*+".indexOf(raw.charAt(ie)) >= 0 && raw.charAt(ie + 1) == ' ') ie += 2;
            markerOffsets[i] = ie;
            String rest = raw.substring(ie, len);
            if (rest.startsWith("[ ]")) states[i] = 1;
            else if (rest.startsWith("[X]") || rest.startsWith("[x]")) states[i] = 2;
            else if (rest.startsWith("[C]") || rest.startsWith("[c]")) states[i] = 3;
            else states[i] = 0;
        }
        boolean anyPlain = false, anyUnchecked = false, anyChecked = false;
        for (int i = 0; i < n; i++) {
            if (hasNonBlank && blanks[i]) continue;
            if (states[i] == 0) anyPlain = true;
            else if (states[i] == 1) anyUnchecked = true;
            else if (states[i] == 2) anyChecked = true;
        }
        int action = anyPlain ? 0 : anyUnchecked ? 1 : anyChecked ? 2 : 3;
        StringBuilder out = new StringBuilder(block.length() + n * 4);
        int lineOffset = blockStart;
        int newSelStart = selStart, newSelEnd = selEnd, singleCursor = selStart;
        for (int i = 0; i < n; i++) {
            String raw = rawLines[i];
            int len = raw.endsWith("\r") ? raw.length() - 1 : raw.length();
            String cr = raw.substring(len);
            String line = raw.substring(0, len);
            int ie = markerOffsets[i];
            String rest = line.substring(ie);
            boolean targetLine = !hasNonBlank || !blanks[i];
            int editFrom = lineOffset + ie, editTo = editFrom, insertLen = 0;
            String modified = line;
            if (targetLine) {
                if (action == 0 && states[i] == 0) {
                    modified = line.substring(0, ie) + "[ ] " + rest;
                    insertLen = 4;
                } else if (action == 1 && states[i] == 1) {
                    modified = line.substring(0, ie) + "[X]" + rest.substring(3);
                    editTo = editFrom + 3;
                    insertLen = 3;
                } else if (action == 2 && states[i] == 2) {
                    modified = line.substring(0, ie) + "[C]" + rest.substring(3);
                    editTo = editFrom + 3;
                    insertLen = 3;
                } else if (action == 3 && states[i] == 3) {
                    modified = line.substring(0, ie) + "[ ]" + rest.substring(3);
                    editTo = editFrom + 3;
                    insertLen = 3;
                }
            }
            if (insertLen > 0 || editTo > editFrom) {
                int removed = editTo - editFrom;
                int delta = insertLen - removed;
                if (selStart == selEnd) {
                    if (action == 0) singleCursor = Math.max(editFrom + 4, selStart + 4);
                    else singleCursor = Math.max(editFrom + 3, selStart);
                } else {
                    if (editFrom < selStart) newSelStart += editTo <= selStart ? delta : insertLen - (selStart - editFrom);
                    if (editFrom <= selEnd) newSelEnd += editTo <= selEnd ? delta : insertLen - (selEnd - editFrom);
                }
            }
            if (i > 0) out.append('\n');
            out.append(modified).append(cr);
            lineOffset += raw.length() + 1;
        }
        String changed = out.toString();
        if (target.length() - (blockEnd - blockStart) + changed.length() > TextCodec.MAX_BYTES) {
            toast("Insertion would exceed the editor size limit."); return;
        }
        target.requestFocus();
        if (!changed.equals(block)) {
            target.getText().replace(blockStart, blockEnd, changed);
        }
        int maxLen = target.length();
        if (selStart == selEnd) {
            target.setSelection(Math.min(maxLen, Math.max(0, singleCursor)));
        } else {
            int s = Math.min(maxLen, Math.max(0, newSelStart));
            int e = Math.min(maxLen, Math.max(s, newSelEnd));
            target.setSelection(s, e);
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

    private void showFind(boolean allFiles) {
        if (app.current() == null) return;
        findAllFiles = allFiles;
        findTitle.setText(allFiles ? "Find in open files" : "Find in file");
        findPanel.setVisibility(View.VISIBLE);
        updateSearchHighlight();
        find.requestFocus();
    }
    private void closeFind() {
        findPanel.setVisibility(View.GONE);
        editor.setSearchPattern(null);
        editor.requestFocus();
    }
    private void updateSearchHighlight() {
        if (editor == null) return;
        editor.setSearchPattern(findPanel != null && findPanel.getVisibility() == View.VISIBLE ? query() : null);
    }
    private Pattern query() {
        String needle = find.getText().toString(); if (needle.isEmpty()) return null;
        return Pattern.compile(Pattern.quote(needle), matchCase.isChecked() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
    private void scrollToOffset(int offset) {
        Layout layout = editor.getLayout();
        if (layout == null) return;
        editor.bringPointIntoView(offset);
        int line = layout.getLineForOffset(Math.min(editor.length(), Math.max(0, offset)));
        int top = layout.getLineTop(line) + editor.getTotalPaddingTop();
        int bottom = layout.getLineBottom(line) + editor.getTotalPaddingTop();
        int visibleHeight = editor.getHeight();
        if (visibleHeight > 0 && (top < editor.getScrollY() || bottom > editor.getScrollY() + visibleHeight)) {
            int targetY = Math.max(0, top - Math.max(0, (visibleHeight - (bottom - top)) / 2));
            editor.scrollTo(editor.getScrollX(), targetY);
        }
        saveViewState();
        editor.invalidate();
    }
    private void selectFoundMatch(EditorTab targetTab, int start, int end) {
        boolean switched = targetTab != shownTab;
        if (switched) {
            saveViewState();
            targetTab.selectionStart = start;
            targetTab.selectionEnd = end;
            app.selectedTab = targetTab.id;
            app.refresh();
        }
        updateSearchHighlight();
        editor.setSelection(start, end);
        scrollToOffset(start);
        if (switched) {
            final long tabId = targetTab.id;
            editor.post(() -> {
                if (shownTab != null && shownTab.id == tabId) {
                    editor.setSelection(Math.min(editor.length(), start), Math.min(editor.length(), end));
                    scrollToOffset(start);
                }
            });
        }
        updateStatus();
        findResult.setText(findAllFiles ? "Match · " + targetTab.document.name : "Match selected");
    }
    private void findNext(boolean forward) {
        Pattern pattern = query(); EditorTab currentTab = app.currentTab();
        if (pattern == null || currentTab == null) return;
        Matcher matcher = pattern.matcher(editor.getText());
        int start = -1, end = -1, firstStart = -1, firstEnd = -1, lastStart = -1, lastEnd = -1;
        int offset = Math.max(0, forward ? editor.getSelectionEnd() : editor.getSelectionStart());
        if (forward) {
            if (matcher.find(offset)) { start = matcher.start(); end = matcher.end(); }
            if (matcher.find(0)) { firstStart = matcher.start(); firstEnd = matcher.end(); }
        } else {
            while (matcher.find()) {
                lastStart = matcher.start(); lastEnd = matcher.end();
                if (matcher.end() <= offset) { start = matcher.start(); end = matcher.end(); }
            }
        }
        if (start >= 0) {
            selectFoundMatch(currentTab, start, end);
            return;
        }
        if (findAllFiles && app.tabs.size() > 1) {
            int curIdx = app.tabs.indexOf(currentTab);
            for (int step = 1; step < app.tabs.size(); step++) {
                int idx = forward ? (curIdx + step) % app.tabs.size() : (curIdx - step + app.tabs.size()) % app.tabs.size();
                EditorTab candidate = app.tabs.get(idx);
                String candidateText = candidate.tasksOnly && candidate.filteredBuffer != null
                        ? candidate.filteredBuffer.text()
                        : candidate.document.buffer.text();
                Matcher m = pattern.matcher(candidateText);
                int cStart = -1, cEnd = -1;
                if (forward) {
                    if (m.find(0)) { cStart = m.start(); cEnd = m.end(); }
                } else {
                    while (m.find()) { cStart = m.start(); cEnd = m.end(); }
                }
                if (cStart >= 0) {
                    selectFoundMatch(candidate, cStart, cEnd);
                    return;
                }
            }
        }
        int wrapStart = forward ? firstStart : lastStart, wrapEnd = forward ? firstEnd : lastEnd;
        if (wrapStart >= 0) selectFoundMatch(currentTab, wrapStart, wrapEnd);
        else findResult.setText("No matches");
    }
    private void replaceOne() {
        EditorDocument doc = app.current(); Pattern pattern = query(); if (!isEditable(doc) || pattern == null) return;
        if (shownTab != null && shownTab.tasksOnly && replacement.getText().toString().contains("\n")) {
            toast("Lines cannot be added or deleted in this view"); return;
        }
        int start = Math.max(0, editor.getSelectionStart()), end = Math.max(start, editor.getSelectionEnd());
        if (pattern.matcher(editor.getText().subSequence(start, end)).matches()) editor.getText().replace(start, end, replacement.getText());
        findNext(true);
    }
    private void replaceAll() {
        EditorDocument doc = app.current(); Pattern pattern = query(); if (!isEditable(doc) || pattern == null) return;
        if (shownTab != null && shownTab.tasksOnly && replacement.getText().toString().contains("\n")) {
            toast("Lines cannot be added or deleted in this view"); return;
        }
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
                            app.main.post(() -> {
                                doc.buffer = buffer; doc.savedHash = org.hopto.git23069.textpad.core.SaveTransaction.hash(bytes);
                                doc.saving = false; doc.replacing = false; shownState = -1; app.refresh();
                                if (doc.syncEnabled) {
                                    if (app.sync.testApi == null) SyncScheduler.retry(app);
                                    app.sync.syncAll(false);
                                }
                            });
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
                    if (backups.isEmpty()) { app.notice("Backups", "Previous file contents appear here after an overwrite. Recent backups are kept and older history is thinned exponentially."); return; }
                    new AlertDialog.Builder(this).setTitle("Backups · " + backups.size()).setItems(backups.stream().map(DocumentStore.Backup::name).toArray(String[]::new), (d, i) -> {
                        DocumentStore.Backup backup = backups.get(i);
                        new AlertDialog.Builder(this).setTitle(backup.originalName()).setMessage("Open this revision as a new document, then use Save As or Save to existing file to restore it.")
                                .setPositiveButton("Open revision", (dialog, w) -> app.restoreBackup(backup))
                                .setNegativeButton("Cancel", null).setNeutralButton("Delete revision", (dialog, w) -> confirmDeleteBackup(backup)).show();
                    }).setNeutralButton("Prune", (d, w) -> app.io.execute(() -> {
                        try {
                            int removed = app.store.pruneBackups(null, System.currentTimeMillis());
                            app.main.post(() -> toast(removed > 0 ? "Pruned " + removed + " older backup" + (removed == 1 ? "." : "s.") : "No backups needed pruning."));
                        } catch (Exception e) { app.main.post(() -> app.notice("Could not prune backups", e.getMessage())); }
                    })).setNegativeButton("Close", null).show();
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
    private void applySettings() { if (app.current() != null) editor.configure(app.settings, isEditable(app.current())); scheduleAutosave(); updateStatus(); }
    private void scheduleAutosave() {
        app.main.removeCallbacks(autosave);
        if (started && app.settings.autosaveSeconds() > 0) app.main.postDelayed(autosave, app.settings.autosaveSeconds() * 1000L);
    }

    private void showSync() { new SyncUi(this, app).show(); }

    void showFiles() {
        saveViewState();
        if (findPanel.getVisibility() == View.VISIBLE) closeFind();
        var keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        app.filesVisible = true; app.library.ensureScope(); app.refresh(); app.sync.configurationChanged(); app.library.refresh();
    }

    void showEditor() { app.filesVisible = false; app.refresh(); app.sync.configurationChanged(); }

    private void navigateBackFromFiles() {
        var folder = app.library.tree.get(app.library.folderId);
        if (folder == null) showEditor(); else app.library.selectFolder(folder.parentId());
    }

    private void showHashtags(boolean startOnErrands) {
        if (app.filesVisible) showEditor();
        saveViewState();
        activeHashtagUi = new HashtagUi(this, app);
        activeHashtagUi.show(startOnErrands);
    }

    void openHashtagResult(EditorTab openTab, Uri uri, Integer lineNumber) {
        saveViewState();
        if (openTab != null && app.tabs.contains(openTab)) {
            app.selectedTab = openTab.id;
            app.refresh();
            if (lineNumber != null) jumpToLineInCurrentTab(lineNumber);
        } else if (uri != null) {
            app.focusOrOpen(uri, () -> {
                if (lineNumber != null) jumpToLineInCurrentTab(lineNumber);
            });
        }
    }

    private void jumpToLineInCurrentTab(int oneBasedLine) {
        EditorTab tab = app.currentTab();
        if (tab == null || editor == null) return;
        if (tab.tasksOnly) {
            int zeroLine = Math.max(0, Math.min(oneBasedLine - 1, tab.document.buffer.lineCount() - 1));
            if (tab.taskFilterResult != null && !tab.taskFilterResult.containsLine(zeroLine)) {
                toggleTasksFilter(tab);
            }
        }
        DocumentBuffer buf = tab.document.buffer;
        int zeroLine = Math.max(0, Math.min(oneBasedLine - 1, buf.lineCount() - 1));
        int realOffset = buf.lineStart(zeroLine);
        int offset = tab.tasksOnly ? toFilteredOffset(tab, realOffset) : Math.min(editor.length(), realOffset);
        tab.selectionStart = offset;
        tab.selectionEnd = offset;
        editor.setSelection(offset);
        scrollToOffset(offset);
        final long tabId = tab.id;
        editor.post(() -> {
            if (shownTab != null && shownTab.id == tabId) {
                int clamped = Math.min(editor.length(), offset);
                editor.setSelection(clamped);
                scrollToOffset(clamped);
                updateStatus();
            }
        });
        updateStatus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
            if (app.filesVisible && (event.getKeyCode() == KeyEvent.KEYCODE_S || event.getKeyCode() == KeyEvent.KEYCODE_Z
                    || event.getKeyCode() == KeyEvent.KEYCODE_Y || event.getKeyCode() == KeyEvent.KEYCODE_W)) showEditor();
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_S -> { if (event.isShiftPressed()) saveAs(false); else if (app.current() != null) app.save(app.current(), null); return true; }
                case KeyEvent.KEYCODE_O -> { openPicker(); return true; }
                case KeyEvent.KEYCODE_N -> { saveViewState(); app.newDocument(); return true; }
                case KeyEvent.KEYCODE_F -> { if (app.filesVisible) libraryUi.focusSearch(); else showFind(event.isShiftPressed()); return true; }
                case KeyEvent.KEYCODE_Z -> { history(event.isShiftPressed()); return true; }
                case KeyEvent.KEYCODE_Y -> { history(true); return true; }
                case KeyEvent.KEYCODE_W -> { if (app.currentTab() != null) closeTab(app.currentTab()); return true; }
                case KeyEvent.KEYCODE_T -> { if (event.isAltPressed()) { toggleTasksFilter(); return true; } }
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_TAB && isEditable(app.current()) && editor.hasFocus()) { indent(!event.isShiftPressed()); return true; }
        return super.dispatchKeyEvent(event);
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private enum Icon { NEW, OPEN, MENU, PREV, NEXT, CLOSE, REPLACE, REPLACE_ALL, UNDO, REDO, FIND, FIND_ALL, HASHTAG, INDENT_LESS, INDENT_MORE, TODO, TASK_FILTER, DATE, TIME, DIVIDER, MOVE_LINE_UP, MOVE_LINE_DOWN, BOTTOM, READ_ONLY, SAVE }
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
        return iconDrawable(icon, color, SURFACE);
    }
    private Drawable iconDrawable(Icon icon, int color, int backgroundColor) {
        return new Drawable() {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                bgPaint.setColor(backgroundColor);
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
                    case FIND_ALL -> {
                        p.moveTo(ox + 4 * s, oy + 6 * s); p.lineTo(ox + 14 * s, oy + 6 * s);
                        p.moveTo(ox + 4 * s, oy + 10 * s); p.lineTo(ox + 10 * s, oy + 10 * s);
                        p.moveTo(ox + 4 * s, oy + 14 * s); p.lineTo(ox + 9 * s, oy + 14 * s);
                        canvas.drawCircle(ox + 15.5f * s, oy + 14.5f * s, 4.2f * s, strokePaint);
                        p.moveTo(ox + 18.5f * s, oy + 17.5f * s); p.lineTo(ox + 21.5f * s, oy + 20.5f * s);
                    }
                    case HASHTAG -> {
                        p.moveTo(ox + 4 * s, oy + 9 * s); p.lineTo(ox + 20 * s, oy + 9 * s);
                        p.moveTo(ox + 4 * s, oy + 15 * s); p.lineTo(ox + 20 * s, oy + 15 * s);
                        p.moveTo(ox + 10 * s, oy + 4 * s); p.lineTo(ox + 8 * s, oy + 20 * s);
                        p.moveTo(ox + 16 * s, oy + 4 * s); p.lineTo(ox + 14 * s, oy + 20 * s);
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
                    case TASK_FILTER -> {
                        canvas.drawRoundRect(new RectF(ox + 3 * s, oy + 5 * s, ox + 9 * s, oy + 11 * s), 1 * s, 1 * s, strokePaint);
                        p.moveTo(ox + 12 * s, oy + 8 * s); p.lineTo(ox + 21 * s, oy + 8 * s);
                        canvas.drawRoundRect(new RectF(ox + 3 * s, oy + 13 * s, ox + 9 * s, oy + 19 * s), 1 * s, 1 * s, strokePaint);
                        p.moveTo(ox + 12 * s, oy + 16 * s); p.lineTo(ox + 21 * s, oy + 16 * s);
                    }
                    case TIME -> {
                        canvas.drawCircle(ox + 12 * s, oy + 12 * s, 9 * s, strokePaint);
                        p.moveTo(ox + 12 * s, oy + 7 * s); p.lineTo(ox + 12 * s, oy + 12 * s); p.lineTo(ox + 15.5f * s, oy + 14 * s);
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
                    case MOVE_LINE_UP -> {
                        p.moveTo(ox + 4 * s, oy + 7 * s); p.lineTo(ox + 11 * s, oy + 7 * s);
                        p.moveTo(ox + 4 * s, oy + 12 * s); p.lineTo(ox + 11 * s, oy + 12 * s);
                        p.moveTo(ox + 4 * s, oy + 17 * s); p.lineTo(ox + 11 * s, oy + 17 * s);
                        p.moveTo(ox + 17 * s, oy + 19 * s); p.lineTo(ox + 17 * s, oy + 5 * s);
                        p.moveTo(ox + 13.5f * s, oy + 8.5f * s); p.lineTo(ox + 17 * s, oy + 5 * s); p.lineTo(ox + 20.5f * s, oy + 8.5f * s);
                    }
                    case MOVE_LINE_DOWN -> {
                        p.moveTo(ox + 4 * s, oy + 7 * s); p.lineTo(ox + 11 * s, oy + 7 * s);
                        p.moveTo(ox + 4 * s, oy + 12 * s); p.lineTo(ox + 11 * s, oy + 12 * s);
                        p.moveTo(ox + 4 * s, oy + 17 * s); p.lineTo(ox + 11 * s, oy + 17 * s);
                        p.moveTo(ox + 17 * s, oy + 5 * s); p.lineTo(ox + 17 * s, oy + 19 * s);
                        p.moveTo(ox + 13.5f * s, oy + 15.5f * s); p.lineTo(ox + 17 * s, oy + 19 * s); p.lineTo(ox + 20.5f * s, oy + 15.5f * s);
                    }
                    case BOTTOM -> {
                        p.moveTo(ox + 12 * s, oy + 4 * s); p.lineTo(ox + 12 * s, oy + 16 * s);
                        p.moveTo(ox + 7 * s, oy + 11 * s); p.lineTo(ox + 12 * s, oy + 16 * s); p.lineTo(ox + 17 * s, oy + 11 * s);
                        p.moveTo(ox + 5 * s, oy + 20 * s); p.lineTo(ox + 19 * s, oy + 20 * s);
                    }
                    case READ_ONLY -> {
                        canvas.drawRoundRect(new RectF(ox + 5 * s, oy + 11 * s, ox + 19 * s, oy + 21 * s), 2 * s, 2 * s, strokePaint);
                        p.moveTo(ox + 8 * s, oy + 11 * s); p.lineTo(ox + 8 * s, oy + 7.5f * s);
                        p.arcTo(new RectF(ox + 8 * s, oy + 3.5f * s, ox + 16 * s, oy + 11.5f * s), 180f, 180f, false);
                        p.lineTo(ox + 16 * s, oy + 11 * s);
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

    void toggleTasksFilter() {
        if (app.currentTab() != null) {
            toggleTasksFilter(app.currentTab());
        }
    }

    void toggleTasksFilter(EditorTab tab) {
        if (tab == null) return;
        saveViewState();
        if (tab.tasksOnly) {
            int currentFilteredOffset = tab.selectionStart;
            int newRealOffset = toRealOffset(tab, currentFilteredOffset);
            tab.tasksOnly = false;
            tab.taskFilterResult = null;
            tab.filteredBuffer = null;
            tab.selectionStart = newRealOffset;
            tab.selectionEnd = newRealOffset;
        } else {
            int currentRealOffset = tab.selectionStart;
            TaskFilter.FilterResult res = TaskFilter.filter(tab.document.buffer.text(), app.settings.indent());
            String filteredText = buildFilteredText(tab.document.buffer, res.visibleLines());
            tab.taskFilterResult = res;
            tab.filteredBuffer = new DocumentBuffer(TextCodec.split(filteredText, tab.document.buffer.encoding(), false));
            tab.tasksOnly = true;
            int newFilteredOffset = toFilteredOffset(tab, currentRealOffset);
            tab.selectionStart = newFilteredOffset;
            tab.selectionEnd = newFilteredOffset;
        }
        if (shownTab == tab) {
            shownState = -1;
            refresh(false);
            if (!tab.tasksOnly) {
                // Run after refresh restores the scroll position from the filtered view.
                editor.post(() -> {
                    if (shownTab == tab && !tab.tasksOnly) {
                        editor.bringPointIntoView(Math.max(0, editor.getSelectionStart()));
                        saveViewState();
                    }
                });
            }
            updateStatus();
        }
    }

    private int toRealOffset(EditorTab tab, int filteredOffset) {
        if (tab == null || tab.filteredBuffer == null || tab.taskFilterResult == null || tab.taskFilterResult.visibleLines().length == 0) {
            return tab != null ? Math.max(0, Math.min(filteredOffset, tab.document.buffer.length())) : 0;
        }
        int clampedFiltered = Math.max(0, Math.min(filteredOffset, tab.filteredBuffer.length()));
        int v = tab.filteredBuffer.lineAt(clampedFiltered);
        if (v >= tab.taskFilterResult.visibleLines().length) {
            v = tab.taskFilterResult.visibleLines().length - 1;
        }
        int col = clampedFiltered - tab.filteredBuffer.lineStart(v);
        int realLine = tab.taskFilterResult.visibleLines()[v];
        int realLineStart = tab.document.buffer.lineStart(realLine);
        int realLineEnd = realLine + 1 < tab.document.buffer.lineCount() ? tab.document.buffer.lineStart(realLine + 1) - 1 : tab.document.buffer.length();
        int realCol = Math.min(col, Math.max(0, realLineEnd - realLineStart));
        return realLineStart + realCol;
    }

    private int toFilteredOffset(EditorTab tab, int realOffset) {
        if (tab == null || tab.filteredBuffer == null || tab.taskFilterResult == null || tab.taskFilterResult.visibleLines().length == 0) {
            return 0;
        }
        int clampedReal = Math.max(0, Math.min(realOffset, tab.document.buffer.length()));
        int realLine = tab.document.buffer.lineAt(clampedReal);
        int realLineStart = tab.document.buffer.lineStart(realLine);
        int col = clampedReal - realLineStart;
        int v = tab.taskFilterResult.indexOf(realLine);
        if (v < 0) {
            int ip = -v - 1;
            if (ip >= tab.taskFilterResult.visibleLines().length) {
                ip = tab.taskFilterResult.visibleLines().length - 1;
            }
            if (ip < 0) ip = 0;
            return tab.filteredBuffer.lineStart(ip);
        }
        int filteredLineStart = tab.filteredBuffer.lineStart(v);
        int filteredLineEnd = v + 1 < tab.filteredBuffer.lineCount() ? tab.filteredBuffer.lineStart(v + 1) - 1 : tab.filteredBuffer.length();
        int filteredCol = Math.min(col, Math.max(0, filteredLineEnd - filteredLineStart));
        return filteredLineStart + filteredCol;
    }

    private int toRealLine(EditorTab tab, int offset) {
        if (tab == null || tab.filteredBuffer == null || tab.taskFilterResult == null || tab.taskFilterResult.visibleLines().length == 0) {
            return tab != null ? tab.document.buffer.lineAt(offset) : 0;
        }
        int clamped = Math.max(0, Math.min(offset, tab.filteredBuffer.length()));
        int v = tab.filteredBuffer.lineAt(clamped);
        if (v < tab.taskFilterResult.visibleLines().length) {
            return tab.taskFilterResult.visibleLines()[v];
        }
        return tab.document.buffer.lineCount() - 1;
    }

    private int toRealColumn(EditorTab tab, int offset) {
        if (tab == null || tab.filteredBuffer == null) {
            return 1;
        }
        int clamped = Math.max(0, Math.min(offset, tab.filteredBuffer.length()));
        int v = tab.filteredBuffer.lineAt(clamped);
        return clamped - tab.filteredBuffer.lineStart(v) + 1;
    }

    private String buildFilteredText(DocumentBuffer buf, int[] visibleLines) {
        if (buf == null || visibleLines == null || visibleLines.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String fullText = buf.text();
        int lineCount = buf.lineCount();
        for (int i = 0; i < visibleLines.length; i++) {
            int line = visibleLines[i];
            int start = buf.lineStart(line);
            int end = (line + 1 < lineCount) ? buf.lineStart(line + 1) - 1 : fullText.length();
            if (end < start) end = start;
            if (i > 0) sb.append('\n');
            sb.append(fullText, start, end);
        }
        return sb.toString();
    }

    private CharSequence buildFilteredSpanned(String filteredText, TaskFilter.FilterResult res) {
        if (res == null || filteredText == null || filteredText.isEmpty()) {
            return filteredText == null ? "" : filteredText;
        }
        SpannableStringBuilder ssb = new SpannableStringBuilder(filteredText);
        int[] visible = res.visibleLines();
        int offset = 0;
        for (int v = 0; v < visible.length; v++) {
            int lineEnd = filteredText.indexOf('\n', offset);
            if (lineEnd == -1) lineEnd = filteredText.length();
            int realLine = visible[v];
            boolean isContext = realLine < res.isContext().length && res.isContext()[realLine];
            if (isContext || TaskFilter.isClosedTask(filteredText.substring(offset, lineEnd))) {
                ssb.setSpan(new ForegroundColorSpan(0x88e3e8df), offset, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            offset = lineEnd + 1;
            if (offset > filteredText.length()) break;
        }
        return ssb;
    }
}
