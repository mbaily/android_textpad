package org.hopto.git23069.textpad.core;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HashtagsTest {
    @Test
    public void extractsValidHashtagsAndIgnoresUrlsAndNumbers() {
        String text = """
                [ ] Buy timber at #Bunnings and screws #hardware-store.
                Visit https://example.com#section or color #101313 for reference.
                #errands_1, (#chemist)
                """;
        List<String> tags = Hashtags.extractHashtags(text);
        assertEquals(List.of("bunnings", "hardware-store", "errands_1", "chemist"), tags);
    }

    @Test
    public void parsesAndFormatsTagLists() {
        List<String> parsed = Hashtags.parseTagList("#Bunnings, chemist  #post-office #bunnings");
        assertEquals(List.of("bunnings", "chemist", "post-office"), parsed);
        assertEquals("#bunnings #chemist #post-office", Hashtags.formatTagList(parsed));
    }

    @Test
    public void searchesLinesAndRespectsHideCompletedToggle() {
        String text = "[ ] Pick up prescription #chemist\n[X] Bought milk #groceries\n[ ] Apples and bread #groceries #errands";
        Hashtags.FileMatch all = Hashtags.search(text, List.of("chemist", "groceries"), false);
        assertTrue(all.matches());
        assertEquals(List.of("chemist", "groceries"), all.matchedTags());
        assertEquals(3, all.lines().size());
        assertEquals(1, all.lines().get(0).lineNumber());
        assertEquals("[ ] Pick up prescription #chemist", all.lines().get(0).lineText());
        assertEquals(2, all.lines().get(1).lineNumber());
        assertEquals(3, all.lines().get(2).lineNumber());

        Hashtags.FileMatch uncompletedOnly = Hashtags.search(text, List.of("chemist", "groceries"), true);
        assertEquals(2, uncompletedOnly.lines().size());
        assertEquals(1, uncompletedOnly.lines().get(0).lineNumber());
        assertEquals(3, uncompletedOnly.lines().get(1).lineNumber());
    }

    @Test
    public void hidesCompletedAndCancelledTasksWithBulletsAndPreservesResultOffsets() {
        String text = "[ ] Open #errands\r\n\t[C] Cancelled #errands\r\n"
                + "    - [c] Cancelled bullet #errands\r\n* [X] Done #errands\r\n"
                + "+ [x] Done too #errands\r\nNote about [C] #errands\r\n";
        assertEquals(6, Hashtags.search(text, List.of("errands"), false).lines().size());
        Hashtags.FileMatch filtered = Hashtags.search(text, List.of("errands"), true);
        assertEquals(List.of(1, 6), filtered.lines().stream().map(Hashtags.LineMatch::lineNumber).toList());
        assertEquals(text.indexOf("Note about"), filtered.lines().get(1).lineStartOffset());
        assertFalse(Hashtags.isCompletedTodoLine("[C] Cancelled"));
    }
}
