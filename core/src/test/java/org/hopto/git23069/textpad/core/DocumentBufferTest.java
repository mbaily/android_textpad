package org.hopto.git23069.textpad.core;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import static org.junit.Assert.*;

public class DocumentBufferTest {
    private DocumentBuffer document(String text) { return new DocumentBuffer(TextCodec.split(text, TextCodec.Encoding.UTF8, false)); }

    @Test public void editsPreserveExistingMixedTerminatorsAndUndoRestoresExactBytes() throws Exception {
        String source = "one\r\ntwo\nthree\rfour";
        var doc = document(source);
        doc.edit(4, 7, "TWO");
        assertEquals("one\r\nTWO\nthree\rfour", new String(doc.snapshot().bytes(), StandardCharsets.UTF_8));
        doc.edit(2, 12, "X\nY"); doc.undo(); doc.undo();
        assertEquals(source, new String(doc.snapshot().bytes(), StandardCharsets.UTF_8)); assertFalse(doc.dirty());
        doc.redo(); assertTrue(doc.dirty());
    }
    @Test public void replaceWholeLinesPreservesMixedEndings() throws Exception {
        var doc = document("a\r\nb\nc\r"); doc.edit(0, doc.length(), " A\n B\n C\n");
        assertEquals(" A\r\n B\n C\r", new String(doc.snapshot().bytes(), StandardCharsets.UTF_8));
    }
    @Test public void newLinesUseExistingDominantConvention() throws Exception {
        var doc = document("a\r\nb\r\n"); doc.edit(doc.length(), doc.length(), "c\nd");
        assertEquals("a\r\nb\r\nc\r\nd", new String(doc.snapshot().bytes(), StandardCharsets.UTF_8));
    }
    @Test public void editsDuringSaveStayDirty() throws Exception {
        var doc = document("a"); doc.edit(1,1,"b"); var snapshot = doc.snapshot();
        doc.edit(2,2,"c"); doc.markSaved(snapshot.state()); assertTrue(doc.dirty());
        doc.undo(); assertFalse(doc.dirty()); doc.redo(); assertTrue(doc.dirty());
    }
    @Test public void editingAfterUndoBranchesHistory() {
        var doc = document("a"); doc.edit(1,1,"b"); doc.edit(2,2,"c"); doc.undo(); doc.edit(2,2,"d");
        assertFalse(doc.canRedo()); assertEquals("abd", doc.text());
        doc.undo(); doc.undo(); assertEquals("a", doc.text()); assertFalse(doc.dirty());
    }
    @Test public void noOpEditKeepsFileClean() {
        var doc = document("same\r\n"); doc.edit(0,doc.length(),"same\n"); assertFalse(doc.dirty());
    }
    @Test public void randomEditsMaintainLineIndexAndUndoHistory() throws Exception {
        var doc = document("first\r\nsecond\n"); String expected = doc.text(); Random random = new Random(183);
        for (int i = 0; i < 800; i++) {
            int start = random.nextInt(expected.length() + 1), end = start + random.nextInt(expected.length() - start + 1);
            String insert = new String[]{"a", "\n", "xyz\nq", "", "\n\n"}[random.nextInt(5)];
            doc.edit(start,end,insert); expected = expected.substring(0,start) + insert + expected.substring(end);
            assertEquals(expected,doc.text());
            for (int p = 0; p <= expected.length(); p++) {
                int line = 0, lineStart = 0;
                for (int k = 0; k < p; k++) if (expected.charAt(k) == '\n') { line++; lineStart = k + 1; }
                assertEquals(line,doc.lineAt(p)); assertEquals(lineStart,doc.lineStart(line));
            }
            assertEquals(expected, TextCodec.decode(doc.snapshot().bytes()).text());
        }
        while (doc.canUndo()) doc.undo(); assertEquals("first\nsecond\n",doc.text()); assertFalse(doc.dirty());
    }
}
