package io.github.shigella520.linkpeek.server.render;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareSummaryReportPageRendererTest {
    private final ShareSummaryReportPageRenderer renderer = new ShareSummaryReportPageRenderer();

    @Test
    void escapesEveryDynamicValueAndNeverExpandsPlaceholdersInsideValues() {
        ShareSummaryImageRecord image = new ShareSummaryImageRecord();
        image.setOgTitle("<script>alert('t')</script>{{description}}");
        image.setOgDescription("\" onload=\"alert(1)");
        image.setOgImageUrl("https://example.com/i.png?a=1&b=\"2\"");
        image.setOgPageUrl("https://example.com/r'x");

        String html = renderer.render(
                image,
                "<img src=x onerror=alert(1)> {{{reportHtml}}} {{title}}",
                "https://example.com/a\".mp3",
                "audio/<x>"
        );

        assertFalse(html.contains("<script>alert"));
        assertFalse(html.contains("<img src=x"));
        assertFalse(html.contains("\" onload=\""));
        assertTrue(html.contains("<title>&lt;script&gt;alert(&#39;t&#39;)&lt;/script&gt;{{description}}</title>"));
        assertTrue(html.contains("<meta property=\"og:image\" content=\"https://example.com/i.png?a=1&amp;b=&quot;2&quot;\">"));
        assertTrue(html.contains("<meta property=\"og:url\" content=\"https://example.com/r&#39;x\">"));
        assertTrue(html.contains("<meta property=\"og:audio:type\" content=\"audio/&lt;x&gt;\">"));
        assertTrue(html.contains("data-audio-play-url=\"https://example.com/a&quot;.mp3/plays\""));
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt; {{{reportHtml}}} {{title}}"));
    }

    @Test
    void hidesAudioPlayerAndOmitsAudioMetaWithoutAudio() {
        ShareSummaryImageRecord image = new ShareSummaryImageRecord();
        image.setOgTitle("标题");

        String html = renderer.render(image, null, null, null);

        assertFalse(html.contains("og:audio"));
        assertTrue(html.contains("data-audio-src=\"\" data-audio-play-url=\"\" hidden aria-label=\"报告音频播放\""));
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"" + renderer.cssUrl() + "\">"));
        assertTrue(html.contains("<script type=\"module\" src=\"" + renderer.jsUrl() + "\"></script>"));
        assertTrue(renderer.cssUrl().matches("/share-summary/report\\.css\\?v=[0-9a-f]{12}"));
        assertTrue(renderer.jsUrl().matches("/share-summary/report\\.js\\?v=[0-9a-f]{12}"));
    }
}
