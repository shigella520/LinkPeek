package io.github.shigella520.linkpeek.core;

import io.github.shigella520.linkpeek.core.util.HtmlEscaper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlEscaperTest {
    @Test
    void escapesHtmlReservedCharacters() {
        assertEquals("&lt;a&gt;&quot;x&quot; &amp; y&lt;/a&gt;", HtmlEscaper.escape("<a>\"x\" & y</a>"));
    }
}
