package io.github.shigella520.linkpeek.provider.v2ex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.media.TitleCardRenderer;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
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
    private static final String SITE_NAME = "V2EX";
    private static final String REFERER = "https://www.v2ex.com";
    private static final String TITLE_CARD_PREFIX = "generated://v2ex/title-card/";
    private static final int CARD_WIDTH = TitleCardRenderer.WIDTH;
    private static final int CARD_HEIGHT = TitleCardRenderer.HEIGHT;
    private static final int MAX_DESCRIPTION_LENGTH = 280;
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

            return new PreviewMetadata(
                    normalizedSourceUrl.toString(),
                    canonicalUrl.toString(),
                    getId(),
                    topicTitle.isBlank() ? SITE_NAME : topicTitle,
                    buildDescription(
                            clean(node.path("title").asText("")),
                            clean(member.path("username").asText("")),
                            clean(topic.path("content").asText(""))
                    ),
                    SITE_NAME,
                    buildGeneratedThumbnailUrl(topicId),
                    CARD_WIDTH,
                    CARD_HEIGHT,
                    ContentType.ARTICLE
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
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        if (isGeneratedTitleCard(metadata.thumbnailUrl())) {
            TitleCardRenderer.render(metadata.title(), SITE_NAME, metadata.canonicalUrl(), SITE_NAME, targetPath);
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
        String compact = clean(value);
        if (compact.length() <= MAX_DESCRIPTION_LENGTH) {
            return compact;
        }
        return compact.substring(0, MAX_DESCRIPTION_LENGTH - 1).stripTrailing() + ELLIPSIS;
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
