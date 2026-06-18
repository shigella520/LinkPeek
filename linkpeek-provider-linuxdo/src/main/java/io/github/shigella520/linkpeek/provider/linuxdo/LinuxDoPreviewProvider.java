package io.github.shigella520.linkpeek.provider.linuxdo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinuxDoPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(LinuxDoPreviewProvider.class);

    private static final Pattern TOPIC_PATH_PATTERN = Pattern.compile("^/t/(?:([^/]+)/)?(\\d+)(?:/\\d+)?/?$");
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_TAG_PATTERN = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))");
    private static final Pattern COOKED_BLOCK_PATTERN = Pattern.compile("(?is)<div\\b[^>]*class=[\"'][^\"']*\\bcooked\\b[^\"']*[\"'][^>]*>(.*?)</div>");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);");

    private static final String CANONICAL_HOST = "linux.do";
    private static final String SITE_NAME = "LINUX DO";
    private static final String TITLE_CARD_PREFIX = "generated://linuxdo/topic-card/";
    private static final int CARD_WIDTH = TitleCardRenderer.WIDTH;
    private static final int CARD_HEIGHT = TitleCardRenderer.HEIGHT;
    private static final TitleCardRenderer.Watermark TITLE_CARD_WATERMARK =
            TitleCardRenderer.Watermark.resource(LinuxDoPreviewProvider.class, "title-card-watermark.svg");
    private static final int MAX_DESCRIPTION_LENGTH = 280;
    private static final int MAX_RAW_CONTENT_LENGTH = 12_000;
    private static final int MAX_ERROR_BODY_LOG_CHARS = 1_000;
    private static final String ELLIPSIS = "…";

    private final HttpClient httpClient;
    private final URI pageBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;
    private final Supplier<String> cookieHeaderSupplier;
    private final ObjectMapper objectMapper;

    public LinuxDoPreviewProvider(
            HttpClient httpClient,
            URI pageBaseUri,
            Duration requestTimeout,
            String userAgent
    ) {
        this(httpClient, pageBaseUri, requestTimeout, userAgent, (String) null);
    }

    public LinuxDoPreviewProvider(
            HttpClient httpClient,
            URI pageBaseUri,
            Duration requestTimeout,
            String userAgent,
            String cookieHeader
    ) {
        this(httpClient, pageBaseUri, requestTimeout, userAgent, () -> cookieHeader);
    }

    public LinuxDoPreviewProvider(
            HttpClient httpClient,
            URI pageBaseUri,
            Duration requestTimeout,
            String userAgent,
            Supplier<String> cookieHeaderSupplier
    ) {
        this(httpClient, pageBaseUri, requestTimeout, userAgent, cookieHeaderSupplier, new ObjectMapper());
    }

    public LinuxDoPreviewProvider(
            HttpClient httpClient,
            URI pageBaseUri,
            Duration requestTimeout,
            String userAgent,
            Supplier<String> cookieHeaderSupplier,
            ObjectMapper objectMapper
    ) {
        this.httpClient = httpClient;
        this.pageBaseUri = pageBaseUri;
        this.requestTimeout = requestTimeout;
        this.userAgent = userAgent;
        this.cookieHeaderSupplier = cookieHeaderSupplier == null ? () -> null : cookieHeaderSupplier;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public String getId() {
        return "linuxdo";
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
            throw new UnsupportedPreviewUrlException("Only Linux.do topic URLs are supported.");
        }

        String topicId = extractTopicId(normalized)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only Linux.do topic URLs are supported."));
        return URI.create("https://" + CANONICAL_HOST + "/t/" + topicId);
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        URI normalizedSourceUrl = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        URI canonicalUrl = canonicalize(sourceUrl);
        String topicId = extractTopicId(canonicalUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only Linux.do topic URLs are supported."));
        URI requestUri = topicPageUri(normalizedSourceUrl, topicId);
        String cookieHeader = trimToNull(cookieHeaderSupplier.get());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Referer", pageBaseUri.toString())
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (cookieHeader != null) {
            requestBuilder.header("Cookie", cookieHeader);
        }
        HttpRequest request = requestBuilder.GET().build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                log.warn(
                        "linuxdo_topic_page_http_error requestUri={} status={} cookieConfigured={} responseBody={}",
                        requestUri,
                        response.statusCode(),
                        cookieHeader != null,
                        responseBodySnippet(response.body())
                );
                throw new UpstreamFetchException(upstreamHttpErrorMessage(response.statusCode(), cookieHeader));
            }

            String html = new String(response.body(), StandardCharsets.UTF_8);
            String title = extractTitle(html);
            if (title.isBlank()) {
                throw new UpstreamFetchException("Failed to parse Linux.do topic title from the page.");
            }
            String rawContent = extractRawContent(title, html);

            return new PreviewMetadata(
                    normalizedSourceUrl.toString(),
                    canonicalUrl.toString(),
                    getId(),
                    title,
                    extractDescription(html),
                    SITE_NAME,
                    TITLE_CARD_PREFIX + topicId,
                    CARD_WIDTH,
                    CARD_HEIGHT,
                    ContentType.ARTICLE,
                    rawContent
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while fetching the Linux.do topic page.", exception);
        } catch (IOException exception) {
            log.warn(
                    "linuxdo_topic_page_request_failed requestUri={} timeoutMs={}",
                    requestUri,
                    requestTimeout.toMillis(),
                    exception
            );
            throw translateIOException(exception, "Failed to fetch or parse the Linux.do topic page.");
        }
    }

    @Override
    public PreviewMetadata enrichForAiTitle(PreviewMetadata metadata, URI sourceUrl) {
        if (metadata == null) {
            return null;
        }
        URI normalizedSourceUrl;
        try {
            normalizedSourceUrl = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        } catch (RuntimeException exception) {
            normalizedSourceUrl = URI.create(metadata.canonicalUrl());
        }
        String topicId = extractTopicId(normalizedSourceUrl)
                .or(() -> extractTopicId(URI.create(metadata.canonicalUrl())))
                .orElse("");
        if (topicId.isBlank()) {
            return metadata;
        }

        return fetchTopicJsonRawContent(normalizedSourceUrl, topicId, metadata.title(), fallbackTopicContent(metadata.rawContent()))
                .map(rawContent -> withRawContent(metadata, rawContent))
                .orElse(metadata);
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
        return CANONICAL_HOST.equals(host) || "www.linux.do".equals(host);
    }

    private Optional<String> extractTopicId(URI sourceUrl) {
        Matcher matcher = TOPIC_PATH_PATTERN.matcher(sourceUrl.getRawPath());
        if (matcher.matches()) {
            return Optional.of(matcher.group(2));
        }
        return Optional.empty();
    }

    private URI topicPageUri(URI sourceUrl, String topicId) {
        Matcher matcher = TOPIC_PATH_PATTERN.matcher(sourceUrl.getRawPath());
        if (!matcher.matches()) {
            throw new UnsupportedPreviewUrlException("Only Linux.do topic URLs are supported.");
        }

        String slug = matcher.group(1);
        String requestPath = slug == null ? "/t/" + topicId : "/t/" + slug + "/" + topicId;
        return pageBaseUri.resolve(requestPath);
    }

    private URI topicJsonUri(URI sourceUrl, String topicId) {
        URI pageUri = topicPageUri(sourceUrl, topicId);
        String path = pageUri.getRawPath();
        return pageBaseUri.resolve(path + ".json");
    }

    private String upstreamHttpErrorMessage(int statusCode, String cookieHeader) {
        if (statusCode == 404 && cookieHeader == null) {
            return "Linux.do topic page returned HTTP 404. The topic may require a logged-in session; configure LinuxDo cookies in Provider configuration if it is private.";
        }
        return "Linux.do topic page returned HTTP " + statusCode;
    }

    private String extractTitle(String html) {
        String ogTitle = extractMetaContent(html, "og:title")
                .or(() -> extractMetaContent(html, "twitter:title"))
                .orElse("");
        if (!ogTitle.isBlank()) {
            return ogTitle;
        }

        Matcher matcher = TITLE_TAG_PATTERN.matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return cleanTitleTag(matcher.group(1));
    }

    private String extractDescription(String html) {
        return extractMetaContent(html, "og:description")
                .or(() -> extractMetaContent(html, "description"))
                .or(() -> extractMetaContent(html, "twitter:description"))
                .map(this::summarize)
                .orElse("");
    }

    private String extractRawContent(String title, String html) {
        List<String> cookedBlocks = cookedBlocks(html);
        if (!cookedBlocks.isEmpty()) {
            return buildRawContent(title, cookedBlocks.get(0), cookedBlocks.subList(1, cookedBlocks.size()));
        }
        String fallbackContent = extractMetaContent(html, "description").orElse("");
        return buildRawContent(title, fallbackContent, List.of());
    }

    private List<String> cookedBlocks(String html) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = COOKED_BLOCK_PATTERN.matcher(html);
        while (matcher.find()) {
            String text = decodeAndClean(stripTags(matcher.group(1)));
            if (!text.isBlank()) {
                blocks.add(text);
            }
        }
        return blocks;
    }

    private String buildRawContent(String title, String body, List<String> replies) {
        return PreviewRawContentFormatter.format(title, body, replies, MAX_RAW_CONTENT_LENGTH);
    }

    private Optional<String> fetchTopicJsonRawContent(
            URI sourceUrl,
            String topicId,
            String fallbackTitle,
            String fallbackBody
    ) {
        URI requestUri = topicJsonUri(sourceUrl, topicId);
        String cookieHeader = trimToNull(cookieHeaderSupplier.get());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Referer", pageBaseUri.toString())
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (cookieHeader != null) {
            requestBuilder.header("Cookie", cookieHeader);
        }

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                log.debug("linuxdo_topic_json_raw_content_http_error requestUri={} status={}", requestUri, response.statusCode());
                return Optional.empty();
            }

            JsonNode payload = objectMapper.readTree(response.body());
            String title = cleanText(payload.path("title").asText(fallbackTitle));
            if (title.isBlank()) {
                title = fallbackTitle;
            }

            List<String> posts = jsonPostContents(payload);
            String body = posts.isEmpty() ? fallbackBody : posts.get(0);
            List<String> replies = posts.size() <= 1 ? List.of() : posts.subList(1, posts.size());
            String rawContent = buildRawContent(title, body, replies);
            return rawContent.isBlank() ? Optional.empty() : Optional.of(rawContent);
        } catch (IOException exception) {
            log.debug("linuxdo_topic_json_raw_content_failed requestUri={} timeoutMs={}", requestUri, requestTimeout.toMillis(), exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("linuxdo_topic_json_raw_content_interrupted requestUri={}", requestUri, exception);
            return Optional.empty();
        }
    }

    private List<String> jsonPostContents(JsonNode payload) {
        JsonNode posts = payload.path("post_stream").path("posts");
        if (!posts.isArray()) {
            return List.of();
        }

        List<String> contents = new ArrayList<>();
        for (JsonNode post : posts) {
            String text = decodeAndClean(stripTags(post.path("cooked").asText("")));
            if (!text.isBlank()) {
                contents.add(text);
            }
        }
        return contents;
    }

    private PreviewMetadata withRawContent(PreviewMetadata metadata, String rawContent) {
        return new PreviewMetadata(
                metadata.sourceUrl(),
                metadata.canonicalUrl(),
                metadata.providerId(),
                metadata.title(),
                metadata.description(),
                metadata.siteName(),
                metadata.thumbnailUrl(),
                metadata.imageWidth(),
                metadata.imageHeight(),
                metadata.contentType(),
                rawContent
        );
    }

    private String fallbackTopicContent(String rawContent) {
        String marker = "\n\n正文\n";
        if (rawContent != null && rawContent.startsWith("原标题\n") && rawContent.contains(marker)) {
            return rawContent.substring(rawContent.indexOf(marker) + marker.length()).strip();
        }
        return rawContent == null ? "" : rawContent.strip();
    }

    private String summarize(String value) {
        return summarize(value, MAX_DESCRIPTION_LENGTH);
    }

    private String summarize(String value, int maxLength) {
        String compact = cleanText(value);
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength - 1).stripTrailing() + ELLIPSIS;
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

    private String cleanTitleTag(String value) {
        String title = decodeAndClean(value);
        String siteSuffix = " - " + SITE_NAME;
        if (title.endsWith(siteSuffix)) {
            title = title.substring(0, title.length() - siteSuffix.length()).stripTrailing();
        }

        int categoryDelimiter = title.lastIndexOf(" - ");
        if (categoryDelimiter > 0) {
            title = title.substring(0, categoryDelimiter).stripTrailing();
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
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&lsquo;", "‘")
                .replace("&rsquo;", "’")
                .replace("&hellip;", "…")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–");

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
                    "TLS handshake with the Linux.do upstream failed. Check the Java trust store or your proxy certificate.",
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
