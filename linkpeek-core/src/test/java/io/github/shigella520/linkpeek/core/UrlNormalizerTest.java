package io.github.shigella520.linkpeek.core;

import io.github.shigella520.linkpeek.core.error.InvalidPreviewUrlException;
import io.github.shigella520.linkpeek.core.util.UrlNormalizer;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlNormalizerTest {
    @Test
    void normalizesSchemeHostDefaultPortAndFragment() {
        URI normalized = UrlNormalizer.parseHttpUrl("HTTPS://Example.COM:443/hello?q=1#frag");

        assertEquals("https://example.com/hello?q=1", normalized.toString());
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThrows(InvalidPreviewUrlException.class, () -> UrlNormalizer.parseHttpUrl("ftp://example.com/file"));
    }
}
