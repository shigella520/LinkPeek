package io.github.shigella520.linkpeek.provider.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliPreviewProviderTest {
    private HttpServer server;
    private BilibiliPreviewProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        provider = new BilibiliPreviewProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void supportsCommonBilibiliUrls() {
        assertTrue(provider.supports(URI.create("https://www.bilibili.com/video/BV1xx411c7mD")));
        assertTrue(provider.supports(URI.create("https://m.bilibili.com/video/BV1xx411c7mD?p=1")));
        assertTrue(provider.supports(URI.create("https://b23.tv/BV1xx411c7mD")));
        assertTrue(provider.supports(URI.create("https://b23.tv/5ox9FJX")));
        assertFalse(provider.supports(URI.create("https://example.com/video/BV1xx411c7mD")));
    }

    @Test
    void canonicalizesToDesktopVideoUrl() {
        URI canonical = provider.canonicalize(URI.create("https://m.bilibili.com/video/BV1xx411c7mD?p=2"));

        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", canonical.toString());
    }

    @Test
    void canonicalizesShortLinkByFollowingRedirect() {
        BilibiliPreviewProvider shortLinkProvider = new BilibiliPreviewProvider(
                new RedirectingHttpClient(URI.create("https://www.bilibili.com/video/BV1xx411c7mD")),
                new ObjectMapper(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0"
        );

        URI canonical = shortLinkProvider.canonicalize(URI.create("https://b23.tv/5ox9FJX"));

        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", canonical.toString());
    }

    @Test
    void resolvesMetadataFromBilibiliApi() {
        server.createContext("/x/web-interface/view", exchange -> writeJson(exchange, """
                {"code":0,"data":{"title":"Demo","desc":"Description","pic":"https://img.example/test.jpg","dimension":{"width":1280,"height":720}}}
                """));

        PreviewMetadata metadata = provider.resolve(URI.create("https://www.bilibili.com/video/BV1xx411c7mD"));

        assertEquals("bilibili", metadata.providerId());
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", metadata.canonicalUrl());
        assertEquals("Demo", metadata.title());
        assertEquals("https://img.example/test.jpg", metadata.thumbnailUrl());
    }

    @Test
    void resolvesMetadataFromGzipEncodedApiResponse() {
        server.createContext("/x/web-interface/view", exchange -> {
            byte[] body = gzip("""
                    {"code":0,"data":{"title":"Demo gzip","desc":"Description","pic":"https://img.example/test.jpg","dimension":{"width":1280,"height":720}}}
                    """);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });

        PreviewMetadata metadata = provider.resolve(URI.create("https://www.bilibili.com/video/BV1xx411c7mD"));

        assertEquals("Demo gzip", metadata.title());
    }

    @Test
    void wrapsUpstreamFailures() {
        server.createContext("/x/web-interface/view", exchange -> writeJson(exchange, """
                {"code":-400,"message":"bad request"}
                """));

        assertThrows(UpstreamFetchException.class, () -> provider.resolve(URI.create("https://www.bilibili.com/video/BV1xx411c7mD")));
    }

    @Test
    void reportsTlsHandshakeFailuresClearly() {
        BilibiliPreviewProvider tlsFailingProvider = new BilibiliPreviewProvider(
                new ThrowingHttpClient(new SSLHandshakeException("PKIX path building failed")),
                new ObjectMapper(),
                URI.create("https://api.bilibili.com"),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0"
        );

        UpstreamFetchException exception = assertThrows(
                UpstreamFetchException.class,
                () -> tlsFailingProvider.resolve(URI.create("https://www.bilibili.com/video/BV1xx411c7mD"))
        );

        assertTrue(exception.getMessage().contains("TLS handshake"));
    }

    @Test
    void rejectsUnsupportedUrls() {
        assertThrows(UnsupportedPreviewUrlException.class, () -> provider.canonicalize(URI.create("https://example.com/video/1")));
    }

    @Test
    void downloadsThumbnailToTargetPath() throws IOException {
        server.createContext("/thumb.jpg", exchange -> {
            byte[] body = "image-data".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });

        PreviewMetadata metadata = new PreviewMetadata(
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "bilibili",
                "Demo",
                "Desc",
                "Bilibili",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/thumb.jpg",
                1280,
                720,
                io.github.shigella520.linkpeek.core.model.ContentType.VIDEO
        );
        Path target = Files.createTempDirectory("linkpeek-bili").resolve("thumb.jpg");

        provider.downloadThumbnail(metadata, target);

        assertEquals("image-data", Files.readString(target));
    }

    private static void writeJson(HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static byte[] gzip(String payload) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }

    private static final class RedirectingHttpClient extends HttpClient {
        private final URI redirectedUri;

        private RedirectingHttpClient(URI redirectedUri) {
            this.redirectedUri = redirectedUri;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, null, new SecureRandom());
                return context;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return new StubResponse<>(request, redirectedUri, 200, null);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }
    }

    private static final class ThrowingHttpClient extends HttpClient {
        private final IOException exception;

        private ThrowingHttpClient(IOException exception) {
            this.exception = exception;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, null, new SecureRandom());
                return context;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            throw exception;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(exception);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private record StubResponse<T>(HttpRequest request, URI uri, int statusCode, T body) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (left, right) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
