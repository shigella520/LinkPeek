package io.github.shigella520.linkpeek.provider.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.core.util.UrlNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLHandshakeException;

public class BilibiliPreviewProvider implements PreviewProvider {
    private static final Logger log = LoggerFactory.getLogger(BilibiliPreviewProvider.class);

    private static final Pattern BV_PATTERN = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final String REFERER = "https://www.bilibili.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI apiBaseUri;
    private final Duration requestTimeout;
    private final String userAgent;

    public BilibiliPreviewProvider(
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
        return "bilibili";
    }

    @Override
    public boolean supports(URI sourceUrl) {
        try {
            URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
            return extractBvid(normalized).isPresent() || isShortLink(normalized);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public URI canonicalize(URI sourceUrl) {
        URI normalized = UrlNormalizer.normalizeHttpUrl(sourceUrl);
        if (isShortLink(normalized)) {
            return canonicalize(resolveShortLink(normalized));
        }

        String bvid = extractBvid(normalized)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only Bilibili BV video URLs are supported."));
        return URI.create("https://www.bilibili.com/video/" + bvid);
    }

    @Override
    public PreviewMetadata resolve(URI sourceUrl) {
        URI canonicalUrl = canonicalize(sourceUrl);
        String bvid = extractBvid(canonicalUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("Only Bilibili BV video URLs are supported."));
        URI requestUri = apiBaseUri.resolve("/x/web-interface/view?bvid=" + URLEncoder.encode(bvid, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Referer", REFERER)
                .header("Origin", REFERER)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                log.warn(
                        "bilibili_api_failed status={} requestUri={} contentType={} contentEncoding={} bodyLength={} bodySnippet={}",
                        response.statusCode(),
                        requestUri,
                        response.headers().firstValue("Content-Type").orElse(""),
                        response.headers().firstValue("Content-Encoding").orElse(""),
                        response.body().length,
                        responseSnippet(response.body())
                );
                throw new UpstreamFetchException("Bilibili API returned HTTP " + response.statusCode());
            }

            byte[] responseBytes = decodeResponseBody(response);
            try {
                JsonNode payload = objectMapper.readTree(responseBytes);
                if (payload.path("code").asInt(-1) != 0) {
                    throw new UpstreamFetchException("Bilibili API rejected the request: " + payload.path("message").asText("unknown error"));
                }

                JsonNode data = payload.path("data");
                JsonNode dimension = data.path("dimension");
                return new PreviewMetadata(
                        UrlNormalizer.normalizeHttpUrl(sourceUrl).toString(),
                        canonicalUrl.toString(),
                        getId(),
                        data.path("title").asText(""),
                        data.path("desc").asText(""),
                        "Bilibili",
                        data.path("pic").asText(),
                        dimension.path("width").asInt(1920),
                        dimension.path("height").asInt(1080),
                        ContentType.VIDEO
                );
            } catch (IOException | RuntimeException exception) {
                log.warn(
                        "bilibili_api_parse_failed requestUri={} status={} contentType={} contentEncoding={} bodyLength={} bodySnippet={}",
                        requestUri,
                        response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(""),
                        response.headers().firstValue("Content-Encoding").orElse(""),
                        responseBytes.length,
                        responseSnippet(responseBytes),
                        exception
                );
                throw new UpstreamFetchException("Failed to read or parse the Bilibili API response.", exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while calling the Bilibili API.", exception);
        } catch (IOException exception) {
            log.warn(
                    "bilibili_api_request_failed requestUri={} timeoutMs={}",
                    requestUri,
                    requestTimeout.toMillis(),
                    exception
            );
            throw translateIOException(exception, "Failed to read or parse the Bilibili API response.");
        }
    }

    @Override
    public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        HttpRequest request = HttpRequest.newBuilder(URI.create(ensureHttps(metadata.thumbnailUrl())))
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

    private Optional<String> extractBvid(URI sourceUrl) {
        String host = sourceUrl.getHost();
        if (host == null) {
            return Optional.empty();
        }

        boolean bilibiliHost = host.endsWith("bilibili.com") || isShortLink(sourceUrl);
        if (!bilibiliHost) {
            return Optional.empty();
        }

        return firstMatch(sourceUrl.getPath())
                .or(() -> firstMatch(sourceUrl.getRawQuery()))
                .map(String::strip);
    }

    private boolean isShortLink(URI sourceUrl) {
        return "b23.tv".equals(sourceUrl.getHost());
    }

    private URI resolveShortLink(URI shortUrl) {
        HttpRequest request = HttpRequest.newBuilder(shortUrl)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .GET()
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                throw new UpstreamFetchException("Bilibili short URL returned HTTP " + response.statusCode());
            }
            return UrlNormalizer.normalizeHttpUrl(response.uri());
        } catch (IOException exception) {
            throw translateIOException(exception, "Failed to resolve the Bilibili short URL.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamFetchException("Interrupted while resolving the Bilibili short URL.", exception);
        }
    }

    private Optional<String> firstMatch(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = BV_PATTERN.matcher(value);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private String ensureHttps(String rawUrl) {
        if (rawUrl.startsWith("//")) {
            return "https:" + rawUrl;
        }
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            return rawUrl;
        }
        log.debug("Normalizing non-standard thumbnail URL {}", rawUrl);
        return "https://" + rawUrl;
    }

    private byte[] decodeResponseBody(HttpResponse<byte[]> response) throws IOException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
        byte[] body = response.body();
        if (encoding.contains("gzip")) {
            try (InputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return inputStream.readAllBytes();
            }
        }
        return body;
    }

    private UpstreamFetchException translateIOException(IOException exception, String fallbackMessage) {
        if (isCausedBy(exception, SSLHandshakeException.class)) {
            return new UpstreamFetchException(
                    "TLS handshake with the Bilibili upstream failed. Check the Java trust store or your proxy certificate.",
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

    private String responseSnippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        int length = Math.min(body.length, 240);
        String snippet = new String(body, 0, length, StandardCharsets.UTF_8)
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (body.length > length) {
            return snippet + "...";
        }
        return snippet;
    }
}
