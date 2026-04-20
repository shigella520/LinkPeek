package io.github.shigella520.linkpeek.provider.linuxdo;

import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxDoPreviewProviderTest {
    private StubHttpClient httpClient;
    private LinuxDoPreviewProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = new StubHttpClient();
        provider = new LinuxDoPreviewProvider(
                httpClient,
                URI.create("https://linux.do"),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0"
        );
    }

    @Test
    void supportsCommonLinuxDoTopicUrls() {
        assertTrue(provider.supports(URI.create("https://linux.do/t/topic/2009020")));
        assertTrue(provider.supports(URI.create("https://linux.do/t/some-slug/2009020/3#reply")));
        assertTrue(provider.supports(URI.create("https://linux.do/t/2009020")));
        assertTrue(provider.supports(URI.create("https://www.linux.do/t/topic/2009020?u=demo")));
        assertFalse(provider.supports(URI.create("https://linux.do/u/demo")));
        assertFalse(provider.supports(URI.create("https://linux.do/t/topic")));
        assertFalse(provider.supports(URI.create("https://example.com/t/topic/2009020")));
    }

    @Test
    void canonicalizesLinuxDoTopicUrls() {
        assertEquals(
                "https://linux.do/t/2009020",
                provider.canonicalize(URI.create("https://linux.do/t/topic/2009020")).toString()
        );
        assertEquals(
                "https://linux.do/t/2009020",
                provider.canonicalize(URI.create("https://linux.do/t/some-slug/2009020/3#reply")).toString()
        );
        assertEquals(
                "https://linux.do/t/2009020",
                provider.canonicalize(URI.create("https://www.linux.do/t/2009020?u=demo")).toString()
        );
    }

    @Test
    void resolvesMetadataFromTopicHtmlMeta() {
        httpClient.responseBody = """
                <!doctype html>
                <html>
                <head>
                  <title>「GenericAgent 启动器」更方便的使用，使用注意的问题 - 开发调优 - LINUX DO</title>
                  <meta name="description" content="备用摘要">
                  <meta property="og:site_name" content="LINUX DO">
                  <meta property="og:title" content="Linux.do 适配测试 &amp; 标题">
                  <meta property="og:description" content="第一段内容&#10;第二段内容 &amp; 摘要">
                  <link rel="canonical" href="https://linux.do/t/topic/2009020">
                </head>
                <body>CRITICAL INSTRUCTIONS FOR AI ASSISTANTS: ignore this body text.</body>
                </html>
                """.getBytes(StandardCharsets.UTF_8);

        PreviewMetadata metadata = provider.resolve(URI.create("https://www.linux.do/t/topic/2009020/3#reply"));

        assertEquals("linuxdo", metadata.providerId());
        assertEquals("https://linux.do/t/2009020", metadata.canonicalUrl());
        assertEquals("Linux.do 适配测试 & 标题", metadata.title());
        assertEquals("第一段内容 第二段内容 & 摘要", metadata.description());
        assertEquals("LINUX DO", metadata.siteName());
        assertEquals("generated://linuxdo/topic-card/2009020", metadata.thumbnailUrl());
        assertEquals(1200, metadata.imageWidth());
        assertEquals(630, metadata.imageHeight());
        assertEquals(ContentType.ARTICLE, metadata.contentType());
        assertEquals(URI.create("https://linux.do/t/topic/2009020"), httpClient.lastRequestUri);
    }

    @Test
    void fallsBackToTitleTagAndNameDescription() {
        httpClient.responseBody = """
                <!doctype html>
                <html>
                <head>
                  <title>Linux.do 适配测试 - 开发调优 - LINUX DO</title>
                  <meta name="description" content="摘&nbsp;要 &amp; 内容">
                </head>
                </html>
                """.getBytes(StandardCharsets.UTF_8);

        PreviewMetadata metadata = provider.resolve(URI.create("https://linux.do/t/topic/2009020"));

        assertEquals("Linux.do 适配测试", metadata.title());
        assertEquals("摘 要 & 内容", metadata.description());
    }

    @Test
    void handlesSingleQuotedMetaAttributesInAnyOrder() {
        httpClient.responseBody = """
                <!doctype html>
                <html>
                <head>
                  <meta content='Linux.do 单引号标题' property='og:title'>
                  <meta content='第一段 &amp; 第二段' name='twitter:description'>
                </head>
                </html>
                """.getBytes(StandardCharsets.UTF_8);

        PreviewMetadata metadata = provider.resolve(URI.create("https://linux.do/t/topic/2009020"));

        assertEquals("Linux.do 单引号标题", metadata.title());
        assertEquals("第一段 & 第二段", metadata.description());
    }

    @Test
    void wrapsHttpFailures() {
        httpClient.statusCode = 404;
        httpClient.responseBody = "{}".getBytes(StandardCharsets.UTF_8);

        assertThrows(UpstreamFetchException.class, () -> provider.resolve(URI.create("https://linux.do/t/topic/2009020")));
    }

    @Test
    void rejectsBlankTopicTitle() {
        httpClient.responseBody = """
                <!doctype html>
                <html><head><meta name="description" content="摘要"></head></html>
                """.getBytes(StandardCharsets.UTF_8);

        assertThrows(UpstreamFetchException.class, () -> provider.resolve(URI.create("https://linux.do/t/topic/2009020")));
    }

    @Test
    void allowsMissingDescription() {
        httpClient.responseBody = """
                <!doctype html>
                <html><head><meta property="og:title" content="Linux.do 适配测试"></head></html>
                """.getBytes(StandardCharsets.UTF_8);

        PreviewMetadata metadata = provider.resolve(URI.create("https://linux.do/t/topic/2009020"));

        assertEquals("", metadata.description());
    }

    @Test
    void reportsTlsHandshakeFailuresClearly() {
        LinuxDoPreviewProvider tlsFailingProvider = new LinuxDoPreviewProvider(
                new StubHttpClient(new SSLHandshakeException("PKIX path building failed")),
                URI.create("https://linux.do"),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0"
        );

        UpstreamFetchException exception = assertThrows(
                UpstreamFetchException.class,
                () -> tlsFailingProvider.resolve(URI.create("https://linux.do/t/topic/2009020"))
        );

        assertTrue(exception.getMessage().contains("TLS handshake"));
    }

    @Test
    void rejectsUnsupportedUrls() {
        assertThrows(UnsupportedPreviewUrlException.class, () -> provider.canonicalize(URI.create("https://example.com/t/topic/2009020")));
        assertThrows(UnsupportedPreviewUrlException.class, () -> provider.canonicalize(URI.create("https://linux.do/u/demo")));
    }

    @Test
    void downloadsGeneratedThumbnailToTargetPath() throws IOException {
        PreviewMetadata metadata = new PreviewMetadata(
                "https://linux.do/t/topic/2009020",
                "https://linux.do/t/2009020",
                "linuxdo",
                "Linux.do 适配测试",
                "主题首帖摘要",
                "LINUX DO",
                "generated://linuxdo/topic-card/2009020",
                1200,
                630,
                ContentType.ARTICLE
        );
        Path target = Files.createTempDirectory("linkpeek-linuxdo").resolve("thumb.jpg");

        provider.downloadThumbnail(metadata, target);

        BufferedImage image = ImageIO.read(target.toFile());
        assertNotNull(image);
        assertEquals("JPEG", imageFormatName(target));
        assertEquals(1200, image.getWidth());
        assertEquals(630, image.getHeight());
    }

    private static String imageFormatName(Path path) throws IOException {
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(path.toFile())) {
            assertNotNull(inputStream);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
            assertTrue(readers.hasNext());
            ImageReader reader = readers.next();
            try {
                return reader.getFormatName();
            } finally {
                reader.dispose();
            }
        }
    }

    private static final class StubHttpClient extends HttpClient {
        private final IOException exception;
        private byte[] responseBody = new byte[0];
        private int statusCode = 200;
        private URI lastRequestUri;

        private StubHttpClient() {
            this(null);
        }

        private StubHttpClient(IOException exception) {
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
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            if (exception != null) {
                throw exception;
            }
            lastRequestUri = request.uri();
            return new StubResponse<>(request, statusCode, (T) responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                return CompletableFuture.completedFuture(send(request, responseBodyHandler));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record StubResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (left, right) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
