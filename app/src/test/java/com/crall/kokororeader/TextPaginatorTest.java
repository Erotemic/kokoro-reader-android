package com.crall.kokororeader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

public class TextPaginatorTest {
    @Test
    public void normalizeCopiedTextRepairsLineBreaksAndHyphenation() {
        String raw = "  A hyphen-\nated line.\r\nStill same paragraph.\r\n\r\nSecond\tparagraph.  ";

        assertEquals(
                "A hyphenated line. Still same paragraph.\n\nSecond paragraph.",
                TextPaginator.normalizeCopiedText(raw));
    }

    @Test
    public void splitIntoPagesKeepsShortParagraphsTogether() {
        ArrayList<String> pages = TextPaginator.splitIntoPages(
                "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.",
                40);

        assertEquals(2, pages.size());
        assertEquals("First paragraph.\n\nSecond paragraph.", pages.get(0));
        assertEquals("Third paragraph.", pages.get(1));
    }

    @Test
    public void splitIntoPagesPrefersSentenceBoundaries() {
        ArrayList<String> pages = TextPaginator.splitIntoPages(
                "One short sentence. Two short sentences. Three short sentences.",
                42);

        assertEquals(2, pages.size());
        assertEquals("One short sentence. Two short sentences.", pages.get(0));
        assertEquals("Three short sentences.", pages.get(1));
    }

    @Test
    public void hardSplitNeverExceedsLimitAndPreservesWordsWhenPossible() {
        String text = "alpha beta gamma delta epsilon zeta eta theta iota";
        ArrayList<String> pages = TextPaginator.splitIntoPages(text, 14);

        assertTrue(pages.size() > 1);
        for (String page : pages) {
            assertFalse(page.isEmpty());
            assertTrue("page was too long: " + page, page.length() <= 14);
        }
        assertEquals(text, String.join(" ", pages));
    }

    @Test(timeout = 1000L)
    public void nonPositiveLimitCannotLoopForever() {
        ArrayList<String> pages = TextPaginator.splitIntoPages("abc", 0);

        assertEquals(3, pages.size());
        assertEquals("a", pages.get(0));
        assertEquals("b", pages.get(1));
        assertEquals("c", pages.get(2));
    }

    @Test
    public void blankInputProducesNoPages() {
        assertTrue(TextPaginator.splitIntoPages(null, 100).isEmpty());
        assertTrue(TextPaginator.splitIntoPages("  \n\n ", 100).isEmpty());
        assertEquals("", TextPaginator.normalizeCopiedText(null));
    }
}
