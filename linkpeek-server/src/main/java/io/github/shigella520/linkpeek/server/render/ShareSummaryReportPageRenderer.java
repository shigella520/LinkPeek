package io.github.shigella520.linkpeek.server.render;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the public share-summary report page from {@code templates/share-summary/report.html}.
 *
 * <p>The template is parsed once. {@code {{name}}} placeholders are always HTML-escaped; {@code {{{name}}}}
 * inserts trusted HTML and is only allowed for the names in {@link #TRUSTED_HTML}. Substitution is a
 * single pass over the parsed template, so placeholder-like text inside values (e.g. report Markdown) is
 * never expanded.
 *
 * <p>The page's CSS and JS are static files whose URLs carry {@code ?v=} with a hash of their content,
 * computed once at startup: a reverse proxy caches {@code .css}/{@code .js} for a long time, and the hash
 * changes exactly when a deploy changes the file.
 */
@Component
public class ShareSummaryReportPageRenderer {
    static final String TEMPLATE_PATH = "templates/share-summary/report.html";
    static final String CSS_PATH = "/share-summary/report.css";
    static final String JS_PATH = "/share-summary/report.js";
    private static final String STATIC_ROOT = "static";
    private static final String DEFAULT_AUDIO_MEDIA_TYPE = "audio/mpeg";
    private static final Set<String> TRUSTED_HTML = Set.of("audioMeta", "reportHtml");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\{(\\w+)}}}|\\{\\{(\\w+)}}");
    private static final int VERSION_LENGTH = 12;

    private final List<Segment> template;
    private final String cssUrl;
    private final String jsUrl;

    public ShareSummaryReportPageRenderer() {
        this.template = parse(readClasspath(TEMPLATE_PATH));
        this.cssUrl = versionedUrl(CSS_PATH);
        this.jsUrl = versionedUrl(JS_PATH);
    }

    public String cssUrl() {
        return cssUrl;
    }

    public String jsUrl() {
        return jsUrl;
    }

    /**
     * @param reportMarkdown untrusted report Markdown; rendered through {@link ShareSummaryMarkdownRenderer}
     * @param audioUrl       public audio URL, or blank when the report has no playable audio
     */
    public String render(ShareSummaryImageRecord image, String reportMarkdown, String audioUrl, String audioMediaType) {
        boolean hasAudio = StringUtils.hasText(audioUrl);
        String safeAudioUrl = hasAudio ? audioUrl : "";
        String mediaType = StringUtils.hasText(audioMediaType) ? audioMediaType : DEFAULT_AUDIO_MEDIA_TYPE;
        Map<String, String> values = new HashMap<>();
        values.put("title", image.getOgTitle());
        values.put("description", image.getOgDescription());
        values.put("imageUrl", image.getOgImageUrl());
        values.put("pageUrl", image.getOgPageUrl());
        values.put("audioMeta", audioMeta(safeAudioUrl, mediaType));
        values.put("audioUrl", safeAudioUrl);
        values.put("audioPlayUrl", hasAudio ? safeAudioUrl + "/plays" : "");
        values.put("audioHidden", hasAudio ? "" : "hidden");
        values.put("reportCssUrl", cssUrl);
        values.put("reportJsUrl", jsUrl);
        values.put("reportHtml", ShareSummaryMarkdownRenderer.toHtml(StringUtils.hasText(reportMarkdown) ? reportMarkdown : ""));

        StringBuilder html = new StringBuilder(16 * 1024);
        for (Segment segment : template) {
            if (segment.name() == null) {
                html.append(segment.text());
                continue;
            }
            if (!values.containsKey(segment.name())) {
                throw new IllegalStateException("No value for report template placeholder " + segment.name());
            }
            String value = values.get(segment.name());
            html.append(segment.trustedHtml() ? value : escape(value));
        }
        return html.toString();
    }

    private String audioMeta(String audioUrl, String audioMediaType) {
        if (!StringUtils.hasText(audioUrl)) {
            return "";
        }
        String escapedAudioUrl = escape(audioUrl);
        return """
                <meta property="og:audio" content="%s">
                    <meta property="og:audio:secure_url" content="%s">
                    <meta property="og:audio:type" content="%s">""".formatted(
                escapedAudioUrl,
                escapedAudioUrl,
                escape(audioMediaType)
        );
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static List<Segment> parse(String source) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(source);
        int position = 0;
        while (matcher.find()) {
            if (matcher.start() > position) {
                segments.add(new Segment(source.substring(position, matcher.start()), null, false));
            }
            boolean trusted = matcher.group(1) != null;
            String name = trusted ? matcher.group(1) : matcher.group(2);
            if (trusted && !TRUSTED_HTML.contains(name)) {
                throw new IllegalStateException("Report template placeholder " + name + " must be escaped");
            }
            segments.add(new Segment(null, name, trusted));
            position = matcher.end();
        }
        segments.add(new Segment(source.substring(position), null, false));
        return List.copyOf(segments);
    }

    private static String versionedUrl(String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(readClasspathBytes(STATIC_ROOT + path));
            return path + "?v=" + HexFormat.of().formatHex(digest).substring(0, VERSION_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String readClasspath(String path) {
        return new String(readClasspathBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] readClasspathBytes(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read classpath resource " + path, exception);
        }
    }

    private record Segment(String text, String name, boolean trustedHtml) {
    }
}
