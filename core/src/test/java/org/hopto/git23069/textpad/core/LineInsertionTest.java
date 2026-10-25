package org.hopto.git23069.textpad.core;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class LineInsertionTest {
    @Test public void copiesWhitespaceAndInfersNearbySpaceSteps() {
        String text = "root\n  first\n    nested\n  sibling\n\n    last";
        assertEquals("    ", LineInsertion.indentation(text, text.indexOf("nested") + 2));
        assertEquals("  ", LineInsertion.indentUnit(text, text.indexOf("nested"), 4));
        assertEquals("    ", LineInsertion.indentUnit("root\n    first\n        nested\n    sibling", 0, 2));
        assertEquals("   ", LineInsertion.indentUnit("root\n   first\n      nested", 0, 4));
    }

    @Test public void blanksAndAlignmentDoNotOverrideRepeatedSteps() {
        String text = "root\n  first\n    nested\n  sibling\n\n                 aligned\n  another";
        assertEquals("  ", LineInsertion.indentUnit(text, 0, 4));
        assertEquals("    ", LineInsertion.indentUnit("root\n\nplain", 0, 4));
        assertEquals("  ", LineInsertion.indentUnit("  first\n  second", 0, 4));
        assertEquals("    ", LineInsertion.indentUnit(" ".repeat(12) + "alignment", 0, 4));
    }

    @Test public void preservesTabsAndBoundsDecreasesAtZero() {
        String text = "root\n\tfirst\n\t\tnested";
        assertEquals("\t", LineInsertion.indentUnit(text, 0, 4));
        assertEquals("\t\t", LineInsertion.adjustIndent("\t", "\t", true));
        assertEquals("\t", LineInsertion.adjustIndent("\t\t", "\t", false));
        assertEquals("", LineInsertion.adjustIndent(" ", "    ", false));
        assertEquals("", LineInsertion.adjustIndent("", "  ", false));
        assertEquals("\t", LineInsertion.adjustIndent("\t  ", "  ", false));
    }

    @Test public void insertsAfterTheWholeLineAndKeepsExistingMixedEndings() throws Exception {
        String source = "root\r\n  first\r\n    nested\nlast";
        var doc = new DocumentBuffer(TextCodec.split(source, TextCodec.Encoding.UTF8, false));
        var insertion = LineInsertion.below(doc.text(), doc.text().indexOf("first") + 2, "  ", "new 😀");
        doc.edit(insertion.offset(), insertion.end(), insertion.text());
        assertEquals(source.replace("  first\r\n", "  first\r\n  new 😀\r\n"),
                new String(doc.snapshot().bytes(), StandardCharsets.UTF_8));
        assertEquals(doc.text().indexOf("new 😀") + "new 😀".length(), insertion.cursor());
        doc.undo(); assertEquals(source, new String(doc.snapshot().bytes(), StandardCharsets.UTF_8));
        assertFalse(doc.dirty()); doc.redo(); assertTrue(doc.dirty());
    }

    @Test public void handlesEmptyDocumentsAndBothFinalNewlineCases() {
        assertEquals("hello", LineInsertion.below("", 0, "", "hello").text());
        var last = LineInsertion.below("first", 2, "  ", "last");
        assertEquals(5, last.offset()); assertEquals("\n  last", last.text());
        var terminated = LineInsertion.below("first\n", 2, "", "last");
        assertEquals(5, terminated.offset()); assertEquals("\nlast", terminated.text());
        assertEquals("last", LineInsertion.below("first\n", 6, "", "last").text());
    }

    @Test public void fillsOnlyTheCurrentBlankLineWithoutAddingASeparator() {
        String[][] cases = {
                {"first\n\nlast", "6", "  ", "first\n  added\nlast"},
                {"first\n \t \nlast", "7", "  ", "first\n  added\nlast"},
                {" \t ", "1", "", "added"},
                {"first\n", "6", "", "first\nadded"},
                {"first\n\n\nlast", "6", "", "first\nadded\n\nlast"},
                {"\nfirst", "0", "", "added\nfirst"},
        };
        for (String[] item : cases) {
            var doc = new DocumentBuffer(TextCodec.split(item[0], TextCodec.Encoding.UTF8, false));
            int lines = doc.lineCount();
            var insertion = LineInsertion.below(doc.text(), Integer.parseInt(item[1]), item[2], "added");
            doc.edit(insertion.offset(), insertion.end(), insertion.text());
            assertEquals(item[3], doc.text()); assertEquals(lines, doc.lineCount());
            assertEquals(item[3].indexOf("added") + 5, insertion.cursor());
        }
    }

    @Test public void fillingWhitespacePreservesExistingTerminatorsAndUndoRestoresExactBytes() throws Exception {
        String source = "first\r\n \t \r\nlast\n";
        var doc = new DocumentBuffer(TextCodec.split(source, TextCodec.Encoding.UTF8, false));
        var insertion = LineInsertion.below(doc.text(), 7, "    ", "filled 😀");
        assertEquals(6, insertion.offset()); assertEquals(9, insertion.end());
        doc.edit(insertion.offset(), insertion.end(), insertion.text());
        byte[] expected = source.replace(" \t \r\n", "    filled 😀\r\n").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, doc.snapshot().bytes()); assertEquals(4, doc.lineCount());
        doc.undo(); assertArrayEquals(source.getBytes(StandardCharsets.UTF_8), doc.snapshot().bytes());
        assertFalse(doc.dirty()); doc.redo(); assertArrayEquals(expected, doc.snapshot().bytes());
    }

    @Test public void nonBlankWhitespaceCharactersRemainProtected() {
        String source = " \u00a0";
        var insertion = LineInsertion.below(source, 1, "", "added");
        assertEquals(source.length(), insertion.offset()); assertEquals(source.length(), insertion.end());
        assertEquals("\nadded", insertion.text());
    }

    @Test public void rejectsMultilineInputWithoutEditing() {
        for (String input : new String[]{"a\nb", "a\rb", "a\r\nb", "a\u2028b", "a\u2029b"}) {
            assertThrows(IllegalArgumentException.class, () -> LineInsertion.below("original", 0, "", input));
        }
        assertThrows(IllegalArgumentException.class, () -> LineInsertion.below("original", 0, "x", "valid"));
    }

    @Test public void localStyleWinsInLongMixedDocuments() {
        String text = "root\n    first\n        nested\n".repeat(150)
                + "root\n  first\n    nested\n".repeat(150);
        assertEquals("  ", LineInsertion.indentUnit(text, text.length(), 4));
        assertEquals("    ", LineInsertion.indentUnit(text, 0, 2));
    }
}
