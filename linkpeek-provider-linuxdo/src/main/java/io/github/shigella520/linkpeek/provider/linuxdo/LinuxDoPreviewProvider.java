package io.github.shigella520.linkpeek.provider.linuxdo;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinuxDoPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(LinuxDoPreviewProvider.class);

    private static final Pattern TOPIC_PATH_PATTERN = Pattern.compile("^/t/(?:[^/]+/)?(\\d+)(?:/\\d+)?/?$");
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_TAG_PATTERN = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);");

    private static final String CANONICAL_HOST = "linux.do";
    private static final String SITE_NAME = "LINUX DO";
    private static final String TITLE_CARD_PREFIX = "generated://linuxdo/topic-card/";
    private static final int CARD_WIDTH = TitleCardRenderer.WIDTH;
    private static final int CARD_HEIGHT = TitleCardRenderer.HEIGHT;
    private static final int MAX_DESCRIPTION_LENGTH = 280;
    private static final String ELLIPSIS = "…";

    private final HttpClient httpClient;
    private final URI pageBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;

    public LinuxDoPreviewProvider(
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
        URI requestUri = pageBaseUri.resolve("/t/topic/" + topicId);

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
                throw new UpstreamFetchException("Linux.do topic page returned HTTP " + response.statusCode());
            }

            String html = new String(response.body(), StandardCharsets.UTF_8);
            String title = extractTitle(html);
            if (title.isBlank()) {
                throw new UpstreamFetchException("Failed to parse Linux.do topic title from the page.");
            }

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
                    ContentType.ARTICLE
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
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        TitleCardRenderer.render(metadata.title(), SITE_NAME, metadata.canonicalUrl(), SITE_NAME, targetPath);
        return targetPath;
    }

    private boolean isSupportedHost(URI sourceUrl) {
        String host = sourceUrl.getHost();
        return CANONICAL_HOST.equals(host) || "www.linux.do".equals(host);
    }

    private Optional<String> extractTopicId(URI sourceUrl) {
        Matcher matcher = TOPIC_PATH_PATTERN.matcher(sourceUrl.getPath());
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
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

    private String summarize(String value) {
        String compact = cleanText(value);
        if (compact.length() <= MAX_DESCRIPTION_LENGTH) {
            return compact;
        }
        return compact.substring(0, MAX_DESCRIPTION_LENGTH - 1).stripTrailing() + ELLIPSIS;
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
}
