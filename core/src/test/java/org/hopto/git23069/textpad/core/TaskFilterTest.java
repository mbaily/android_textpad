package org.hopto.git23069.textpad.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskFilterTest {

    @Test
    public void testSimpleUncompletedTasks() {
        String doc = "[ ] Task 1\n[X] Completed task\n[ ] Task 2\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertArrayEquals(new int[]{0, 2}, res.visibleLines());
        assertTrue(res.isUncompleted()[0]);
        assertFalse(res.isVisible()[1]);
        assertTrue(res.isUncompleted()[2]);
    }

    @Test
    public void testIndentedTaskIntroducesCompletedParent() {
        String doc = "[X] Parent project\n    [X] Step 1\n    [ ] Step 2\n[X] Other project\n    [X] Subtask\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        // Line 0 is [X] Parent project (context)
        // Line 1 is [X] Step 1 (hidden)
        // Line 2 is [ ] Step 2 (uncompleted)
        // Lines 3-4 are Other project & subtask (all completed, hidden)
        assertArrayEquals(new int[]{0, 2}, res.visibleLines());
        assertTrue(res.isContext()[0]);
        assertFalse(res.isUncompleted()[0]);
        assertTrue(res.isUncompleted()[2]);
    }

    @Test
    public void testPlainHeaderAndAncestor() {
        String doc = "Shopping:\n    [ ] Milk\n    [X] Bread\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertArrayEquals(new int[]{0, 1}, res.visibleLines());
        assertTrue(res.isContext()[0]);
        assertTrue(res.isUncompleted()[1]);
        assertFalse(res.isVisible()[2]);
    }

    @Test
    public void testPlainNotesIndentedUnderUncompletedTask() {
        String doc = "[ ] Buy groceries\n    Remember organic milk\n    Check expiry date\n[X] Clean house\n    Vacuum living room\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        // Lines 0, 1, 2 visible
        // Lines 3, 4 hidden
        assertArrayEquals(new int[]{0, 1, 2}, res.visibleLines());
        assertTrue(res.isUncompleted()[0]);
        assertTrue(res.isNote()[1]);
        assertTrue(res.isNote()[2]);
        assertFalse(res.isVisible()[3]);
        assertFalse(res.isVisible()[4]);
    }

    @Test
    public void testMarkdownHeadersContext() {
        String doc = "# Project Alpha\n## Section 1\n[ ] Important task\n## Section 2\n[X] Already done\n# Project Beta\n[X] Done beta\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        // Visible: # Project Alpha (0), ## Section 1 (1), [ ] Important task (2)
        // Hidden: Section 2, Done beta
        assertArrayEquals(new int[]{0, 1, 2}, res.visibleLines());
        assertTrue(res.isContext()[0]);
        assertTrue(res.isContext()[1]);
        assertTrue(res.isUncompleted()[2]);
    }

    @Test
    public void testTabsAndSpacesIndentation() {
        String doc = "Shopping:\n\t[ ] Apples\n\t\tNote with tabs\n    [ ] Bananas\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertArrayEquals(new int[]{0, 1, 2, 3}, res.visibleLines());
    }

    @Test
    public void testEmptyAndNoTasks() {
        String doc = "Just some text\nwithout any tasks\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertEquals(0, res.visibleCount());
        assertFalse(res.hasVisibleLines());
    }


    @Test
    public void testDeepNestingWithCompletedIntermediate() {
        String doc = "[ ] Top uncompleted\n    [X] Child completed\n        [ ] Grandchild uncompleted\n        [X] Grandchild completed\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertArrayEquals(new int[]{0, 1, 2}, res.visibleLines());
        assertTrue(res.isUncompleted()[0]);
        assertTrue(res.isContext()[1]);
        assertTrue(res.isUncompleted()[2]);
    }

    @Test
    public void dividerHeadingIncludesTheBlankTitleAndDividerWithoutIndentedTasks() {
        String doc = "Unrelated notes\n\nShopping\n--------\n[ ] Milk\n[X] Bread\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertArrayEquals(new int[]{1, 2, 3, 4}, res.visibleLines());
        for (int line : new int[]{1, 2, 3}) assertTrue(res.isContext()[line]);
        assertTrue(res.isUncompleted()[4]);
        assertFalse(res.isVisible()[5]);
    }

    @Test
    public void dividerHeadingAcceptsWhitespaceCrLfAndAnyConfiguredDashCount() {
        for (String divider : new String[]{"-", "---", "-".repeat(40), "-".repeat(200)}) {
            String doc = " \t\r\n\tShopping \r\n  " + divider + "\t\r\n[ ] Milk\r\n";
            TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
            assertArrayEquals(new int[]{0, 1, 2, 3}, res.visibleLines());
        }
    }

    @Test
    public void dividerHeadingRequiresABlankThenANonblankTitleThenOnlyDashes() {
        for (String prefix : new String[]{"Shopping\n---", "Notes\nShopping\n---", "\n\n---",
                "\nShopping\n-- text --", "\nShopping\n- - -", "\nShopping\n===", "\nShopping\n"}) {
            String doc = prefix + "\n[ ] Milk\n";
            TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
            assertArrayEquals(new int[]{doc.split("\n", -1).length - 2}, res.visibleLines());
        }
    }

    @Test
    public void dividerHeadingsWorkAlongsideMarkdownHeadingsAndTaskNotes() {
        String doc = "# Projects\n\nShopping\n---\n[ ] Milk\n    Remember organic\n[X] Bread\n"
                + "\nHousework\n-----\n[ ] Sweep\n[X] Dust\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 7, 8, 9, 10}, res.visibleLines());
        assertTrue(res.isNote()[5]);
        assertTrue(res.isContext()[8]);
        assertFalse(res.isVisible()[6]);
        assertFalse(res.isVisible()[11]);
    }

    @Test
    public void dividerHeadingsStayVisibleWithoutAnyUncompletedTasks() {
        String doc = "Unrelated\n\nFinished section\n---\n[X] Done\n\nEmpty section\n-\n";
        TaskFilter.FilterResult res = TaskFilter.filter(doc, 4);
        assertArrayEquals(new int[]{1, 2, 3, 5, 6, 7}, res.visibleLines());
        for (int line : res.visibleLines()) assertTrue(res.isContext()[line]);
        for (boolean uncompleted : res.isUncompleted()) assertFalse(uncompleted);
    }

}
