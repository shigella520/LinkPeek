package io.github.shigella520.linkpeek.provider.v2ex;

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
import java.net.URLEncoder;
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

public class V2exPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(V2exPreviewProvider.class);

    private static final Pattern TOPIC_PATH_PATTERN = Pattern.compile("^/(?:amp/)?t/(\\d+)(?:/.*)?$");
    private static final Pattern TOPIC_CONTENT_PATTERN = Pattern.compile("(?is)<div\\b[^>]*class=[\"'][^\"']*\\btopic_content\\b[^\"']*[\"'][^>]*>(.*?)</div>");
    private static final Pattern REPLY_CONTENT_PATTERN = Pattern.compile("(?is)<div\\b[^>]*class=[\"'][^\"']*\\breply_content\\b[^\"']*[\"'][^>]*>(.*?)</div>");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);");
    private static final String SITE_NAME = "V2EX";
    private static final String REFERER = "https://www.v2ex.com";
    private static final String TITLE_CARD_PREFIX = "generated://v2ex/title-card/";
    private static final int CARD_WIDTH = TitleCardRenderer.WIDTH;
    private static final int CARD_HEIGHT = TitleCardRenderer.HEIGHT;
    private static final TitleCardRenderer.Watermark TITLE_CARD_WATERMARK =
            TitleCardRenderer.Watermark.resource(V2exPreviewProvider.class, "title-card-watermark.png");
    private static final int MAX_DESCRIPTION_LENGTH = 280;
    private static final int MAX_RAW_CONTENT_LENGTH = 12_000;
    private static final String ELLIPSIS = "…";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI apiBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;

    public V2exPreviewProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI apiBaseUri,
            Duration requestTimeout,
            String userAgent
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiBaseUri = apiBaseUri;
        this.requestTimeout = requestTimeout;
        this.userAgent = userAgent;
    }

    @Override
    public String getId() {
        return "v2ex";
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
            throw new UnsupportedPreviewUrlException("Only V2EX topic URLs are supported.");
        }

        String topicId = extractTopicId(normalized)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only V2EX topic URLs are supported."));
        return URI.create("https://www.v2ex.com/t/" + topicId);
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        URI normalizedSourceUrl = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        URI canonicalUrl = canonicalize(sourceUrl);
        String topicId = extractTopicId(canonicalUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only V2EX topic URLs are supported."));
        URI requestUri = apiBaseUri.resolve("/api/topics/show.json?id=" + URLEncoder.encode(topicId, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Referer", REFERER)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new UpstreamFetchException("V2EX API returned HTTP " + response.statusCode());
            }

            JsonNode payload = objectMapper.readTree(response.body());
            JsonNode topic = firstTopic(payload)
                    .orElseThrow(() -> new UpstreamFetchException("V2EX topic was not found."));
            if (topic.path("deleted").asInt(0) != 0) {
                throw new UpstreamFetchException("V2EX topic is no longer available.");
            }

            JsonNode node = topic.path("node");
            JsonNode member = topic.path("member");
            String topicTitle = clean(topic.path("title").asText(""));
            String topicContent = summarize(topic.path("content").asText(""), MAX_RAW_CONTENT_LENGTH);
            String rawContent = buildRawContent(topicTitle, topicContent, List.of());

            return new PreviewMetadata(
                    normalizedSourceUrl.toString(),
                    canonicalUrl.toString(),
                    getId(),
                    topicTitle.isBlank() ? SITE_NAME : topicTitle,
                    buildDescription(
                            clean(node.path("title").asText("")),
                            clean(member.path("username").asText("")),
                            topicContent
                    ),
                    SITE_NAME,
                    buildGeneratedThumbnailUrl(topicId),
                    CARD_WIDTH,
                    CARD_HEIGHT,
                    ContentType.ARTICLE,
                    rawContent
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while calling the V2EX API.", exception);
        } catch (IOException exception) {
            log.warn(
                    "v2ex_api_request_failed requestUri={} timeoutMs={}",
                    requestUri,
                    requestTimeout.toMillis(),
                    exception
            );
            throw translateIOException(exception, "Failed to read or parse the V2EX API response.");
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
        Optional<String> topicId = extractTopicId(normalizedSourceUrl)
                .or(() -> extractTopicId(URI.create(metadata.canonicalUrl())));
        if (topicId.isEmpty()) {
            return metadata;
        }
        Optional<String> rawContent = fetchTopicPageRawContent(
                normalizedSourceUrl,
                topicId.get(),
                metadata.title(),
                fallbackTopicContent(metadata.rawContent())
        );
        return rawContent
                .map(value -> withRawContent(metadata, value))
                .orElse(metadata);
    }

    @Override
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        if (isGeneratedTitleCard(metadata.thumbnailUrl())) {
            TitleCardRenderer.render(metadata.title(), SITE_NAME, metadata.canonicalUrl(), SITE_NAME, TITLE_CARD_WATERMARK, targetPath);
            return targetPath;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(metadata.thumbnailUrl()))
                .timeout(requestTimeout)
                .header("Referer", REFERER)
                .header("User-Agent", userAgent)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new UpstreamFetchException("Thumbnail request failed with HTTP " + response.statusCode());
            }
            Files.write(targetPath, response.body());
            return targetPath;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while downloading the thumbnail.", exception);
        }
    }

    private boolean isSupportedHost(URI sourceUrl) {
        String host = sourceUrl.getHost();
        return "v2ex.com".equals(host) || "www.v2ex.com".equals(host);
    }

    private Optional<String> extractTopicId(URI sourceUrl) {
        Matcher matcher = TOPIC_PATH_PATTERN.matcher(sourceUrl.getPath());
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<JsonNode> firstTopic(JsonNode payload) {
        if (payload == null || !payload.isArray() || payload.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(payload.get(0));
    }

    private Optional<String> fetchTopicPageRawContent(
            URI sourceUrl,
            String topicId,
            String topicTitle,
            String fallbackTopicContent
    ) {
        URI pageUri = topicPageUri(sourceUrl, topicId);
        HttpRequest request = HttpRequest.newBuilder(pageUri)
                .timeout(requestTimeout)
                .header("Referer", REFERER)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                log.debug("v2ex_topic_page_raw_content_http_error requestUri={} status={}", pageUri, response.statusCode());
                return Optional.empty();
            }

            String html = new String(response.body(), StandardCharsets.UTF_8);
            String topicContent = firstTopicContent(html).filter(value -> !value.isBlank()).orElse(fallbackTopicContent);
            String rawContent = buildRawContent(topicTitle, topicContent, replyContents(html));
            return rawContent.isBlank() ? Optional.empty() : Optional.of(rawContent);
        } catch (IOException exception) {
            log.debug("v2ex_topic_page_raw_content_failed requestUri={} timeoutMs={}", pageUri, requestTimeout.toMillis(), exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.debug("v2ex_topic_page_raw_content_interrupted requestUri={}", pageUri, exception);
            return Optional.empty();
        }
    }

    private URI topicPageUri(URI sourceUrl, String topicId) {
        String query = sourceUrl.getRawQuery();
        String path = "/t/" + topicId + (query == null || query.isBlank() ? "" : "?" + query);
        return apiBaseUri.resolve(path);
    }

    private Optional<String> firstTopicContent(String html) {
        Matcher matcher = TOPIC_CONTENT_PATTERN.matcher(html);
        if (matcher.find()) {
            return Optional.of(cleanHtmlText(matcher.group(1)));
        }
        return Optional.empty();
    }

    private List<String> replyContents(String html) {
        List<String> replies = new ArrayList<>();
        Matcher matcher = REPLY_CONTENT_PATTERN.matcher(html);
        while (matcher.find()) {
            String reply = cleanHtmlText(matcher.group(1));
            if (!reply.isBlank()) {
                replies.add(reply);
            }
        }
        return replies;
    }

    private String buildRawContent(String topicTitle, String topicContent, List<String> replies) {
        return PreviewRawContentFormatter.format(topicTitle, topicContent, replies, MAX_RAW_CONTENT_LENGTH);
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

    private String buildGeneratedThumbnailUrl(String topicId) {
        return TITLE_CARD_PREFIX + topicId;
    }

    private boolean isGeneratedTitleCard(String thumbnailUrl) {
        return thumbnailUrl != null && thumbnailUrl.startsWith(TITLE_CARD_PREFIX);
    }

    private String buildDescription(String nodeTitle, String username, String content) {
        List<String> parts = new ArrayList<>();
        if (!nodeTitle.isBlank()) {
            parts.add(nodeTitle);
        }
        if (!username.isBlank()) {
            parts.add("@" + username);
        }
        if (!content.isBlank()) {
            parts.add(summarize(content));
        }

        String description = String.join(" - ", parts);
        return description.isBlank() ? SITE_NAME : description;
    }

    private String summarize(String value) {
        return summarize(value, MAX_DESCRIPTION_LENGTH);
    }

    private String summarize(String value, int maxLength) {
        String compact = clean(value);
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength - 1).stripTrailing() + ELLIPSIS;
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String cleanHtmlText(String value) {
        if (value == null) {
            return "";
        }
        String withoutScripts = SCRIPT_STYLE_PATTERN.matcher(value).replaceAll(" ");
        String withBreaks = withoutScripts
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)</li>", "\n");
        return clean(decodeHtmlEntities(TAG_PATTERN.matcher(withBreaks).replaceAll(" ")));
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

    private UpstreamFetchException translateIOException(IOException exception, String fallbackMessage) {
        if (isCausedBy(exception, SSLHandshakeException.class)) {
            return new UpstreamFetchException(
                    "TLS handshake with the V2EX upstream failed. Check the Java trust store or your proxy certificate.",
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

}
