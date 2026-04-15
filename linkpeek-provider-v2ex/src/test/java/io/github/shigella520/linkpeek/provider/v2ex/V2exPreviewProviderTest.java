package io.github.shigella520.linkpeek.provider.v2ex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2exPreviewProviderTest {
    private HttpServer server;
    private V2exPreviewProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        provider = new V2exPreviewProvider(
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
    void supportsCommonV2exTopicUrls() {
        assertTrue(provider.supports(URI.create("https://www.v2ex.com/t/1205886")));
        assertTrue(provider.supports(URI.create("https://v2ex.com/t/1205886#reply45")));
        assertTrue(provider.supports(URI.create("https://www.v2ex.com/amp/t/1205886?p=2")));
        assertFalse(provider.supports(URI.create("https://www.v2ex.com/go/openai")));
        assertFalse(provider.supports(URI.create("https://example.com/t/1205886")));
    }

    @Test
    void canonicalizesReplyAnchorsToTopicRoot() {
        URI canonical = provider.canonicalize(URI.create("https://www.v2ex.com/t/1205886?p=2#reply45"));

        assertEquals("https://www.v2ex.com/t/1205886", canonical.toString());
    }

    @Test
    void resolvesMetadataToGeneratedTitleCard() {
        server.createContext("/api/topics/show.json", exchange -> writeJson(exchange, """
                [{
                  "id": 1205886,
                  "title": "今日份 GPT 5.4 笑话",
                  "content": "https://i.v2ex.co/4828f6ki.png",
                  "content_rendered": "<a href=\\"/i/4828f6ki.png\\"><img src=\\"//i.v2ex.co/4828f6ki.png\\" class=\\"embedded_image\\"></a>",
                  "deleted": 0,
                  "node": {"title": "OpenAI"},
                  "member": {
                    "username": "Zhuzhuchenyan",
                    "avatar_xlarge": "https://cdn.v2ex.com/avatar/demo_xlarge.png"
                  }
                }]
                """));

        PreviewMetadata metadata = provider.resolve(URI.create("https://www.v2ex.com/t/1205886#reply45"));

        assertEquals("v2ex", metadata.providerId());
        assertEquals("https://www.v2ex.com/t/1205886", metadata.canonicalUrl());
        assertEquals("今日份 GPT 5.4 笑话", metadata.title());
        assertEquals("OpenAI - @Zhuzhuchenyan - https://i.v2ex.co/4828f6ki.png", metadata.description());
        assertTrue(metadata.thumbnailUrl().startsWith("generated://v2ex/title-card/"));
        assertEquals(1200, metadata.imageWidth());
        assertEquals(630, metadata.imageHeight());
    }

    @Test
    void resolvesTitleCardForPostsWithoutImagesToo() {
        server.createContext("/api/topics/show.json", exchange -> writeJson(exchange, """
                [{
                  "id": 1206093,
                  "title": "做个关于 macOS 26 的调查",
                  "content": "如果你在用 Planet...",
                  "content_rendered": "如果你在用 Planet...",
                  "deleted": 0,
                  "node": {"title": "Planet"},
                  "member": {
                    "username": "Livid",
                    "avatar_xlarge": "//cdn.v2ex.com/avatar/demo_xlarge.png"
                  }
                }]
                """));

        PreviewMetadata metadata = provider.resolve(URI.create("https://www.v2ex.com/t/1206093"));

        assertTrue(metadata.thumbnailUrl().startsWith("generated://v2ex/title-card/"));
        assertEquals(1200, metadata.imageWidth());
        assertEquals(630, metadata.imageHeight());
    }

    @Test
    void wrapsMissingTopicAsUpstreamFailure() {
        server.createContext("/api/topics/show.json", exchange -> writeJson(exchange, "[]"));

        assertThrows(UpstreamFetchException.class, () -> provider.resolve(URI.create("https://www.v2ex.com/t/9999999")));
    }

    @Test
    void reportsTlsHandshakeFailuresClearly() {
        V2exPreviewProvider tlsFailingProvider = new V2exPreviewProvider(
                new ThrowingHttpClient(new SSLHandshakeException("PKIX path building failed")),
                new ObjectMapper(),
                URI.create("https://www.v2ex.com"),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0"
        );

        UpstreamFetchException exception = assertThrows(
                UpstreamFetchException.class,
                () -> tlsFailingProvider.resolve(URI.create("https://www.v2ex.com/t/1205886"))
        );

        assertTrue(exception.getMessage().contains("TLS handshake"));
    }

    @Test
    void rejectsUnsupportedUrls() {
        assertThrows(UnsupportedPreviewUrlException.class, () -> provider.canonicalize(URI.create("https://example.com/t/1")));
    }

    @Test
    void downloadsGeneratedThumbnailToTargetPath() throws IOException {
        PreviewMetadata metadata = generatedMetadata(
                "https://www.v2ex.com/t/1205886",
                "今日份 GPT 5.4 笑话"
        );
        Path target = Files.createTempDirectory("linkpeek-v2ex").resolve("thumb.jpg");

        provider.downloadThumbnail(metadata, target);

        BufferedImage image = ImageIO.read(target.toFile());
        assertNotNull(image);
        assertEquals(1200, image.getWidth());
        assertEquals(630, image.getHeight());
    }

    @Test
    void generatesStableCardForSameCanonicalUrl() throws IOException {
        PreviewMetadata metadata = generatedMetadata(
                "https://www.v2ex.com/t/1205886",
                "一个很长很长的标题，用来测试标题卡片在多次生成时是否保持完全一致"
        );
        Path first = Files.createTempDirectory("linkpeek-v2ex-card").resolve("first.jpg");
        Path second = Files.createTempDirectory("linkpeek-v2ex-card").resolve("second.jpg");

        provider.downloadThumbnail(metadata, first);
        provider.downloadThumbnail(metadata, second);

        assertTrue(Arrays.equals(Files.readAllBytes(first), Files.readAllBytes(second)));
    }

    @Test
    void generatesDifferentCardsForDifferentCanonicalUrls() throws IOException {
        PreviewMetadata firstMetadata = generatedMetadata(
                "https://www.v2ex.com/t/1205886",
                "同样的标题"
        );
        PreviewMetadata secondMetadata = generatedMetadata(
                "https://www.v2ex.com/t/1206093",
                "同样的标题"
        );
        Path first = Files.createTempDirectory("linkpeek-v2ex-card").resolve("first.jpg");
        Path second = Files.createTempDirectory("linkpeek-v2ex-card").resolve("second.jpg");

        provider.downloadThumbnail(firstMetadata, first);
        provider.downloadThumbnail(secondMetadata, second);

        assertNotEquals(Arrays.hashCode(Files.readAllBytes(first)), Arrays.hashCode(Files.readAllBytes(second)));
    }

    private PreviewMetadata generatedMetadata(String canonicalUrl, String title) {
        String topicId = canonicalUrl.substring(canonicalUrl.lastIndexOf('/') + 1);
        return new PreviewMetadata(
                canonicalUrl,
                canonicalUrl,
                "v2ex",
                title,
                "OpenAI - @demo - description",
                "V2EX",
                "generated://v2ex/title-card/" + topicId,
                1200,
                630,
                ContentType.ARTICLE
        );
    }

    private static void writeJson(HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
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
}
