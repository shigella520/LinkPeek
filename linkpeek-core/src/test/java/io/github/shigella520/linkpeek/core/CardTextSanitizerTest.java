package io.github.shigella520.linkpeek.core;

import io.github.shigella520.linkpeek.core.util.CardTextSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardTextSanitizerTest {
    @Test
    void stripsEmojiFromCardTitle() {
        assertEquals("V2EX 标题", CardTextSanitizer.displayTitle("V2EX 😄 标题", "备用标题"));
    }

    @Test
    void fallsBackWhenTitleOnlyContainsUnsupportedSymbols() {
        assertEquals("备用标题", CardTextSanitizer.displayTitle("👨‍👩‍👧‍👦", "备用标题"));
    }

    @Test
    void collapsesWhitespaceAroundRemovedSymbols() {
        assertEquals("Hello world", CardTextSanitizer.sanitize("Hello   😄   world"));
    }
}
