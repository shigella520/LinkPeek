package io.github.shigella520.linkpeek.server.render;

import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.util.HtmlEscaper;
import org.springframework.stereotype.Component;

@Component
public class HtmlPageRenderer {
    public String renderPreview(PreviewMetadata metadata, PreviewKey previewKey, String baseUrl) {
        String imageUrl = join(baseUrl, "/media/thumb/" + previewKey.value() + ".jpg");
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="utf-8">
                <meta property="og:type" content="%s" />
                <meta property="og:title" content="%s" />
                <meta property="og:description" content="%s" />
                <meta property="og:site_name" content="%s" />
                <meta property="og:url" content="%s" />
                <meta property="og:image" content="%s" />
                <meta property="og:image:width" content="%d" />
                <meta property="og:image:height" content="%d" />
                <title>%s</title>
                </head>
                <body></body>
                </html>
                """.formatted(
                metadata.contentType().ogType(),
                HtmlEscaper.escape(metadata.title()),
                HtmlEscaper.escape(metadata.description()),
                HtmlEscaper.escape(metadata.siteName()),
                HtmlEscaper.escape(metadata.canonicalUrl()),
                HtmlEscaper.escape(imageUrl),
                metadata.imageWidth(),
                metadata.imageHeight(),
                HtmlEscaper.escape(metadata.title())
        );
    }

    public String renderError(String title, String message) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                </head>
                <body>
                <h1>%s</h1>
                <p>%s</p>
                </body>
                </html>
                """.formatted(
                HtmlEscaper.escape(title),
                HtmlEscaper.escape(title),
                HtmlEscaper.escape(message)
        );
    }

    private String join(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + path;
    }
}
