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
        doc.edit(insertion.offset(), insertion.offset(), insertion.text());
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
        assertEquals("\nlast", LineInsertion.below("first\n", 6, "", "last").text());
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
