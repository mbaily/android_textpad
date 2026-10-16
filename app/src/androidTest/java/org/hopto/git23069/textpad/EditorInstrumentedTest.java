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
            app.tabs.clear(); app.selectedTab = -1; app.events.clear(); app.settings.prefs.edit().clear().commit();
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

    @Test public void nativeEditorSavesUnicodeAndMixedTerminatorsThroughSaf() throws Exception {
        Uri uri = seed("unicode.txt", "\ufeffone\r\n😀 café\nالعربية\r".getBytes(StandardCharsets.UTF_8));
        try (var scenario = launch(uri)) {
            waitFor(() -> app.current() != null);
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                assertEquals("one\n😀 café\nالعربية\n", editor.getText().toString());
                editor.getText().insert(0, "prefix "); app.save(app.current(), null);
            });
            waitFor(() -> !app.current().saving && !app.current().buffer.dirty());
            assertArrayEquals("\ufeffprefix one\r\n😀 café\nالعربية\r".getBytes(StandardCharsets.UTF_8),read(uri));
            assertFalse(app.store.backups().isEmpty());
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
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                CodeEditor editor = activity.findViewById(R.id.editor);
                assertEquals(2,app.tabs.size()); assertEquals(3,editor.getSelectionStart());
                assertTrue(editor.getText().toString().endsWith("😀")); assertTrue(app.current().buffer.dirty());
            });
        }
    }

    @Test public void toolbarUndoRedoAndIndentUseTheSharedHistory() throws Exception {
        try (var scenario = launch(null)) {
            scenario.onActivity(activity -> {
                app.newDocument(); CodeEditor editor = activity.findViewById(R.id.editor);
                editor.getText().append("hello\nworld"); editor.setSelection(0,editor.length());
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
