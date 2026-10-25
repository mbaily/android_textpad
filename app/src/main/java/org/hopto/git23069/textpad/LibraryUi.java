package org.hopto.git23069.textpad;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.hopto.git23069.textpad.core.Folder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Native full-page shared-library browser; the existing editor stays mounted. */
final class LibraryUi {
    private static final int BG = 0xff101313, SURFACE = 0xff19201d, TEXT = 0xffe3e8df, MUTED = 0xff99a79e, ACCENT = 0xffa9d98b;
    private record Entry(Folder folder, RemoteApi.Remote file) {
        String id() { return folder != null ? folder.id() : file.id(); }
        String name() { return folder != null ? folder.name() : file.name(); }
    }
    private final MainActivity activity;
    private final TextpadApplication app;
    private final LibraryManager library;
    final LinearLayout view;
    private final LinearLayout breadcrumbs;
    private final EditText search;
    private final TextView status, selection;
    private final Button newFolder, newFile, move, selectAll, folderActions, refresh;
    private final Entries adapter = new Entries();
    private String renderedPath = "";
    private boolean rendering;
    private List<Entry> visible = List.of();

    LibraryUi(MainActivity activity, TextpadApplication app) {
        this.activity = activity; this.app = app; library = app.library;
        view = column(); view.setId(R.id.library_browser); view.setBackgroundColor(BG); view.setPadding(dp(12), dp(8), dp(12), 0);
        LinearLayout heading = row(); TextView title = label("Files", 23, TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("Editor", "Back to editor", activity::showEditor)); view.addView(heading);
        HorizontalScrollView path = new HorizontalScrollView(activity); path.setHorizontalScrollBarEnabled(false);
        breadcrumbs = row(); path.addView(breadcrumbs); view.addView(path, new LinearLayout.LayoutParams(-1, dp(48)));
        HorizontalScrollView actions = new HorizontalScrollView(activity); actions.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = row();
        buttons.addView(button("Folders", "Browse folders", () -> destination("Browse folders", library.folderId, Set.of(), library::selectFolder, "Open folder")));
        newFolder = button("New folder", "New folder", () -> { String parent = library.folderId; name("New folder", "", value -> library.createFolder(value, parent)); }); buttons.addView(newFolder);
        newFile = button("New file", "New shared file", () -> { String parent = library.folderId; name("New file", "", value -> library.createFile(value, parent)); }); buttons.addView(newFile);
        newFile.setTextColor(ACCENT);
        folderActions = button("Actions", "Current folder actions", () -> {
            Folder folder = library.tree.get(library.folderId); if (folder != null) folderMenu(folder);
        }); buttons.addView(folderActions);
        refresh = button("Refresh", "Refresh shared files", library::refresh); buttons.addView(refresh);
        buttons.addView(button("Server settings", "Server settings", () -> new SyncUi(activity, app).settings()));
        actions.addView(buttons); view.addView(actions);
        search = new EditText(activity); search.setId(R.id.library_search); search.setHint("Search this folder"); search.setSingleLine(true);
        search.setTextSize(14); search.setTextColor(TEXT); search.setHintTextColor(MUTED); search.setSaveEnabled(false);
        search.setContentDescription("Search this folder");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable text) { if (!rendering) { library.filter = text.toString(); library.selected.clear(); render(); } }
        });
        view.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));
        status = label("", 12, MUTED); status.setId(R.id.library_status); status.setPadding(dp(4), dp(4), dp(4), dp(4)); view.addView(status);
        LinearLayout selected = row(); selection = label("", 12, MUTED); selection.setId(R.id.library_selection);
        selected.addView(selection, new LinearLayout.LayoutParams(0, -2, 1));
        selectAll = button("Select all", "Select all files", () -> {
            List<String> ids = visible.stream().filter(e -> e.file() != null).map(Entry::id).toList();
            if (!ids.isEmpty() && library.selected.containsAll(ids)) library.selected.clear(); else library.selected.addAll(ids);
            render();
        }); selected.addView(selectAll);
        move = button("Move…", "Move selected files", () -> {
            List<String> ids = visible.stream().filter(e -> e.file() != null && library.selected.contains(e.id())).map(Entry::id).toList();
            destination("Move " + ids.size() + " selected files", library.folderId, Set.of(), parent -> library.moveFiles(ids, parent), "Move here");
        }); move.setId(R.id.library_move_selected); selected.addView(move); view.addView(selected);
        RecyclerView list = new RecyclerView(activity); list.setId(R.id.library_entries); list.setLayoutManager(new LinearLayoutManager(activity)); list.setAdapter(adapter);
        view.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        view.setVisibility(View.GONE);
    }

    void render() {
        rendering = true;
        String query = library.filter.strip().toLowerCase(Locale.ROOT);
        List<Entry> rows = new ArrayList<>();
        for (Folder folder : library.tree.children(library.folderId)) if (folder.name().toLowerCase(Locale.ROOT).contains(query)) rows.add(new Entry(folder, null));
        for (var file : library.childFiles(library.folderId)) if (file.name().toLowerCase(Locale.ROOT).contains(query)) rows.add(new Entry(null, file));
        visible = List.copyOf(rows);
        List<String> ids = visible.stream().filter(e -> e.file() != null).map(Entry::id).toList(); library.selected.retainAll(ids);
        if (!search.getText().toString().equals(library.filter)) search.setText(library.filter);
        String path = library.pathLabel(library.folderId);
        if (!renderedPath.equals(path)) {
            renderedPath = path; breadcrumbs.removeAllViews();
            Button top = button("Top level", "Top level", () -> library.selectFolder(null));
            if (library.folderId == null) top.setTextColor(ACCENT); breadcrumbs.addView(top);
            for (Folder folder : library.tree.path(library.folderId)) {
                breadcrumbs.addView(label(" / ", 12, MUTED));
                Button crumb = button(folder.name(), "Open folder: " + folder.name(), () -> library.selectFolder(folder.id()));
                if (folder.id().equals(library.folderId)) crumb.setTextColor(ACCENT);
                crumb.setMaxWidth(dp(200)); crumb.setSingleLine(true); crumb.setEllipsize(TextUtils.TruncateAt.END); breadcrumbs.addView(crumb);
            }
        }
        boolean canWrite = library.connected && !library.busy && !app.sync.busy;
        enable(newFolder, canWrite); enable(newFile, canWrite); enable(folderActions, library.folderId != null && !library.busy);
        enable(refresh, !library.loading && !library.busy); enable(move, canWrite && !library.selected.isEmpty());
        enable(selectAll, !ids.isEmpty() && !library.busy);
        selectAll.setText(!ids.isEmpty() && library.selected.containsAll(ids) ? "Clear" : "Select all");
        selection.setText(library.selected.isEmpty() ? visible.size() + " items" : library.selected.size() + " selected");
        String message;
        if (!library.error.isBlank()) message = library.error;
        else if (library.busy) message = "Working…";
        else if (!library.loaded && library.loading) message = "Loading files and folders…";
        else if (!library.loaded) message = "Connect to the sync server to browse shared files.";
        else if (!library.connected) message = "Offline · Showing cached files and folders.";
        else if (visible.isEmpty()) message = query.isEmpty() ? "This folder is empty." : "No matching files or folders.";
        else message = "Shared library · " + (library.loading ? "Refreshing…" : "Connected");
        status.setText(message); status.setTextColor(library.error.isBlank() ? MUTED : 0xffe7ad99);
        adapter.notifyDataSetChanged(); rendering = false;
    }

    void focusSearch() {
        search.requestFocus();
        var keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private boolean editable() {
        if (library.connected && !library.busy && !app.sync.busy) return true;
        library.error = library.busy || app.sync.busy ? "Wait for the current operation to finish." : "Connect to the server to manage saved files and folders.";
        render(); return false;
    }
    private void name(String title, String initial, Consumer<String> accepted) {
        if (!editable()) return;
        EditText input = new EditText(activity); input.setId(R.id.library_name); input.setSingleLine(true); input.setText(initial); input.setSelectAllOnFocus(true);
        input.setContentDescription(title); input.setSaveEnabled(false);
        LinearLayout fields = column(); fields.setPadding(dp(20), 0, dp(20), 0); fields.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String normalized = new Folder(UUID.randomUUID().toString(), input.getText().toString(), null, "", "").name();
                dialog.dismiss(); accepted.accept(normalized);
            } catch (IllegalArgumentException e) { input.setError("Use 1–255 characters without slashes or control characters."); }
        })); dialog.show(); input.requestFocus();
    }
    private void folderMenu(Folder folder) {
        String[] choices = {"Open folder", "New subfolder", "New file", "Rename folder", "Move folder…", "Delete empty folder"};
        new AlertDialog.Builder(activity).setTitle(folder.name()).setItems(choices, (dialog, which) -> {
            if (which == 0) { library.selectFolder(folder.id()); return; }
            if (!editable()) return;
            switch (which) {
                case 1 -> name("New folder", "", value -> library.createFolder(value, folder.id()));
                case 2 -> name("New file", "", value -> library.createFile(value, folder.id()));
                case 3 -> name("Rename folder", folder.name(), renamed -> library.renameFolder(folder, renamed));
                case 4 -> destination("Move " + folder.name(), folder.parentId(), library.tree.descendants(folder.id()), parent -> library.moveFolder(folder, parent), "Move here");
                case 5 -> {
                    if (!library.tree.children(folder.id()).isEmpty() || !library.childFiles(folder.id()).isEmpty()) {
                        library.error = "Move this folder's files and subfolders before deleting it."; render(); return;
                    }
                    new AlertDialog.Builder(activity).setTitle("Delete empty folder?").setMessage(folder.name())
                            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> library.deleteFolder(folder)).show();
                }
            }
        }).setNegativeButton("Close", null).show();
    }
    private void fileMenu(RemoteApi.Remote file) {
        String[] choices = {"Open file", "Rename shared file", "Move to folder…", "Download a copy…", "History…"};
        new AlertDialog.Builder(activity).setTitle(file.name()).setItems(choices, (dialog, which) -> {
            switch (which) {
                case 0 -> library.open(file);
                case 1 -> name("Rename shared file", file.name(), renamed -> library.renameFile(file, renamed));
                case 2 -> { if (editable()) destination("Move " + file.name(), file.folderId(), Set.of(), parent -> library.moveFiles(List.of(file.id()), parent), "Move here"); }
                case 3 -> new SyncUi(activity, app).downloadFile(file);
                case 4 -> new SyncUi(activity, app).history(file);
            }
        }).setNegativeButton("Close", null).show();
    }
    private void destination(String title, String initial, Set<String> excluded, Consumer<String> accepted, String action) {
        LinearLayout content = column(); content.setPadding(dp(12), 0, dp(12), 0);
        String[] parent = {initial}; TextView path = label("", 12, MUTED); path.setPadding(0, dp(8), 0, dp(8));
        path.setSingleLine(true); path.setEllipsize(TextUtils.TruncateAt.MIDDLE); content.addView(path);
        LinearLayout navigation = row(); content.addView(navigation);
        RecyclerView choices = new RecyclerView(activity); choices.setLayoutManager(new LinearLayoutManager(activity));
        content.addView(choices, new LinearLayout.LayoutParams(-1, dp(230)));
        FolderChoices list = new FolderChoices(); choices.setAdapter(list);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            path.setText(library.pathLabel(parent[0])); navigation.removeAllViews();
            navigation.addView(button("Top level", "Top level destination", () -> { parent[0] = null; render[0].run(); }));
            Button up = button("Up", "Up one folder", () -> { Folder current = library.tree.get(parent[0]); parent[0] = current == null ? null : current.parentId(); render[0].run(); });
            enable(up, parent[0] != null); navigation.addView(up);
            list.folders = library.tree.children(parent[0]).stream().filter(folder -> !excluded.contains(folder.id())).toList();
            list.open = folder -> { parent[0] = folder.id(); render[0].run(); }; list.notifyDataSetChanged();
        };
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(content).setNegativeButton("Cancel", null)
                .setPositiveButton(action, (d, w) -> accepted.accept(parent[0])).create();
        render[0].run(); dialog.show();
    }

    private final class Cell extends RecyclerView.ViewHolder {
        final LinearLayout root, nameArea; final CheckBox checked; final TextView name, detail; final Button more;
        Cell() {
            super(new LinearLayout(activity)); root = (LinearLayout) itemView; root.setOrientation(LinearLayout.HORIZONTAL); root.setGravity(Gravity.CENTER_VERTICAL);
            root.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            root.setPadding(0, dp(8), 0, dp(8));
            checked = new CheckBox(activity); root.addView(checked, new LinearLayout.LayoutParams(dp(44), dp(48)));
            nameArea = column(); nameArea.setPadding(dp(4), dp(6), dp(4), dp(6)); nameArea.setFocusable(true);
            name = label("", 15, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); nameArea.addView(name);
            detail = label("", 11, MUTED); detail.setPadding(0, dp(4), 0, 0); nameArea.addView(detail);
            root.addView(nameArea, new LinearLayout.LayoutParams(0, -2, 1));
            more = button("More", "Entry actions", () -> {}); root.addView(more);
        }
    }
    private final class Entries extends RecyclerView.Adapter<Cell> {
        public Cell onCreateViewHolder(ViewGroup parent, int type) { return new Cell(); }
        public int getItemCount() { return visible.size(); }
        public void onBindViewHolder(Cell cell, int position) {
            Entry entry = visible.get(position); boolean folder = entry.folder() != null;
            cell.name.setText(entry.name()); cell.name.setTextColor(folder ? ACCENT : TEXT);
            String detail;
            if (folder) detail = "Folder · " + (library.tree.children(entry.id()).size() + library.childFiles(entry.id()).size()) + " items";
            else {
                boolean dirty = app.tabs.stream().anyMatch(tab -> entry.id().equals(tab.document.syncId) && tab.document.buffer.dirty());
                detail = dirty ? "Unsaved edits in an open tab" : entry.file().size() + " bytes" + (library.availableLocally(entry.id()) ? " · Available locally" : "");
            }
            cell.detail.setText(detail); cell.nameArea.setContentDescription("Open " + (folder ? "folder: " : "file: ") + entry.name());
            cell.nameArea.setOnClickListener(v -> { if (folder) library.selectFolder(entry.id()); else library.open(entry.file()); });
            cell.checked.setOnCheckedChangeListener(null); cell.checked.setVisibility(folder ? View.INVISIBLE : View.VISIBLE);
            cell.checked.setChecked(!folder && library.selected.contains(entry.id())); cell.checked.setEnabled(!library.busy);
            cell.checked.setContentDescription("Select " + entry.name());
            cell.checked.setOnCheckedChangeListener((button, checked) -> { if (checked) library.selected.add(entry.id()); else library.selected.remove(entry.id()); render(); });
            cell.more.setContentDescription("Actions for " + (folder ? "folder " : "file ") + entry.name());
            cell.more.setOnClickListener(v -> { if (folder) folderMenu(entry.folder()); else fileMenu(entry.file()); });
            GradientDrawable background = new GradientDrawable(); background.setColor(!folder && library.selected.contains(entry.id()) ? 0xff263026 : BG);
            background.setCornerRadius(dp(6)); cell.root.setBackground(background);
        }
    }
    private final class FolderChoices extends RecyclerView.Adapter<FolderChoices.Holder> {
        List<Folder> folders = List.of(); Consumer<Folder> open;
        final class Holder extends RecyclerView.ViewHolder { Holder() { super(button("", "", () -> {})); } }
        public Holder onCreateViewHolder(ViewGroup parent, int type) { return new Holder(); }
        public int getItemCount() { return folders.size(); }
        public void onBindViewHolder(Holder holder, int position) {
            Folder folder = folders.get(position); Button button = (Button) holder.itemView;
            button.setText(folder.name() + "  ›"); button.setContentDescription("Destination folder: " + folder.name());
            button.setSingleLine(true); button.setEllipsize(TextUtils.TruncateAt.END);
            button.setOnClickListener(v -> open.accept(folder)); button.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(48)));
        }
    }
    private int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(activity); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String text, String description, Runnable action) {
        Button button = new Button(activity); button.setText(text); button.setTextSize(12); button.setAllCaps(false); button.setContentDescription(description);
        button.setMinWidth(dp(52)); button.setMinimumWidth(dp(52)); button.setMinHeight(dp(44)); button.setMinimumHeight(dp(44));
        button.setPadding(dp(10), dp(4), dp(10), dp(4)); button.setTextColor(TEXT); button.setOnClickListener(v -> action.run());
        GradientDrawable background = new GradientDrawable(); background.setColor(SURFACE); background.setCornerRadius(dp(6));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x335b7950), background, null));
        return button;
    }
    private void enable(Button button, boolean enabled) { button.setEnabled(enabled); button.setAlpha(enabled ? 1f : .4f); }
}
