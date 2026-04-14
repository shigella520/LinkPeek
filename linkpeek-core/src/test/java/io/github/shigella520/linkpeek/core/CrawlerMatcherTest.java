package io.github.shigella520.linkpeek.core;

import io.github.shigella520.linkpeek.core.util.CrawlerMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlerMatcherTest {
    @Test
    void matchesKnownCrawlerSignaturesCaseInsensitively() {
        CrawlerMatcher matcher = new CrawlerMatcher(List.of("facebookexternalhit", "TwitterBot"));

        assertTrue(matcher.matches("Mozilla facebookexternalhit/1.1"));
        assertTrue(matcher.matches("TWITTERBOT/1.0"));
        assertFalse(matcher.matches("Mozilla/5.0 Safari"));
    }
}
