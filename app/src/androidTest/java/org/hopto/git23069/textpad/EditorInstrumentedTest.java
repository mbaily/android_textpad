package org.hopto.git23069.textpad;

import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.hopto.git23069.textpad.core.TextCodec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class EditorInstrumentedTest {
    private TextpadApplication app;
    @Before public void reset() {
        app = (TextpadApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            app.tabs.clear(); app.selectedTab = -1; app.events.clear(); app.exiting = false; app.readOnlyMode = false; app.settings.prefs.edit().clear().commit();
            app.getSharedPreferences("files", 0).edit().remove("recent").commit();
        });
    }
    private Uri seed(String name, byte[] contents) throws Exception {
        Uri uri = DocumentsContract.buildDocumentUri("org.hopto.git23069.textpad.test.documents", name);
        try (OutputStream out = app.getContentResolver().openOutputStream(uri, "wt")) { out.write(contents); }
        return uri;
    }
    private ActivityScenario<MainActivity> launch(Uri uri) {
        Intent intent = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (uri != null) intent.setAction(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return ActivityScenario.launch(intent);
    }
    private void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Boolean> result = new AtomicReference<>(false);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(ready.getAsBoolean()));
            if (result.get()) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for editor operation");
    }
    private byte[] read(Uri uri) throws Exception { try (InputStream in = app.getContentResolver().openInputStream(uri)) { return DocumentStore.readLimited(in); } }
    private View button(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = button(group.getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private View findViewWithHint(View view, String hint) {
        if (view instanceof android.widget.TextView tv && tv.getHint() != null && hint.contentEquals(tv.getHint())) return tv;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findViewWithHint(group.getChildAt(i), hint); if (found != null) return found;
        }
        return null;
    }

    @Test public void entryScreenShowsAnEmptyRecentListAndCanCreateADocument() throws Exception {
        try (var scenario = launch(null)) {
            scenario.onActivity(activity -> {
                View home = activity.findViewById(R.id.recent_files);
                assertEquals(View.VISIBLE, home.getVisibility());
                assertEquals("No recent files yet.", ((android.widget.TextView) activity.findViewById(R.id.recent_files_empty)).getText().toString());
                assertTrue(button(home, "Open file").isEnabled());
                button(home, "New document").performClick();
                assertNotNull(app.current());
                assertEquals(View.GONE, home.getVisibility());
                app.close(app.currentTab().id);
                assertEquals(View.VISIBLE, home.getVisibility());
                assertNotNull(activity.findViewById(R.id.recent_files_empty));
            });
        }
    }

    @Test public void entryScreenOpensRecentFilesAndRemembersTheirOrderAcrossRecreation() throws Exception {
        Uri notes = seed("Notes.txt", "A recent note".getBytes(StandardCharsets.UTF_8));
        Uri shopping = seed("Shopping.txt", "[ ] Milk".getBytes(StandardCharsets.UTF_8));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            app.store.remember(notes, "Notes.txt");
            app.store.remember(shopping, "Shopping.txt");
        });
        try (var scenario = launch(null)) {
            scenario.onActivity(activity -> {
                ViewGroup list = activity.findViewById(R.id.recent_file_list);
                assertEquals(2, list.getChildCount());
                assertEquals("Open recent file: Shopping.txt", list.getChildAt(0).getContentDescription());
                button(list, "Open recent file: Notes.txt").performClick();
            });
            waitFor(() -> app.current() != null && DocumentStore.same(notes, app.current().uri));
            scenario.onActivity(activity -> {
                assertEquals("A recent note", ((CodeEditor) activity.findViewById(R.id.editor)).getText().toString());
                app.close(app.currentTab().id);
                ViewGroup list = activity.findViewById(R.id.recent_file_list);
                assertEquals(2, list.getChildCount());
                assertEquals("Open recent file: Notes.txt", list.getChildAt(0).getContentDescription());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                ViewGroup list = activity.findViewById(R.id.recent_file_list);
                assertEquals(2, list.getChildCount());
                assertEquals("Open recent file: Notes.txt", list.getChildAt(0).getContentDescription());
                button(list, "Open recent file: Shopping.txt").performClick();
            });
            waitFor(() -> app.current() != null && DocumentStore.same(shopping, app.current().uri));
            scenario.onActivity(activity -> {
                assertEquals("[ ] Milk", ((CodeEditor) activity.findViewById(R.id.editor)).getText().toString());
                app.close(app.currentTab().id);
                assertEquals(2, ((ViewGroup) activity.findViewById(R.id.recent_file_list)).getChildCount());
            });
        }
    }

    @Test public void nativeEditorSavesUnicodeAndMixedTerminatorsThroughSaf() throws Exception {
        Uri uri = seed("unicode.txt", "\ufeffone\r\n😀 café\nالعربية\r".getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                View saveBtn = button(activity.getWindow().getDecorView(), "Save file");
                assertEquals("one\n😀 café\nالعربية\n", editor.getText().toString());
                assertFalse(saveBtn.isEnabled());
                editor.getText().insert(0, "x");
                assertTrue(saveBtn.isEnabled());
                editor.getText().delete(0, 1);
                assertFalse(saveBtn.isEnabled());
                editor.getText().insert(0, "prefix ");
                assertTrue(saveBtn.isEnabled());
                app.save(app.current(), null);
            });
            waitFor(() -> !app.current().saving && !app.current().buffer.dirty());
            scenario.onActivity(activity -> {
                assertFalse(button(activity.getWindow().getDecorView(), "Save file").isEnabled());
            });
            assertArrayEquals("\ufeffprefix one\r\n😀 café\nالعربية\r".getBytes(StandardCharsets.UTF_8),read(uri));
            int backupsAfterFirstSave = app.store.backups().size();
            assertTrue(backupsAfterFirstSave > 0);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> app.save(app.current(), null));
            assertEquals(backupsAfterFirstSave, app.store.backups().size());
        }
    }

    @Test public void duplicateTabsShareTextAndKeepSeparateSelectionsAcrossRecreation() throws Exception {
        Uri uri = seed("tabs.txt", "first\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor); editor.setSelection(3);
                app.duplicate(); assertSame(app.tabs.get(0).document,app.tabs.get(1).document);
                editor.setSelection(editor.length()); editor.getText().append("😀");
                app.selectedTab = app.tabs.get(0).id; app.refresh();
                assertEquals(3,editor.getSelectionStart()); assertTrue(editor.getText().toString().endsWith("😀"));
                button(activity.getWindow().getDecorView(),"Toggle read-only mode").performClick();
                assertNull(editor.getKeyListener());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                assertEquals(2,app.tabs.size()); assertEquals(3,editor.getSelectionStart());
                assertTrue(editor.getText().toString().endsWith("😀")); assertTrue(app.current().buffer.dirty());
                assertTrue(app.readOnlyMode);
                assertNull(editor.getKeyListener());
                assertFalse(button(activity.getWindow().getDecorView(),"Toggle todo").isEnabled());
            });
        }
    }

    @Test public void toolbarUndoRedoAndIndentUseTheSharedHistory() throws Exception {
        try (var scenario = launch(null)) {
            scenario.onActivity(activity -> {
                View saveBtn = button(activity.getWindow().getDecorView(), "Save file");
                assertFalse(saveBtn.isEnabled());
                app.newDocument(); CodeEditor editor = activity.findViewById(R.id.editor);
                assertFalse(saveBtn.isEnabled());
                editor.getText().append("hello\nworld"); editor.setSelection(0,editor.length());
                assertTrue(saveBtn.isEnabled());
                button(activity.getWindow().getDecorView(),"Increase indentation").performClick();
                assertEquals("    hello\n    world",editor.getText().toString());
                button(activity.getWindow().getDecorView(),"Undo").performClick(); assertEquals("hello\nworld",editor.getText().toString());
                button(activity.getWindow().getDecorView(),"Redo").performClick(); assertEquals("    hello\n    world",editor.getText().toString());
                editor.setSelection(0); assertEquals(0,editor.getSelectionStart());
                button(activity.getWindow().getDecorView(),"Go to bottom of file").performClick();
                assertEquals(editor.length(),editor.getSelectionStart()); assertEquals(editor.length(),editor.getSelectionEnd());
                button(activity.getWindow().getDecorView(),"Toggle read-only mode").performClick();
                assertNull(editor.getKeyListener());
                assertFalse(button(activity.getWindow().getDecorView(),"Undo").isEnabled());
                assertFalse(button(activity.getWindow().getDecorView(),"Toggle todo").isEnabled());
                button(activity.getWindow().getDecorView(),"Toggle read-only mode").performClick();
                assertNotNull(editor.getKeyListener());
                assertTrue(button(activity.getWindow().getDecorView(),"Undo").isEnabled());
                assertTrue(button(activity.getWindow().getDecorView(),"Toggle todo").isEnabled());
                app.settings.prefs.edit().putInt("dividerDashes", 12).commit();
                editor.getText().append("\n");
                button(activity.getWindow().getDecorView(),"Insert date").performClick();
                assertTrue(editor.getText().toString().endsWith(java.time.LocalDate.now().toString()));
                editor.getText().append("\n");
                button(activity.getWindow().getDecorView(),"Insert time").performClick();
                assertTrue(editor.getText().toString().matches("(?s).*\\n([1-9]|1[0-2]):[0-5][0-9] (AM|PM)$"));
                editor.getText().append("\n");
                button(activity.getWindow().getDecorView(),"Insert dividing line").performClick();
                assertTrue(editor.getText().toString().endsWith("\n------------"));
                editor.getText().append("\ntask item");
                editor.setSelection(editor.length());
                button(activity.getWindow().getDecorView(),"Toggle todo").performClick();
                assertTrue(editor.getText().toString().endsWith("\n[ ] task item"));
                button(activity.getWindow().getDecorView(),"Toggle todo").performClick();
                assertTrue(editor.getText().toString().endsWith("\n[X] task item"));
                button(activity.getWindow().getDecorView(),"Toggle todo").performClick();
                assertTrue(editor.getText().toString().endsWith("\ntask item"));
                editor.getText().replace(0, editor.length(), "    indented\n  [ ] open\n\t[X] done");
                editor.setSelection(4); // cursor on indented line
                button(activity.getWindow().getDecorView(),"Toggle todo").performClick();
                assertEquals("    [ ] indented\n  [ ] open\n\t[X] done", editor.getText().toString());
                button(activity.getWindow().getDecorView(),"Undo").performClick();
                assertEquals("    indented\n  [ ] open\n\t[X] done", editor.getText().toString());
                editor.setSelection(0, editor.length()); // select all 3 mixed lines
                button(activity.getWindow().getDecorView(),"Toggle todo").performClick();
                assertEquals("    [ ] indented\n  [ ] open\n\t[X] done", editor.getText().toString());
                button(activity.getWindow().getDecorView(),"Toggle todo").performClick();
                assertEquals("    [X] indented\n  [X] open\n\t[X] done", editor.getText().toString());
                button(activity.getWindow().getDecorView(),"Toggle todo").performClick();
                assertEquals("    indented\n  open\n\tdone", editor.getText().toString());
                editor.getText().replace(0, editor.length(), "line1\nline2\nline3");
                editor.setSelection(6); // cursor on line2
                button(activity.getWindow().getDecorView(),"Move line up").performClick();
                assertEquals("line2\nline1\nline3", editor.getText().toString());
                button(activity.getWindow().getDecorView(),"Move line down").performClick();
                assertEquals("line1\nline2\nline3", editor.getText().toString());
                editor.setSelection(0, 8); // select line1 and line2
                button(activity.getWindow().getDecorView(),"Move line down").performClick();
                assertEquals("line3\nline1\nline2", editor.getText().toString());
                button(activity.getWindow().getDecorView(),"Move line up").performClick();
                assertEquals("line1\nline2\nline3", editor.getText().toString());
                editor.getText().append("\ntask item");
                // Find in open files cycles through matches in current file and switches tabs when reaching the end.
                app.newDocument();
                editor.getText().append("alpha task\nbeta task");
                long secondTab = app.selectedTab;
                long firstTab = app.tabs.get(0).id;
                app.selectedTab = firstTab; app.refresh();
                editor.setSelection(0);
                button(activity.getWindow().getDecorView(),"Find in open files").performClick();
                android.widget.EditText findInput = (android.widget.EditText) findViewWithHint(activity.getWindow().getDecorView(),"Find text");
                findInput.setText("task");
                assertNotNull(editor.getSearchPattern());
                button(activity.getWindow().getDecorView(),"Next match").performClick();
                assertEquals(firstTab,app.selectedTab);
                assertFalse(editor.isFocused());
                button(activity.getWindow().getDecorView(),"Next match").performClick();
                assertEquals(secondTab,app.selectedTab);
                assertEquals(6,editor.getSelectionStart()); assertEquals(10,editor.getSelectionEnd());
                button(activity.getWindow().getDecorView(),"Next match").performClick();
                assertEquals(secondTab,app.selectedTab);
                assertEquals(16,editor.getSelectionStart()); assertEquals(20,editor.getSelectionEnd());
                button(activity.getWindow().getDecorView(),"Next match").performClick();
                assertEquals(firstTab,app.selectedTab);
                button(activity.getWindow().getDecorView(),"Close find").performClick();
                assertNull(editor.getSearchPattern());
            });
        }
    }

    @Test public void externallyChangedFileIsNotOverwrittenWithoutConfirmation() throws Exception {
        Uri uri = seed("external.txt", "original".getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            AtomicReference<TextpadApplication.Event> event = new AtomicReference<>();
            scenario.onActivity(activity -> {
                ((CodeEditor) activity.findViewById(R.id.editor)).getText().append(" edited");
                app.detach(activity); app.attach(new TextpadApplication.Listener() {
                    public void refresh() {} public void event(TextpadApplication.Event e) { event.set(e); }
                });
            });
            seed("external.txt","changed elsewhere".getBytes(StandardCharsets.UTF_8));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> app.save(app.current(),null));
            waitFor(() -> event.get() != null);
            assertNotNull(event.get().conflict()); assertArrayEquals("changed elsewhere".getBytes(StandardCharsets.UTF_8),read(uri));
            assertTrue(app.current().buffer.dirty());
            scenario.onActivity(activity -> app.detach(app.listener));
        }
    }

    @Test public void readOnlyProviderIsSelectableAndNotEditable() throws Exception {
        Uri uri = seed("readonly.txt", "read only".getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                assertFalse(app.current().writable); CodeEditor editor = activity.findViewById(R.id.editor);
                assertNull(editor.getKeyListener()); editor.setSelection(0,4); assertEquals(4,editor.getSelectionEnd());
            });
        }
    }

    @Test public void tasksViewHidesLinesAndSavesEditsWithoutLosingHiddenText() throws Exception {
        String original = "Unrelated introduction\n# Shopping\n[X] Finished project\n    [X] Finished step\n"
                + "    [ ] Buy milk\n        Remember organic\n    [X] Finished sibling\n"
                + "[ ] Buy apples\n[X] Finished last task\n";
        String filtered = "# Shopping\n[X] Finished project\n    [ ] Buy milk\n"
                + "        Remember organic\n[ ] Buy apples";
        Uri uri = seed("task-filter.txt", original.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                editor.setSelection(original.indexOf("apples"));
                button(activity.getWindow().getDecorView(), "Filter uncompleted tasks").performClick();
                assertTrue(app.currentTab().tasksOnly);
                assertEquals(filtered, editor.getText().toString());
                assertArrayEquals(new int[]{1, 2, 4, 5, 7}, editor.lineMap);
                assertEquals(filtered.indexOf("apples"), editor.getSelectionStart());
                assertEquals(original, app.current().buffer.text());
                assertFalse(app.current().buffer.dirty());
                editor.getText().insert(editor.length(), "\nblocked");
                assertEquals(filtered, editor.getText().toString());
                int hiddenGap = filtered.indexOf("\n[ ] Buy apples");
                editor.getText().delete(hiddenGap, hiddenGap + 1);
                assertEquals(filtered, editor.getText().toString());
                int apples = filtered.indexOf("apples");
                editor.getText().replace(apples, apples + "apples".length(), "pears");
                assertEquals(original.replace("apples", "pears"), app.current().buffer.text());
                app.save(app.current(), null);
            });
            waitFor(() -> !app.current().saving && !app.current().buffer.dirty());
            assertArrayEquals(original.replace("apples", "pears").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), read(uri));
            scenario.recreate();
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                assertEquals(filtered.replace("apples", "pears"), editor.getText().toString());
                editor.setSelection(editor.getText().toString().indexOf("pears"));
                button(activity.getWindow().getDecorView(), "Filter uncompleted tasks").performClick();
                assertEquals(original.replace("apples", "pears"), editor.getText().toString());
                assertEquals(original.indexOf("apples"), editor.getSelectionStart());
            });
        }
    }

    @Test public void tasksViewWithNoOpenTasksIsEmptyAndRestoresAllLines() throws Exception {
        String original = "Plain notes\n[X] Finished task\n";
        Uri uri = seed("no-open-tasks.txt", original.getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                button(activity.getWindow().getDecorView(), "Filter uncompleted tasks").performClick();
                assertEquals("", editor.getText().toString());
                assertEquals(original, app.current().buffer.text());
                button(activity.getWindow().getDecorView(), "Filter uncompleted tasks").performClick();
                assertEquals(original, editor.getText().toString());
                assertFalse(app.current().buffer.dirty());
            });
        }
    }

    @Test public void tasksViewKeepsDividerHeadingsAndMapsHeadingEditsToTheFullText() throws Exception {
        String original = "Unrelated notes\n\nShopping\n-----\n[ ] Milk\n[X] Bread\n"
                + "\nFinished section\n---\n[X] Done\n";
        String filtered = "\nShopping\n-----\n[ ] Milk";
        Uri uri = seed("divider-headings.txt", original.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                View toggle = button(activity.getWindow().getDecorView(), "Filter uncompleted tasks");
                toggle.performClick();
                assertEquals(filtered, editor.getText().toString());
                assertArrayEquals(new int[]{1, 2, 3, 4}, editor.lineMap);
                assertFalse(app.current().buffer.dirty());
                editor.getText().replace(1, 1 + "Shopping".length(), "Groceries");
                assertEquals(original.replace("Shopping", "Groceries"), app.current().buffer.text());
                toggle.performClick();
                assertEquals(original.replace("Shopping", "Groceries"), editor.getText().toString());
            });
            assertArrayEquals(original.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), read(uri));
        }
    }

    @Test public void tasksViewKeepsDividerHeadingsAtFileStartWithoutAddingABlankLine() throws Exception {
        String original = "Shopping\n---\n[ ] Milk\n[X] Bread\n\nFinished section\n---\n[X] Done\n";
        Uri uri = seed("top-heading.txt", original.getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                View toggle = button(activity.getWindow().getDecorView(), "Filter uncompleted tasks");
                toggle.performClick();
                assertEquals("Shopping\n---\n[ ] Milk", editor.getText().toString());
                assertArrayEquals(new int[]{0, 1, 2}, editor.lineMap);
                assertFalse(app.current().buffer.dirty());
                editor.getText().replace(0, "Shopping".length(), "Groceries");
                assertEquals(original.replace("Shopping", "Groceries"), app.current().buffer.text());
                toggle.performClick();
                assertEquals(original.replace("Shopping", "Groceries"), editor.getText().toString());
            });
        }
    }

    @Test public void leavingTasksViewScrollsToTheCursorInTheFullDocument() throws Exception {
        String original = "[ ] First task\n" + "[X] Completed before target\n".repeat(250)
                + "[ ] Task to keep visible\n" + "[X] Completed after target\n".repeat(250);
        int realCursor = original.indexOf("keep visible") + "keep visible".length();
        Uri uri = seed("task-cursor.txt", original.getBytes(StandardCharsets.UTF_8));
        AtomicReference<CodeEditor> shownEditor = new AtomicReference<>();
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                shownEditor.set(editor);
                button(activity.getWindow().getDecorView(), "Filter uncompleted tasks").performClick();
            });
            waitFor(() -> shownEditor.get().getLayout() != null && !shownEditor.get().isLayoutRequested());
            scenario.onActivity(activity -> {
                CodeEditor editor = shownEditor.get();
                assertEquals("[ ] First task\n[ ] Task to keep visible", editor.getText().toString());
                editor.setSelection(editor.length());
                editor.scrollTo(0, 0);
                button(activity.getWindow().getDecorView(), "Filter uncompleted tasks").performClick();
                assertEquals(realCursor, editor.getSelectionStart());
                assertEquals(original, editor.getText().toString());
            });
            waitFor(() -> {
                CodeEditor editor = shownEditor.get();
                android.text.Layout layout = editor.getLayout();
                if (layout == null || editor.isLayoutRequested()) return false;
                int line = layout.getLineForOffset(editor.getSelectionStart());
                int top = layout.getLineTop(line) + editor.getTotalPaddingTop() - editor.getScrollY();
                int bottom = layout.getLineBottom(line) + editor.getTotalPaddingTop() - editor.getScrollY();
                return top >= editor.getTotalPaddingTop() && bottom <= editor.getHeight() - editor.getTotalPaddingBottom();
            });
            scenario.onActivity(activity -> {
                assertEquals(realCursor, shownEditor.get().getSelectionStart());
                assertFalse(app.current().buffer.dirty());
                assertTrue(shownEditor.get().getScrollY() > 0);
            });
        }
    }

    @Test public void tenMiBFileOpensEditsAndSavesOnDevice() throws Exception {
        byte[] bytes = new byte[TextCodec.MAX_BYTES]; java.util.Arrays.fill(bytes,(byte) 'a');
        // Keep paragraphs reasonably short; long-line behavior is exercised by the first paragraph.
        for (int i = 1023; i < bytes.length; i += 1024) bytes[i] = '\n';
        Uri uri = seed("large.txt",bytes);
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor); assertEquals(TextCodec.MAX_BYTES,editor.length());
                editor.getText().replace(0,1,"b"); app.save(app.current(),null);
            });
            waitFor(() -> !app.current().saving && !app.current().buffer.dirty());
            byte[] result = read(uri); assertEquals(bytes.length,result.length); assertEquals('b',result[0]);
        }
    }

    @Test public void exitPromptsEachUnsavedFileInSequence() throws Exception {
        Uri uri = seed("exit-save.txt", "before".getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                editor.getText().append(" after");
                app.newDocument();
                editor.getText().append("discard this");
                app.newDocument();
                editor.getText().append("cancel on this");
                assertEquals(3, app.tabs.size());
                activity.exitApp();
                assertTrue(app.exiting);
                assertEquals(app.tabs.get(0).id, app.selectedTab);
            });
        }
    }
}
