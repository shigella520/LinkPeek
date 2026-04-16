package io.github.shigella520.linkpeek.provider.nga;

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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NgaPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(NgaPreviewProvider.class);

    private static final Pattern TID_QUERY_PATTERN = Pattern.compile("(^|&)tid=(\\d+)($|&)");
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_OG_TITLE_PATTERN = Pattern.compile("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"'][^>]*>");

    private static final String CANONICAL_HOST = "bbs.nga.cn";
    private static final String SITE_NAME = "NGA";
    private static final String TITLE_CARD_PREFIX = "generated://nga/thread-card/";
    private static final int CARD_WIDTH = TitleCardRenderer.WIDTH;
    private static final int CARD_HEIGHT = TitleCardRenderer.HEIGHT;

    private final HttpClient httpClient;
    private final URI pageBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;
    private final String ngaPassportUid;
    private final String ngaPassportCid;

    public NgaPreviewProvider(
            HttpClient httpClient,
            URI pageBaseUri,
            Duration requestTimeout,
            String userAgent,
            String ngaPassportUid,
            String ngaPassportCid
    ) {
        this.httpClient = httpClient;
        this.pageBaseUri = pageBaseUri;
        this.requestTimeout = requestTimeout;
        this.userAgent = userAgent;
        this.ngaPassportUid = trimToNull(ngaPassportUid);
        this.ngaPassportCid = trimToNull(ngaPassportCid);
    }

    @Override
    public String getId() {
        return "nga";
    }

    @Override
    public boolean supports(URI sourceUrl) {
        try {
            URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
            return isSupportedHost(normalized) && isReadPath(normalized) && extractTid(normalized).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public URI canonicalize(URI sourceUrl) {
        URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        if (!isSupportedHost(normalized) || !isReadPath(normalized)) {
            throw new UnsupportedPreviewUrlException("Only NGA thread read.php URLs are supported.");
        }

        String tid = extractTid(normalized)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only NGA thread read.php URLs are supported."));
        return URI.create("https://" + CANONICAL_HOST + "/read.php?tid=" + tid);
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        URI normalizedSourceUrl = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        URI canonicalUrl = canonicalize(sourceUrl);
        String tid = extractTid(canonicalUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only NGA thread read.php URLs are supported."));
        URI requestUri = pageBaseUri.resolve("/read.php?tid=" + tid);

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", pageBaseUri.toString())
                .header("Cookie", buildCookieHeader())
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new UpstreamFetchException("NGA page returned HTTP " + response.statusCode());
            }

            String title = extractTitle(decodeHtml(response.body()));
            if (title.isBlank()) {
                throw new UpstreamFetchException("Failed to parse NGA thread metadata from the page.");
            }

            return new PreviewMetadata(
                    normalizedSourceUrl.toString(),
                    canonicalUrl.toString(),
                    getId(),
                    title,
                    SITE_NAME,
                    SITE_NAME,
                    TITLE_CARD_PREFIX + tid,
                    CARD_WIDTH,
                    CARD_HEIGHT,
                    ContentType.ARTICLE
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while fetching the NGA page.", exception);
        } catch (IOException exception) {
            log.warn(
                    "nga_page_request_failed requestUri={} timeoutMs={}",
                    requestUri,
                    requestTimeout.toMillis(),
                    exception
            );
            throw translateIOException(exception, "Failed to fetch or parse the NGA page.");
        }
    }

    @Override
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        TitleCardRenderer.render(metadata.title(), SITE_NAME, metadata.canonicalUrl(), SITE_NAME, targetPath);
        return targetPath;
    }

    private boolean isSupportedHost(URI sourceUrl) {
        String host = sourceUrl.getHost();
        return "bbs.nga.cn".equals(host) || "nga.178.com".equals(host) || "ngabbs.com".equals(host);
    }

    private boolean isReadPath(URI sourceUrl) {
        return "/read.php".equals(sourceUrl.getPath());
    }

    private Optional<String> extractTid(URI sourceUrl) {
        String query = sourceUrl.getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TID_QUERY_PATTERN.matcher(query);
        if (matcher.find()) {
            return Optional.of(matcher.group(2));
        }
        return Optional.empty();
    }

    private String extractTitle(String html) {
        return firstGroup(META_OG_TITLE_PATTERN, html)
                .or(() -> firstGroup(TITLE_TAG_PATTERN, html))
                .map(this::cleanTitle)
                .orElse("");
    }

    private Optional<String> firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private String cleanTitle(String rawTitle) {
        String title = cleanText(rawTitle)
                .replace("NGA玩家社区", "")
                .replace("NGA 论坛", "")
                .replace("-  ", "")
                .strip();
        if (title.endsWith("-")) {
            title = title.substring(0, title.length() - 1).stripTrailing();
        }
        return title;
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String decodeHtml(byte[] body) {
        String gbkHtml = new String(body, Charset.forName("GB18030"));
        if (gbkHtml.contains("charset=utf-8") || gbkHtml.contains("charset=UTF-8")) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return gbkHtml;
    }

    private String buildCookieHeader() {
        if (ngaPassportUid != null && ngaPassportCid != null) {
            return "ngaPassportUid=" + ngaPassportUid + "; ngaPassportCid=" + ngaPassportCid + ";";
        }
        return "guestJs=" + Instant.now().getEpochSecond();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UpstreamFetchException translateIOException(IOException exception, String fallbackMessage) {
        if (isCausedBy(exception, SSLHandshakeException.class)) {
            return new UpstreamFetchException(
                    "TLS handshake with the NGA upstream failed. Check the Java trust store or your proxy certificate.",
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
