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

    @Test
    void stripsEmojiSymbolsOutsideThePictographBlocks() {
        assertEquals("280积分出 Telegram【印度+91】自动发货", CardTextSanitizer.sanitize("280积分出 Telegram【印度+91】⭐自动发货"));
        assertEquals("国旗 文本", CardTextSanitizer.sanitize("国旗 🇨🇳 文本"));
    }
}
