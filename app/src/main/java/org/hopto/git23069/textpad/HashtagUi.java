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
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.hopto.git23069.textpad.core.Hashtags;
import org.hopto.git23069.textpad.core.TextCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    private boolean hideCompleted = true;
    private boolean editingErrands;
    private String searchQuery = "";
    private List<String> discoveredTags = List.of();
    private List<FileResult> currentResults = List.of();
    private boolean searching;
    private List<String> errandsTags;
    private List<TagGroup> groups;
    private String selectedGroupId = "";

    private AlertDialog dialog;
    private Button searchTabBtn, errandsTabBtn, lineModeBtn, fileModeBtn;
    private LinearLayout modeContainer;
    private RecyclerView resultsList;
    private final ResultsAdapter resultsAdapter = new ResultsAdapter();
    private TagChipAdapter queryChips, errandsEditChips;
    private EditText searchInput, errandsEditInput;
    private TextView statusLabel;

    HashtagUi(MainActivity activity, TextpadApplication app) {
        this.activity = activity;
        this.app = app;
        this.errandsTags = app.settings.errandsHashtags();
        this.groups = app.settings.tagGroups();
    }

    private boolean alive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    AlertDialog show(boolean startOnErrands) {
        this.errandsTab = startOnErrands;
        if (startOnErrands && errandsTags.isEmpty()) {
            this.editingErrands = true;
        }
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        // Header row
        LinearLayout header = row();
        TextView title = label("Hashtags & Tag groups", 18, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button folderBtn = pillButton("Folders…", false, v -> showFoldersDialog());
        header.addView(folderBtn);
        root.addView(header);

        // Top tab bar: Search vs Errands
        LinearLayout tabsRow = row();
        tabsRow.setPadding(0, dp(8), 0, dp(8));
        searchTabBtn = pillButton("Search", !errandsTab, v -> switchTab(false));
        errandsTabBtn = pillButton("Tag groups", errandsTab, v -> switchTab(true));
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
        hideBox.setId(R.id.hashtag_hide_completed);
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
        resultsList = new RecyclerView(activity);
        resultsList.setId(R.id.hashtag_results);
        resultsList.setLayoutManager(new LinearLayoutManager(activity));
        resultsList.setItemAnimator(null);
        resultsList.setPadding(0, dp(8), 0, dp(8));
        resultsList.setAdapter(resultsAdapter);
        root.addView(resultsList, new LinearLayout.LayoutParams(-1, dp(360), 1));

        rebuildModeControls();

        dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
        dialog.setOnDismissListener(d -> searchGen.incrementAndGet());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // Search local files first; settings synchronization shares the I/O executor.
        runSearch();
        app.sync.syncHashtagSettingsInBackground(() -> {
            if (!alive() || dialog == null || !dialog.isShowing()) return;
            List<String> syncedTags = app.settings.errandsHashtags();
            List<TagGroup> syncedGroups = app.settings.tagGroups();
            if (errandsTags.equals(syncedTags) && groups.equals(syncedGroups)) return;
            errandsTags = syncedTags;
            groups = syncedGroups;
            if (!selectedGroupId.isEmpty() && selectedGroup() == null) {
                selectedGroupId = "";
                editingErrands = false;
            }
            if (errandsTab && !editingErrands) {
                rebuildModeControls();
                runSearch();
            }
        });

        return dialog;
    }

    void onFolderTreeGranted() {
        if (alive() && dialog != null && dialog.isShowing()) {
            runSearch();
        }
    }

    private String errandsTabLabel() {
        int count = selectedTags().size();
        return selectedName() + (count > 0 ? " (" + count + ")" : "");
    }

    private TagGroup selectedGroup() {
        for (TagGroup group : groups) if (group.id().equals(selectedGroupId)) return group;
        return null;
    }

    private String selectedName() {
        TagGroup group = selectedGroup();
        return group == null ? "Errands" : group.name();
    }

    private List<String> selectedTags() {
        TagGroup group = selectedGroup();
        return group == null ? errandsTags : group.hashtags();
    }

    private void chooseGroup() {
        List<TagGroup> choices = new ArrayList<>(groups);
        choices.add(new TagGroup("", "Errands", errandsTags, "", false, false, ""));
        choices.sort(TagGroup.ORDER);
        String[] names = choices.stream().map(g -> g.name() + " (" + g.hashtags().size() + ")").toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("Tag groups")
                .setItems(names, (d, which) -> {
                    selectedGroupId = choices.get(which).id();
                    switchTab(true);
                }).setNegativeButton("Cancel", null).show();
    }

    private void nameGroup(boolean create) {
        TagGroup original = selectedGroup();
        if (!create && original == null) return;
        EditText input = new EditText(activity);
        input.setId(R.id.tag_group_name);
        input.setSingleLine(true);
        input.setHint("Group name");
        input.setText(create ? "" : original.name());
        AlertDialog naming = new AlertDialog.Builder(activity).setTitle(create ? "New tag group" : "Rename group")
                .setView(input).setNegativeButton("Cancel", null).setPositiveButton(create ? "Create" : "Rename", null).create();
        naming.setOnShowListener(d -> naming.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String name = TagGroup.normalizeName(input.getText().toString());
                for (TagGroup group : groups) {
                    if ((create || !group.id().equals(original.id())) && group.name().equalsIgnoreCase(name))
                        throw new IllegalArgumentException("A tag group with that name already exists.");
                }
                TagGroup group = create ? new TagGroup(UUID.randomUUID().toString(), name, List.of(), "", false, false, "") : original;
                group = group.edit(name, group.hashtags());
                app.settings.saveTagGroup(group);
                groups = app.settings.tagGroups();
                selectedGroupId = group.id();
                errandsTab = true;
                editingErrands = create;
                stylePill(searchTabBtn, false);
                stylePill(errandsTabBtn, true);
                naming.dismiss();
                rebuildModeControls();
                runSearch();
                syncGroups();
            } catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
        }));
        naming.show();
        input.requestFocus();
    }

    private void deleteGroup() {
        TagGroup group = selectedGroup();
        if (group == null) return;
        new AlertDialog.Builder(activity).setTitle("Delete tag group?")
                .setMessage("Delete “" + group.name() + "”? Your documents and their hashtags are kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, which) -> {
                    app.settings.saveTagGroup(group.delete());
                    groups = app.settings.tagGroups();
                    selectedGroupId = "";
                    editingErrands = false;
                    rebuildModeControls();
                    runSearch();
                    syncGroups();
                }).show();
    }

    private void syncGroups() {
        app.sync.syncHashtagSettingsInBackground(() -> {
            if (!alive() || dialog == null || !dialog.isShowing()) return;
            List<String> before = selectedTags();
            errandsTags = app.settings.errandsHashtags();
            groups = app.settings.tagGroups();
            if (!selectedGroupId.isEmpty() && selectedGroup() == null) {
                selectedGroupId = "";
                editingErrands = false;
            }
            if (errandsTab && !editingErrands) rebuildModeControls();
            if (!before.equals(selectedTags())) runSearch();
        });
    }

    private void switchTab(boolean toErrands) {
        this.errandsTab = toErrands;
        if (toErrands && selectedTags().isEmpty()) {
            this.editingErrands = true;
        } else {
            this.editingErrands = false;
        }
        stylePill(searchTabBtn, !errandsTab);
        stylePill(errandsTabBtn, errandsTab);
        errandsTabBtn.setText("Tag groups");
        rebuildModeControls();
        runSearch();
    }

    private void updateFilterButtons() {
        stylePill(lineModeBtn, !fileMatchMode);
        stylePill(fileModeBtn, fileMatchMode);
    }

    private void rebuildModeControls() {
        modeContainer.removeAllViews();
        queryChips = null;
        errandsEditChips = null;
        searchInput = null;
        errandsEditInput = null;
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

            queryChips = new TagChipAdapter(discoveredTags, Set.copyOf(Hashtags.parseTagList(searchQuery)), tag -> {
                List<String> current = new ArrayList<>(Hashtags.parseTagList(searchQuery));
                if (!current.remove(tag)) current.add(tag);
                searchInput.setText(Hashtags.formatTagList(current));
                searchInput.setSelection(searchInput.getText().length());
            });
            modeContainer.addView(chipList(queryChips));
        } else {
            LinearLayout groupRow = row();
            Button chooser = pillButton(errandsTabLabel() + " ▾", false, v -> chooseGroup());
            chooser.setId(R.id.tag_group_selector);
            chooser.setSingleLine(true);
            chooser.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chooser.setContentDescription("Select tag group: " + selectedName());
            groupRow.addView(chooser, new LinearLayout.LayoutParams(0, dp(38), 1));
            Button create = pillButton("New tag group", false, v -> nameGroup(true));
            create.setId(R.id.tag_group_new);
            groupRow.addView(create, new LinearLayout.LayoutParams(-2, dp(38)));
            modeContainer.addView(groupRow);

            LinearLayout box = column();
            box.setBackground(rounded(SURFACE, 8, BORDER));
            box.setPadding(dp(10), dp(8), dp(10), dp(8));

            LinearLayout boxHeader = row();
            TextView subtitle = label(selectedName() + " hashtags (matches any)", 12, MUTED);
            boxHeader.addView(subtitle, new LinearLayout.LayoutParams(0, -2, 1));
            if (!editingErrands) {
                Button editBtn = pillButton("Edit", false, v -> {
                    editingErrands = true;
                    rebuildModeControls();
                });
                boxHeader.addView(editBtn, new LinearLayout.LayoutParams(-2, dp(30)));
            }
            box.addView(boxHeader);
            if (!selectedGroupId.isEmpty() && !editingErrands) {
                LinearLayout actions = row();
                actions.addView(pillButton("Rename group", false, v -> nameGroup(false)));
                actions.addView(pillButton("Delete group", false, v -> deleteGroup()));
                box.addView(actions);
            }
            TagGroup group = selectedGroup();
            if (group != null && group.dirty()) box.addView(label("Saved on device · pending sync", 11, MUTED));

            List<String> currentErrands = selectedTags();
            if (editingErrands) {
                errandsEditInput = new EditText(activity);
                errandsEditInput.setId(R.id.tag_group_hashtags);
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

                errandsEditChips = new TagChipAdapter(discoveredTags, Set.copyOf(currentErrands), tag -> {
                    List<String> current = new ArrayList<>(Hashtags.parseTagList(errandsEditInput.getText().toString()));
                    if (!current.remove(tag)) current.add(tag);
                    errandsEditInput.setText(Hashtags.formatTagList(current));
                    errandsEditInput.setSelection(errandsEditInput.getText().length());
                });
                box.addView(chipList(errandsEditChips));

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
                Button saveBtn = pillButton(selectedGroupId.isEmpty() ? "Save Errands" : "Save hashtags", true, v -> saveErrands());
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, dp(34));
                cLp.setMarginEnd(dp(6));
                actionsRow.addView(cancelBtn, cLp);
                actionsRow.addView(saveBtn, new LinearLayout.LayoutParams(-2, dp(34)));
                box.addView(actionsRow);
            } else if (!currentErrands.isEmpty()) {
                box.addView(chipList(new TagChipAdapter(currentErrands, Set.copyOf(currentErrands), null)));
            } else {
                TextView emptyMsg = label("No hashtags in this group yet. Tap Edit to add hashtags.", 12, MUTED);
                emptyMsg.setPadding(0, dp(6), 0, dp(2));
                box.addView(emptyMsg);
            }
            modeContainer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void saveErrands() {
        String raw = errandsEditInput != null ? errandsEditInput.getText().toString() : "";
        List<String> parsed = Hashtags.parseTagList(raw);
        if (selectedGroupId.isEmpty()) {
            app.settings.setErrandsHashtags(parsed, app.settings.errandsUpdatedAt(), true);
            errandsTags = parsed;
        } else {
            TagGroup group = selectedGroup();
            if (group == null) return;
            app.settings.saveTagGroup(group.edit(group.name(), parsed));
            groups = app.settings.tagGroups();
        }
        editingErrands = false;
        errandsTabBtn.setText("Tag groups");
        rebuildModeControls();
        runSearch();
        syncGroups();
    }

    private void updateDiscoveredChips() {
        if (queryChips != null) queryChips.update(discoveredTags, Hashtags.parseTagList(searchQuery));
    }

    private void updateErrandsEditChips() {
        if (errandsEditChips != null && errandsEditInput != null) {
            errandsEditChips.update(discoveredTags, Hashtags.parseTagList(errandsEditInput.getText().toString()));
        }
    }

    private RecyclerView chipList(TagChipAdapter adapter) {
        RecyclerView list = new RecyclerView(activity);
        list.setId(R.id.hashtag_chips);
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setPadding(0, dp(6), 0, dp(2));
        list.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(38)));
        list.setAdapter(adapter);
        return list;
    }

    private static final class ChipHolder extends RecyclerView.ViewHolder {
        final Button button;
        ChipHolder(Button button) { super(button); this.button = button; }
    }

    private final class TagChipAdapter extends RecyclerView.Adapter<ChipHolder> {
        private List<String> tags;
        private Set<String> selected;
        private final Consumer<String> onClick;

        TagChipAdapter(List<String> tags, Set<String> selected, Consumer<String> onClick) {
            this.tags = tags;
            this.selected = selected;
            this.onClick = onClick;
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void update(List<String> tags, List<String> selected) {
            this.tags = tags;
            this.selected = Set.copyOf(selected);
            notifyDataSetChanged();
        }

        @Override public ChipHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = pillButton("", false, null);
            button.setSingleLine(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-2, dp(30));
            lp.setMarginEnd(dp(6));
            button.setLayoutParams(lp);
            return new ChipHolder(button);
        }

        @Override public void onBindViewHolder(ChipHolder holder, int position) {
            String tag = tags.get(position);
            holder.button.setText("#" + tag);
            stylePill(holder.button, selected.contains(tag));
            holder.button.setOnClickListener(onClick == null ? null : v -> onClick.accept(tag));
            holder.button.setClickable(onClick != null);
        }

        @Override public int getItemCount() { return tags.size(); }
    }

    private void runSearch() {
        final int gen = searchGen.incrementAndGet();
        final List<String> targetTags = errandsTab
                ? selectedTags()
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
        renderResults();

        app.io.execute(() -> {
            if (searchGen.get() != gen) return;
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
        if (resultsList == null) return;

        List<String> targetTags = errandsTab
                ? selectedTags()
                : Hashtags.parseTagList(searchQuery);

        if (searching) {
            resultsAdapter.showMessage("Searching files…");
            return;
        }
        if (errandsTab && targetTags.isEmpty()) {
            resultsAdapter.showMessage("Save hashtags in this group above to view matching lines or files.");
            return;
        }
        if (!errandsTab && targetTags.isEmpty()) {
            resultsAdapter.showMessage("Tap a hashtag chip or type one or more hashtags above to search.");
            return;
        }
        if (currentResults.isEmpty()) {
            resultsAdapter.showMessage("No matching " + (fileMatchMode ? "files" : "lines") + " found.");
            return;
        }

        List<ResultRow> rows = new ArrayList<>();
        for (FileResult res : currentResults) {
            rows.add(new ResultRow(res, null, null));
            if (!fileMatchMode) {
                for (Hashtags.LineMatch lineMatch : res.match().lines()) {
                    rows.add(new ResultRow(res, lineMatch, null));
                }
            }
        }
        resultsAdapter.update(rows);
    }

    private record ResultRow(FileResult file, Hashtags.LineMatch line, String message) {}

    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final TextView text, meta, location;
        ResultHolder(View view, TextView text, TextView meta, TextView location) {
            super(view);
            this.text = text;
            this.meta = meta;
            this.location = location;
        }
    }

    private final class ResultsAdapter extends RecyclerView.Adapter<ResultHolder> {
        private List<ResultRow> rows = List.of();

        void showMessage(String message) {
            update(List.of(new ResultRow(null, null, message)));
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void update(List<ResultRow> rows) {
            this.rows = rows;
            notifyDataSetChanged();
            resultsList.scrollToPosition(0);
        }

        @Override public int getItemCount() { return rows.size(); }

        @Override public int getItemViewType(int position) {
            ResultRow row = rows.get(position);
            return row.message() != null ? 0 : row.line() == null ? 1 : 2;
        }

        @Override public ResultHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 0) {
                TextView text = emptyState("");
                text.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new ResultHolder(text, text, null, null);
            }
            if (viewType == 1) {
                LinearLayout header = column();
                header.setPadding(dp(12), dp(10), dp(12), dp(10));
                header.setBackground(rounded(SURFACE_ALT, 8, BORDER));
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
                lp.topMargin = dp(8);
                header.setLayoutParams(lp);
                TextView name = label("", app.settings.fontSize(), ACCENT);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                TextView meta = label("", 11, MUTED);
                TextView location = label("", 11, MUTED);
                location.setPadding(0, dp(2), 0, 0);
                header.addView(name);
                header.addView(meta);
                header.addView(location);
                return new ResultHolder(header, name, meta, location);
            }
            TextView text = label("", app.settings.fontSize(), TEXT);
            text.setTypeface(Typeface.MONOSPACE);
            text.setSingleLine(false);
            text.setPadding(dp(12), dp(10), dp(12), dp(10));
            text.setBackgroundColor(SURFACE);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            lp.bottomMargin = dp(1);
            text.setLayoutParams(lp);
            return new ResultHolder(text, text, null, null);
        }

        @Override public void onBindViewHolder(ResultHolder holder, int position) {
            ResultRow row = rows.get(position);
            if (row.message() != null) {
                holder.text.setText(row.message());
            } else if (row.line() == null) {
                FileResult file = row.file();
                holder.text.setText(file.file().name());
                int count = file.match().lines().size();
                String meta = count + (count == 1 ? " line" : " lines");
                if (fileMatchMode) meta = Hashtags.formatTagList(file.match().matchedTags()) + " · " + meta;
                holder.meta.setText(meta);
                holder.location.setText(file.file().locationLabel());
                holder.itemView.setOnClickListener(v -> openResult(file.file(), null));
            } else {
                Hashtags.LineMatch line = row.line();
                String prefix = "Ln " + line.lineNumber() + "  ";
                SpannableStringBuilder text = new SpannableStringBuilder(prefix).append(line.lineText());
                text.setSpan(new ForegroundColorSpan(ACCENT), 0, prefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                holder.text.setText(text);
                holder.itemView.setOnClickListener(v -> openResult(row.file().file(), line.lineNumber()));
            }
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
