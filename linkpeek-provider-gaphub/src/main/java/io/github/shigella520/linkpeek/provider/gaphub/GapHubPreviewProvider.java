package io.github.shigella520.linkpeek.provider.gaphub;

import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.media.TitleCardRenderer;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.core.util.PreviewRawContentFormatter;
import io.github.shigella520.linkpeek.core.util.UrlNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GapHubPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(GapHubPreviewProvider.class);

    private static final Pattern TOPIC_PATH_PATTERN = Pattern.compile("^/topics/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/?$");
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_TAG_PATTERN = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))");
    private static final Pattern H1_PATTERN = Pattern.compile("(?is)<h1\\b[^>]*>(.*?)</h1>");
    private static final Pattern PROSE_BLOCK_PATTERN = Pattern.compile("(?is)<div\\b(?=[^>]*\\bclass=(\"[^\"]*\\bprose\\b[^\"]*\"|'[^']*\\bprose\\b[^']*'))[^>]*>(.*?)</div>");
    private static final Pattern FLIGHT_MARKDOWN_PATTERN = Pattern.compile("(?s)\\[\\\\\"\\$\\\\\",\\\\\"\\$L[0-9a-fA-F]+\\\\\",null,\\{\\\\\"className\\\\\":\\\\\"mt-2\\\\\",\\\\\"children\\\\\":\\\\\"((?:\\\\\\\\.|[^\\\\\"])*)\\\\\"}]");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);");

    private static final String CANONICAL_HOST = "gaphub.cc";
    private static final String SITE_NAME = "GapHub";
    private static final String TITLE_CARD_PREFIX = "generated://gaphub/topic-card/";
    private static final int CARD_WIDTH = TitleCardRenderer.WIDTH;
    private static final int CARD_HEIGHT = TitleCardRenderer.HEIGHT;
    private static final TitleCardRenderer.Watermark TITLE_CARD_WATERMARK =
            TitleCardRenderer.Watermark.resource(GapHubPreviewProvider.class, "title-card-watermark.png");
    private static final int MAX_DESCRIPTION_LENGTH = 280;
    private static final int MAX_RAW_CONTENT_LENGTH = 12_000;
    private static final int MAX_ERROR_BODY_LOG_CHARS = 1_000;
    private static final String ELLIPSIS = "...";

    private final HttpClient httpClient;
    private final URI pageBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;

    public GapHubPreviewProvider(
            HttpClient httpClient,
            URI pageBaseUri,
            Duration requestTimeout,
            String userAgent
    ) {
        this.httpClient = httpClient;
        this.pageBaseUri = pageBaseUri;
        this.requestTimeout = requestTimeout;
        this.userAgent = userAgent;
    }

    @Override
    public String getId() {
        return "gaphub";
    }

    @Override
    public boolean supports(URI sourceUrl) {
        try {
            URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
            return isSupportedHost(normalized) && extractTopicId(normalized).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public URI canonicalize(URI sourceUrl) {
        URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        if (!isSupportedHost(normalized)) {
            throw new UnsupportedPreviewUrlException("Only GapHub topic URLs are supported.");
        }

        String topicId = extractTopicId(normalized)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only GapHub topic URLs are supported."));
        return URI.create("https://" + CANONICAL_HOST + "/topics/" + topicId.toLowerCase());
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        URI normalizedSourceUrl = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        URI canonicalUrl = canonicalize(sourceUrl);
        String topicId = extractTopicId(canonicalUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only GapHub topic URLs are supported."));
        URI requestUri = pageBaseUri.resolve("/topics/" + topicId);

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Referer", pageBaseUri.toString())
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                log.warn(
                        "gaphub_topic_page_http_error requestUri={} status={} responseBody={}",
                        requestUri,
                        response.statusCode(),
                        responseBodySnippet(response.body())
                );
                throw new UpstreamFetchException("GapHub topic page returned HTTP " + response.statusCode());
            }

            String html = new String(response.body(), StandardCharsets.UTF_8);
            String title = extractTitle(html);
            if (title.isBlank()) {
                throw new UpstreamFetchException("Failed to parse GapHub topic title from the page.");
            }

            String rawContent = extractRawContent(title, html);
            String description = extractDescription(html)
                    .or(() -> firstContentBlock(rawContent))
                    .orElse("");

            return new PreviewMetadata(
                    normalizedSourceUrl.toString(),
                    canonicalUrl.toString(),
                    getId(),
                    title,
                    description,
                    SITE_NAME,
                    TITLE_CARD_PREFIX + topicId,
                    CARD_WIDTH,
                    CARD_HEIGHT,
                    ContentType.ARTICLE,
                    rawContent
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while fetching the GapHub topic page.", exception);
        } catch (IOException exception) {
            log.warn(
                    "gaphub_topic_page_request_failed requestUri={} timeoutMs={}",
                    requestUri,
                    requestTimeout.toMillis(),
                    exception
            );
            throw translateIOException(exception, "Failed to fetch or parse the GapHub topic page.");
        }
    }

    @Override
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        TitleCardRenderer.render(metadata.title(), SITE_NAME, metadata.canonicalUrl(), SITE_NAME, TITLE_CARD_WATERMARK, targetPath);
        return targetPath;
    }

    private boolean isSupportedHost(URI sourceUrl) {
        String host = sourceUrl.getHost();
        return CANONICAL_HOST.equals(host) || "www.gaphub.cc".equals(host);
    }

    private Optional<String> extractTopicId(URI sourceUrl) {
        Matcher matcher = TOPIC_PATH_PATTERN.matcher(sourceUrl.getRawPath());
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private String extractTitle(String html) {
        String metaTitle = extractMetaContent(html, "og:title")
                .or(() -> extractMetaContent(html, "twitter:title"))
                .orElse("");
        if (!metaTitle.isBlank()) {
            return cleanTitle(metaTitle);
        }

        Matcher h1Matcher = H1_PATTERN.matcher(html);
        if (h1Matcher.find()) {
            String h1 = decodeAndClean(stripTags(h1Matcher.group(1)));
            if (!h1.isBlank()) {
                return h1;
            }
        }

        Matcher titleMatcher = TITLE_TAG_PATTERN.matcher(html);
        if (!titleMatcher.find()) {
            return "";
        }
        return cleanTitle(titleMatcher.group(1));
    }

    private Optional<String> extractDescription(String html) {
        return extractMetaContent(html, "og:description")
                .or(() -> extractMetaContent(html, "description"))
                .or(() -> extractMetaContent(html, "twitter:description"))
                .map(this::summarize)
                .filter(description -> !description.equals(SITE_DESCRIPTION));
    }

    private String extractRawContent(String title, String html) {
        List<String> bodyBlocks = proseBlocks(html);
        List<String> replies = flightMarkdownChildren(html);
        if (!bodyBlocks.isEmpty()) {
            return buildRawContent(title, bodyBlocks.get(0), replies);
        }

        String fallbackBody = extractMetaContent(html, "description")
                .filter(description -> !SITE_DESCRIPTION.equals(description))
                .orElse("");
        return buildRawContent(title, fallbackBody, replies);
    }

    private List<String> proseBlocks(String html) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = PROSE_BLOCK_PATTERN.matcher(html);
        while (matcher.find()) {
            String text = decodeAndClean(stripTags(matcher.group(2)));
            if (!text.isBlank() && !isGapHubImagePlaceholder(text)) {
                blocks.add(text);
            }
        }
        return blocks;
    }

    private List<String> flightMarkdownChildren(String html) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = FLIGHT_MARKDOWN_PATTERN.matcher(html);
        while (matcher.find()) {
            String text = decodeAndClean(decodeJsonStringContent(matcher.group(1)));
            if (!text.isBlank() && !isGapHubImagePlaceholder(text)) {
                blocks.add(text);
            }
        }
        return blocks;
    }

    private String buildRawContent(String title, String body, List<String> replies) {
        return PreviewRawContentFormatter.format(title, body, replies, MAX_RAW_CONTENT_LENGTH);
    }

    private Optional<String> firstContentBlock(String rawContent) {
        String marker = "\n\n正文\n";
        if (rawContent == null || !rawContent.startsWith("原标题\n") || !rawContent.contains(marker)) {
            return Optional.empty();
        }
        String body = rawContent.substring(rawContent.indexOf(marker) + marker.length());
        int replyMarker = body.indexOf("\n\n回帖\n");
        if (replyMarker >= 0) {
            body = body.substring(0, replyMarker);
        }
        String description = summarize(body);
        return description.isBlank() || description.equals(SITE_DESCRIPTION) ? Optional.empty() : Optional.of(description);
    }

    private String summarize(String value) {
        String compact = cleanText(value);
        if (compact.length() <= MAX_DESCRIPTION_LENGTH) {
            return compact;
        }
        return compact.substring(0, MAX_DESCRIPTION_LENGTH - ELLIPSIS.length()).stripTrailing() + ELLIPSIS;
    }

    private Optional<String> extractMetaContent(String html, String key) {
        Matcher metaMatcher = META_TAG_PATTERN.matcher(html);
        while (metaMatcher.find()) {
            String tag = metaMatcher.group();
            boolean keyMatches = attributeValue(tag, "property")
                    .filter(key::equalsIgnoreCase)
                    .isPresent()
                    || attributeValue(tag, "name")
                    .filter(key::equalsIgnoreCase)
                    .isPresent();
            if (!keyMatches) {
                continue;
            }

            String content = attributeValue(tag, "content")
                    .map(this::decodeAndClean)
                    .orElse("");
            if (!content.isBlank()) {
                return Optional.of(content);
            }
        }
        return Optional.empty();
    }

    private Optional<String> attributeValue(String tag, String attributeName) {
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(tag);
        while (matcher.find()) {
            if (!attributeName.equalsIgnoreCase(matcher.group(1))) {
                continue;
            }
            for (int group = 3; group <= 5; group++) {
                String value = matcher.group(group);
                if (value != null) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private String cleanTitle(String value) {
        String title = decodeAndClean(value);
        String pipeSuffix = " | " + SITE_NAME;
        if (title.endsWith(pipeSuffix)) {
            title = title.substring(0, title.length() - pipeSuffix.length()).stripTrailing();
        }
        String dashSuffix = " - " + SITE_NAME;
        if (title.endsWith(dashSuffix)) {
            title = title.substring(0, title.length() - dashSuffix.length()).stripTrailing();
        }
        return title;
    }

    private String decodeAndClean(String value) {
        return cleanText(decodeHtmlEntities(value));
    }

    private String stripTags(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String withoutScripts = SCRIPT_STYLE_PATTERN.matcher(value).replaceAll(" ");
        String withBreaks = withoutScripts
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)</li>", "\n");
        return TAG_PATTERN.matcher(withBreaks)
                .replaceAll(" ");
    }

    private String decodeHtmlEntities(String value) {
        String decoded = value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&ldquo;", "\"")
                .replace("&rdquo;", "\"")
                .replace("&lsquo;", "'")
                .replace("&rsquo;", "'")
                .replace("&hellip;", "...")
                .replace("&mdash;", "-")
                .replace("&ndash;", "-");

        Matcher matcher = NUMERIC_ENTITY_PATTERN.matcher(decoded);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(decodeNumericEntity(matcher.group(1), matcher.group(0))));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String decodeNumericEntity(String value, String fallback) {
        try {
            int radix = value.startsWith("x") || value.startsWith("X") ? 16 : 10;
            String digits = radix == 16 ? value.substring(1) : value;
            int codePoint = Integer.parseInt(digits, radix);
            return new String(Character.toChars(codePoint));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private String decodeJsonStringContent(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                decoded.append(current);
                continue;
            }

            char escaped = value.charAt(++index);
            switch (escaped) {
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case '"', '\\', '/' -> decoded.append(escaped);
                case 'u' -> {
                    if (index + 4 <= value.length() - 1) {
                        String hex = value.substring(index + 1, index + 5);
                        try {
                            decoded.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        } catch (NumberFormatException exception) {
                            decoded.append("\\u").append(hex);
                            index += 4;
                        }
                    } else {
                        decoded.append("\\u");
                    }
                }
                default -> decoded.append(escaped);
            }
        }
        return decoded.toString();
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private boolean isGapHubImagePlaceholder(String text) {
        return "图片加载中...".equals(text) || "图片加载中…".equals(text);
    }

    private String responseBodySnippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = cleanText(new String(body, StandardCharsets.UTF_8));
        if (text.length() <= MAX_ERROR_BODY_LOG_CHARS) {
            return text;
        }
        return text.substring(0, MAX_ERROR_BODY_LOG_CHARS).stripTrailing() + ELLIPSIS;
    }

    private UpstreamFetchException translateIOException(IOException exception, String fallbackMessage) {
        if (isCausedBy(exception, SSLHandshakeException.class)) {
            return new UpstreamFetchException(
                    "TLS handshake with the GapHub upstream failed. Check the Java trust store or your proxy certificate.",
                    exception
            );
        }
        return new UpstreamFetchException(fallbackMessage, exception);
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> targetType) {
        Throwable current = throwable;
        while (current != null) {
            if (targetType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final String SITE_DESCRIPTION = "GapHub 是一个中文社区。在这里，你可以分享经历、交流问题，也可以安静地记录当下。";
}
