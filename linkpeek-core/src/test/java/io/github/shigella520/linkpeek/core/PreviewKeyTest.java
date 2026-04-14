package io.github.shigella520.linkpeek.core;

import io.github.shigella520.linkpeek.core.model.PreviewKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PreviewKeyTest {
    @Test
    void normalizesCanonicalUrlBeforeHashing() {
        PreviewKey first = PreviewKey.fromCanonicalUrl("HTTPS://WWW.BILIBILI.COM:443/video/BV1xx411c7mD#frag");
        PreviewKey second = PreviewKey.fromCanonicalUrl("https://www.bilibili.com/video/BV1xx411c7mD");

        assertEquals(first, second);
    }

    @Test
    void differsForDifferentCanonicalUrls() {
        PreviewKey first = PreviewKey.fromCanonicalUrl("https://www.bilibili.com/video/BV1xx411c7mD");
        PreviewKey second = PreviewKey.fromCanonicalUrl("https://www.bilibili.com/video/BV9xx411c7mD");

        assertNotEquals(first, second);
    }
}
