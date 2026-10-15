package org.hopto.git23069.textpad.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContentSimilarityTest {
    @Test public void identicalAndUnrelatedTextHaveExpectedScores() {
        assertEquals(100,ContentSimilarity.percent("hello","hello")); assertEquals(0,ContentSimilarity.percent("","different"));
        assertEquals(0,ContentSimilarity.percent("aaaaaaaaaaaaaaaa","zzzzzzzzzzzzzzzz"));
    }
    @Test public void aSmallEditStillSuggestsSimilarity() {
        String text="The same document with a few paragraphs of saved text.\n".repeat(100);
        assertTrue(ContentSimilarity.percent(text,text.replaceFirst("saved","updated"))>70);
    }
}
