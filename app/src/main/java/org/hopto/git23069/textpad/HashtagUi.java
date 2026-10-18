package org.hopto.git23069.textpad;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.hopto.git23069.textpad.core.Hashtags;
import org.hopto.git23069.textpad.core.TextCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class HashtagUi {
    private static final int BG = 0xff101313, SURFACE = 0xff19201d, SURFACE_ALT = 0xff222b26,
            TEXT = 0xffe3e8df, MUTED = 0xff94a298, ACCENT = 0xffa9d98b, BORDER = 0xff29322c;

    record ScannedFile(EditorTab openTab, Uri uri, String name, String locationLabel, String text) {}
    record FileResult(ScannedFile file, Hashtags.FileMatch match) {}

    private final MainActivity activity;
    private final TextpadApplication app;
    private final AtomicInteger searchGen = new AtomicInteger();

    private boolean errandsTab;
    private boolean fileMatchMode;
    private boolean hideCompleted; // default false
    private boolean editingErrands;
    private String searchQuery = "";
    private List<String> discoveredTags = List.of();
    private List<FileResult> currentResults = List.of();
    private boolean searching;

    private AlertDialog dialog;
    private Button searchTabBtn, errandsTabBtn, lineModeBtn, fileModeBtn;
    private LinearLayout modeContainer, resultsContainer, chipsRow, errandsChipsRow;
    private EditText searchInput, errandsEditInput;
    private TextView statusLabel;

    HashtagUi(MainActivity activity, TextpadApplication app) {
        this.activity = activity;
        this.app = app;
    }

    private boolean alive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    void show(boolean startOnErrands) {
        this.errandsTab = startOnErrands;
        if (startOnErrands && app.settings.errandsHashtags().isEmpty()) {
            this.editingErrands = true;
        }
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        // Header row
        LinearLayout header = row();
        TextView title = label("Hashtags & Errands", 18, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button folderBtn = pillButton("Folders…", false, v -> showFoldersDialog());
        header.addView(folderBtn);
        root.addView(header);

        // Top tab bar: Search vs Errands
        LinearLayout tabsRow = row();
        tabsRow.setPadding(0, dp(8), 0, dp(8));
        searchTabBtn = pillButton("Search", !errandsTab, v -> switchTab(false));
        errandsTabBtn = pillButton(errandsTabLabel(), errandsTab, v -> switchTab(true));
        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, dp(38), 1);
        tabLp1.setMarginEnd(dp(4));
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, dp(38), 1);
        tabLp2.setMarginStart(dp(4));
        tabsRow.addView(searchTabBtn, tabLp1);
        tabsRow.addView(errandsTabBtn, tabLp2);
        root.addView(tabsRow);

        // Dynamic controls container (Search input OR Errands viewer/editor)
        modeContainer = column();
        root.addView(modeContainer);

        // Match mode + hide completed/cancelled row
        LinearLayout filterRow = row();
        filterRow.setPadding(0, dp(6), 0, dp(6));
        lineModeBtn = pillButton("Line matching", !fileMatchMode, v -> {
            fileMatchMode = false;
            updateFilterButtons();
            renderResults();
        });
        fileModeBtn = pillButton("File matching", fileMatchMode, v -> {
            fileMatchMode = true;
            updateFilterButtons();
            renderResults();
        });
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(-2, dp(34));
        modeLp.setMarginEnd(dp(6));
        filterRow.addView(lineModeBtn, modeLp);
        filterRow.addView(fileModeBtn, modeLp);

        View spacer = new View(activity);
        filterRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        CheckBox hideBox = new CheckBox(activity);
        hideBox.setText("Hide [X] / [C]");
        hideBox.setTextColor(TEXT);
        hideBox.setTextSize(12);
        hideBox.setChecked(hideCompleted);
        hideBox.setOnCheckedChangeListener((b, checked) -> {
            hideCompleted = checked;
            runSearch();
        });
        filterRow.addView(hideBox);
        root.addView(filterRow);

        statusLabel = label("Scanning open, recent, synced, and same-folder files…", 11, MUTED);
        statusLabel.setPadding(0, dp(2), 0, dp(6));
        root.addView(statusLabel);

        View div = new View(activity);
        div.setBackgroundColor(BORDER);
        root.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));

        // Scrollable results area
        ScrollView resultsScroll = new ScrollView(activity);
        resultsScroll.setFillViewport(true);
        resultsContainer = column();
        resultsContainer.setPadding(0, dp(8), 0, dp(8));
        resultsScroll.addView(resultsContainer);
        root.addView(resultsScroll, new LinearLayout.LayoutParams(-1, dp(360), 1));

        rebuildModeControls();

        dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // Sync errands in background when opening dialog, then refresh if needed
        app.sync.syncErrandsInBackground(() -> {
            if (!alive() || dialog == null || !dialog.isShowing()) return;
            errandsTabBtn.setText(errandsTabLabel());
            if (errandsTab && !editingErrands) {
                rebuildModeControls();
                runSearch();
            }
        });

        runSearch();
    }

    void onFolderTreeGranted() {
        if (alive() && dialog != null && dialog.isShowing()) {
            runSearch();
        }
    }

    private String errandsTabLabel() {
        int count = app.settings.errandsHashtags().size();
        return count > 0 ? "Errands (" + count + ")" : "Errands";
    }

    private void switchTab(boolean toErrands) {
        this.errandsTab = toErrands;
        if (toErrands && app.settings.errandsHashtags().isEmpty()) {
            this.editingErrands = true;
        } else {
            this.editingErrands = false;
        }
        stylePill(searchTabBtn, !errandsTab);
        stylePill(errandsTabBtn, errandsTab);
        errandsTabBtn.setText(errandsTabLabel());
        rebuildModeControls();
        runSearch();
    }

    private void updateFilterButtons() {
        stylePill(lineModeBtn, !fileMatchMode);
        stylePill(fileModeBtn, fileMatchMode);
    }

    private void rebuildModeControls() {
        modeContainer.removeAllViews();
        if (!errandsTab) {
            searchInput = new EditText(activity);
            searchInput.setHint("Search hashtags (e.g. #chemist #bunnings)…");
            searchInput.setTextColor(TEXT);
            searchInput.setHintTextColor(MUTED);
            searchInput.setTextSize(14);
            searchInput.setSingleLine(true);
            searchInput.setBackground(rounded(SURFACE, 8, BORDER));
            searchInput.setPadding(dp(12), dp(8), dp(12), dp(8));
            searchInput.setText(searchQuery);
            searchInput.setSelection(searchInput.getText().length());
            searchInput.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(Editable s) {
                    searchQuery = s.toString();
                    updateDiscoveredChips();
                    runSearch();
                }
            });
            modeContainer.addView(searchInput, new LinearLayout.LayoutParams(-1, -2));

            HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipsRow = row();
            chipsRow.setPadding(0, dp(6), 0, dp(2));
            chipScroll.addView(chipsRow);
            modeContainer.addView(chipScroll, new LinearLayout.LayoutParams(-1, -2));
            updateDiscoveredChips();
        } else {
            LinearLayout box = column();
            box.setBackground(rounded(SURFACE, 8, BORDER));
            box.setPadding(dp(10), dp(8), dp(10), dp(8));

            LinearLayout boxHeader = row();
            TextView subtitle = label("Errands hashtags (matches any)", 12, MUTED);
            boxHeader.addView(subtitle, new LinearLayout.LayoutParams(0, -2, 1));
            if (!editingErrands) {
                Button editBtn = pillButton("Edit", false, v -> {
                    editingErrands = true;
                    rebuildModeControls();
                });
                boxHeader.addView(editBtn, new LinearLayout.LayoutParams(-2, dp(30)));
            }
            box.addView(boxHeader);

            List<String> currentErrands = app.settings.errandsHashtags();
            if (editingErrands) {
                errandsEditInput = new EditText(activity);
                errandsEditInput.setHint("#chemist #bunnings #postoffice #groceries");
                errandsEditInput.setTextColor(TEXT);
                errandsEditInput.setHintTextColor(MUTED);
                errandsEditInput.setTextSize(14);
                errandsEditInput.setMinLines(2);
                errandsEditInput.setMaxLines(4);
                errandsEditInput.setGravity(Gravity.TOP | Gravity.START);
                errandsEditInput.setBackground(rounded(BG, 6, BORDER));
                errandsEditInput.setPadding(dp(10), dp(8), dp(10), dp(8));
                errandsEditInput.setText(Hashtags.formatTagList(currentErrands));
                errandsEditInput.setSelection(errandsEditInput.getText().length());
                LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
                inputLp.setMargins(0, dp(6), 0, dp(6));
                box.addView(errandsEditInput, inputLp);

                HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
                chipScroll.setHorizontalScrollBarEnabled(false);
                errandsChipsRow = row();
                chipScroll.addView(errandsChipsRow);
                box.addView(chipScroll, new LinearLayout.LayoutParams(-1, -2));
                updateErrandsEditChips();

                errandsEditInput.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    public void afterTextChanged(Editable s) { updateErrandsEditChips(); }
                });

                LinearLayout actionsRow = row();
                actionsRow.setGravity(Gravity.END);
                actionsRow.setPadding(0, dp(6), 0, 0);
                Button cancelBtn = pillButton("Cancel", false, v -> {
                    editingErrands = false;
                    rebuildModeControls();
                });
                Button saveBtn = pillButton("Save Errands", true, v -> saveErrands());
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, dp(34));
                cLp.setMarginEnd(dp(6));
                actionsRow.addView(cancelBtn, cLp);
                actionsRow.addView(saveBtn, new LinearLayout.LayoutParams(-2, dp(34)));
                box.addView(actionsRow);
            } else if (!currentErrands.isEmpty()) {
                HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
                chipScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout tagList = row();
                tagList.setPadding(0, dp(6), 0, dp(2));
                for (String tag : currentErrands) {
                    Button chip = pillButton("#" + tag, true, null);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30));
                    lp.setMarginEnd(dp(6));
                    tagList.addView(chip, lp);
                }
                chipScroll.addView(tagList);
                box.addView(chipScroll);
            } else {
                TextView emptyMsg = label("No errands hashtags configured yet. Tap Edit to add tags like #chemist or #bunnings.", 12, MUTED);
                emptyMsg.setPadding(0, dp(6), 0, dp(2));
                box.addView(emptyMsg);
            }
            modeContainer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void saveErrands() {
        String raw = errandsEditInput != null ? errandsEditInput.getText().toString() : "";
        List<String> parsed = Hashtags.parseTagList(raw);
        app.settings.setErrandsHashtags(parsed, app.settings.errandsUpdatedAt(), true);
        editingErrands = false;
        errandsTabBtn.setText(errandsTabLabel());
        rebuildModeControls();
        runSearch();
        app.sync.syncErrandsInBackground(null);
    }

    private void updateDiscoveredChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        List<String> active = Hashtags.parseTagList(searchQuery);
        for (String tag : discoveredTags) {
            boolean selected = active.contains(tag);
            Button chip = pillButton("#" + tag, selected, v -> {
                List<String> cur = new ArrayList<>(Hashtags.parseTagList(searchQuery));
                if (cur.contains(tag)) cur.remove(tag);
                else cur.add(tag);
                searchQuery = Hashtags.formatTagList(cur);
                if (searchInput != null) {
                    searchInput.setText(searchQuery);
                    searchInput.setSelection(searchInput.getText().length());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30));
            lp.setMarginEnd(dp(6));
            chipsRow.addView(chip, lp);
        }
    }

    private void updateErrandsEditChips() {
        if (errandsChipsRow == null || errandsEditInput == null) return;
        errandsChipsRow.removeAllViews();
        List<String> draft = Hashtags.parseTagList(errandsEditInput.getText().toString());
        for (String tag : discoveredTags) {
            boolean selected = draft.contains(tag);
            Button chip = pillButton("#" + tag, selected, v -> {
                List<String> cur = new ArrayList<>(Hashtags.parseTagList(errandsEditInput.getText().toString()));
                if (cur.contains(tag)) cur.remove(tag);
                else cur.add(tag);
                String formatted = Hashtags.formatTagList(cur);
                errandsEditInput.setText(formatted);
                errandsEditInput.setSelection(formatted.length());
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30));
            lp.setMarginEnd(dp(6));
            errandsChipsRow.addView(chip, lp);
        }
    }

    private void runSearch() {
        final int gen = searchGen.incrementAndGet();
        final List<String> targetTags = errandsTab
                ? app.settings.errandsHashtags()
                : Hashtags.parseTagList(searchQuery);
        final boolean hideDone = this.hideCompleted;

        // Snapshot open tabs on the main thread first
        final List<ScannedFile> openSnap = new ArrayList<>();
        final Set<EditorDocument> seenDocs = new LinkedHashSet<>();
        final Set<String> seenIdentities = new LinkedHashSet<>();
        final List<Uri> openUris = new ArrayList<>();
        for (EditorTab tab : app.tabs) {
            EditorDocument doc = tab.document;
            if (!seenDocs.add(doc)) continue;
            if (doc.uri != null) {
                seenIdentities.add(DocumentStore.identity(doc.uri));
                openUris.add(doc.uri);
            }
            String loc = doc.uri == null ? "Open tab · Unsaved" : "Open tab · " + app.store.location(doc.uri, doc.name);
            openSnap.add(new ScannedFile(tab, doc.uri, doc.name, loc, doc.buffer.text()));
        }
        final List<Uri> searchTrees = app.settings.searchTrees();

        searching = true;
        statusLabel.setText("Searching open, recent, synced, and same-folder files…");

        app.io.execute(() -> {
            List<ScannedFile> allFiles = new ArrayList<>(openSnap);

            // Collect candidate URIs from Recents, Synced bindings, and Same-directory / Folder trees
            Map<String, DocumentStore.Recent> diskCandidates = new LinkedHashMap<>();
            for (DocumentStore.Recent r : app.store.recents()) {
                if (r.uri() != null) {
                    String id = DocumentStore.identity(r.uri());
                    if (!seenIdentities.contains(id)) diskCandidates.putIfAbsent(id, r);
                }
            }
            for (SyncManager.Binding b : app.sync.bindings()) {
                if (b.uri() != null) {
                    String id = DocumentStore.identity(b.uri());
                    if (!seenIdentities.contains(id)) diskCandidates.putIfAbsent(id, new DocumentStore.Recent(b.name(), b.uri()));
                }
            }
            for (DocumentStore.Recent sib : app.store.discoverSiblingAndTreeFiles(openUris, searchTrees)) {
                if (sib.uri() != null) {
                    String id = DocumentStore.identity(sib.uri());
                    if (!seenIdentities.contains(id)) diskCandidates.putIfAbsent(id, sib);
                }
            }

            for (DocumentStore.Recent candidate : diskCandidates.values()) {
                if (searchGen.get() != gen) return;
                try {
                    byte[] bytes = app.store.read(candidate.uri());
                    String text = TextCodec.decode(bytes).text();
                    String loc = app.store.location(candidate.uri(), candidate.name());
                    allFiles.add(new ScannedFile(null, candidate.uri(), candidate.name(), loc, text));
                } catch (Exception ignored) {
                    // Skip unreadable, expired-permission, or binary files cleanly.
                }
            }

            Set<String> allFoundTags = new LinkedHashSet<>();
            List<FileResult> matches = new ArrayList<>();
            for (ScannedFile sf : allFiles) {
                if (searchGen.get() != gen) return;
                allFoundTags.addAll(Hashtags.extractHashtags(sf.text()));
                if (!targetTags.isEmpty()) {
                    Hashtags.FileMatch fm = Hashtags.search(sf.text(), targetTags, hideDone);
                    if (fm.matches()) {
                        matches.add(new FileResult(sf, fm));
                    }
                }
            }

            final List<String> finalDiscovered = new ArrayList<>(allFoundTags);
            final int totalScanned = allFiles.size();
            app.main.post(() -> {
                if (searchGen.get() != gen || !alive()) return;
                searching = false;
                discoveredTags = finalDiscovered;
                currentResults = matches;
                if (!errandsTab) updateDiscoveredChips();
                else if (editingErrands) updateErrandsEditChips();
                statusLabel.setText("Scanned " + totalScanned + (totalScanned == 1 ? " file" : " files")
                        + " · " + matches.size() + " matching " + (matches.size() == 1 ? "file" : "files"));
                renderResults();
            });
        });
    }

    private void renderResults() {
        if (resultsContainer == null) return;
        resultsContainer.removeAllViews();

        List<String> targetTags = errandsTab
                ? app.settings.errandsHashtags()
                : Hashtags.parseTagList(searchQuery);

        if (searching && currentResults.isEmpty()) {
            resultsContainer.addView(emptyState("Searching files…"));
            return;
        }
        if (errandsTab && targetTags.isEmpty()) {
            resultsContainer.addView(emptyState("Save your errands hashtags above to view matching lines or files."));
            return;
        }
        if (!errandsTab && targetTags.isEmpty()) {
            resultsContainer.addView(emptyState("Tap a hashtag chip or type one or more hashtags above to search."));
            return;
        }
        if (currentResults.isEmpty()) {
            resultsContainer.addView(emptyState("No matching " + (fileMatchMode ? "files" : "lines") + " found."));
            return;
        }

        for (FileResult res : currentResults) {
            LinearLayout card = column();
            card.setBackground(rounded(SURFACE, 8, BORDER));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.setMargins(0, 0, 0, dp(8));
            card.setLayoutParams(cardLp);

            // File header button
            LinearLayout fileHeader = column();
            fileHeader.setPadding(dp(12), dp(10), dp(12), dp(10));
            fileHeader.setBackground(rounded(SURFACE_ALT, 8, 0));
            fileHeader.setOnClickListener(v -> openResult(res.file(), null));

            LinearLayout titleRow = row();
            TextView nameView = label(res.file().name(), 14, ACCENT);
            nameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            titleRow.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));

            int lineCount = res.match().lines().size();
            String meta = fileMatchMode
                    ? Hashtags.formatTagList(res.match().matchedTags()) + " · " + lineCount + (lineCount == 1 ? " line" : " lines")
                    : lineCount + (lineCount == 1 ? " line" : " lines");
            TextView countView = label(meta, 11, MUTED);
            titleRow.addView(countView);
            fileHeader.addView(titleRow);

            TextView locView = label(res.file().locationLabel(), 11, MUTED);
            locView.setPadding(0, dp(2), 0, 0);
            fileHeader.addView(locView);
            card.addView(fileHeader);

            // In line-matching mode, render every matching line in full without cutting off or truncating!
            if (!fileMatchMode) {
                for (Hashtags.LineMatch lineMatch : res.match().lines()) {
                    View lineDiv = new View(activity);
                    lineDiv.setBackgroundColor(BORDER);
                    card.addView(lineDiv, new LinearLayout.LayoutParams(-1, dp(1)));

                    TextView lineView = new TextView(activity);
                    String prefix = "Ln " + lineMatch.lineNumber() + "  ";
                    SpannableStringBuilder sb = new SpannableStringBuilder(prefix).append(lineMatch.lineText());
                    sb.setSpan(new ForegroundColorSpan(ACCENT), 0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    lineView.setText(sb);
                    lineView.setTextColor(TEXT);
                    lineView.setTextSize(13);
                    lineView.setTypeface(Typeface.MONOSPACE);
                    lineView.setSingleLine(false); // Never cut off or truncate matching lines
                    lineView.setPadding(dp(12), dp(10), dp(12), dp(10));
                    lineView.setOnClickListener(v -> openResult(res.file(), lineMatch.lineNumber()));
                    card.addView(lineView, new LinearLayout.LayoutParams(-1, -2));
                }
            }

            resultsContainer.addView(card);
        }
    }

    private void openResult(ScannedFile file, Integer lineNumber) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        activity.openHashtagResult(file.openTab(), file.uri(), lineNumber);
    }

    private void showFoldersDialog() {
        List<Uri> trees = app.settings.searchTrees();
        LinearLayout box = column();
        box.setPadding(dp(20), dp(12), dp(20), dp(8));
        TextView info = label("Textpad searches open files, recent files, synced files, and accessible files in the same folders as open files.\n\n"
                + "On Android, granting a folder lets Textpad also scan unopened sibling files in that directory.", 13, MUTED);
        box.addView(info);

        if (!trees.isEmpty()) {
            TextView heading = label("GRANTED FOLDERS (TAP TO REMOVE)", 11, ACCENT);
            heading.setPadding(0, dp(12), 0, dp(6));
            box.addView(heading);
            for (Uri tree : trees) {
                Button item = pillButton(tree.getLastPathSegment() == null ? tree.toString() : tree.getLastPathSegment(), false, null);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, 0, 0, dp(6));
                box.addView(item, lp);
                item.setOnClickListener(v -> {
                    app.settings.removeSearchTree(tree);
                    box.removeView(item);
                    runSearch();
                });
            }
        }

        new AlertDialog.Builder(activity)
                .setTitle("Search folders")
                .setView(box)
                .setPositiveButton("Add folder…", (d, w) -> activity.launchSearchTreePicker())
                .setNegativeButton("Done", null)
                .show();
    }

    private TextView emptyState(String text) {
        TextView tv = label(text, 13, MUTED);
        tv.setPadding(dp(8), dp(16), dp(8), dp(16));
        return tv;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView label(String text, int size, int color) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private Button pillButton(String text, boolean active, View.OnClickListener click) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        stylePill(b, active);
        if (click != null) b.setOnClickListener(click);
        return b;
    }

    private void stylePill(Button b, boolean active) {
        b.setTextColor(active ? BG : TEXT);
        b.setBackground(rounded(active ? ACCENT : SURFACE, 8, active ? ACCENT : BORDER));
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
