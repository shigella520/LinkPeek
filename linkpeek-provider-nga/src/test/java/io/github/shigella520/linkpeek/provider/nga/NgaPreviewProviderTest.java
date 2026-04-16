package io.github.shigella520.linkpeek.provider.nga;

import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NgaPreviewProviderTest {
    private StubHttpClient httpClient;
    private NgaPreviewProvider provider;

    @BeforeEach
    void setUp() {
        httpClient = new StubHttpClient();
        provider = new NgaPreviewProvider(
                httpClient,
                URI.create("https://bbs.nga.cn"),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0",
                null,
                null
        );
    }

    @Test
    void supportsCommonNgaThreadUrls() {
        assertTrue(provider.supports(URI.create("https://bbs.nga.cn/read.php?tid=46581611")));
        assertTrue(provider.supports(URI.create("https://nga.178.com/read.php?tid=46581611&page=2#l1")));
        assertFalse(provider.supports(URI.create("https://bbs.nga.cn/thread.php?fid=123")));
        assertFalse(provider.supports(URI.create("https://example.com/read.php?tid=46581611")));
    }

    @Test
    void canonicalizesNgaThreadUrl() {
        URI canonical = provider.canonicalize(URI.create("https://nga.178.com/read.php?tid=46581611&page=2#l1"));

        assertEquals("https://bbs.nga.cn/read.php?tid=46581611", canonical.toString());
    }

    @Test
    void resolvesMetadataFromNgaHtmlPage() {
        httpClient.responseBody = """
                <html>
                <head>
                    <meta charset="gbk">
                    <title>[水区] 这是一个测试贴 - NGA玩家社区</title>
                    <meta name="description" content="这是 meta 描述">
                </head>
                <body>
                    <div id="m_posts_c">
                        <table>
                            <tr><td class="posterinfo"><a href="/nuke.php?uid=123">测试用户</a></td></tr>
                            <tr><td class="postcontent ubbcode">
                                第一段内容<br>
                                第二段内容，顺便带一点 HTML &amp; 实体。
                            </td></tr>
                        </table>
                    </div>
                </body>
                </html>
                """.getBytes(Charset.forName("GB18030"));

        PreviewMetadata metadata = provider.resolve(URI.create("https://bbs.nga.cn/read.php?tid=46581611&page=3"));

        assertEquals("nga", metadata.providerId());
        assertEquals("https://bbs.nga.cn/read.php?tid=46581611", metadata.canonicalUrl());
        assertEquals("[水区] 这是一个测试贴", metadata.title());
        assertTrue(metadata.description().contains("@测试用户"));
        assertTrue(metadata.description().contains("第一段内容 第二段内容"));
        assertTrue(metadata.thumbnailUrl().startsWith("generated://nga/thread-card/46581611"));
        assertEquals(URI.create("https://bbs.nga.cn/read.php?tid=46581611"), httpClient.lastRequestUri);
    }

    @Test
    void fallsBackToMetaDescriptionWhenPostBodyMissing() {
        httpClient.responseBody = """
                <html>
                <head>
                    <title>NGA 测试帖子 - NGA玩家社区</title>
                    <meta name="description" content="帖子摘要内容">
                </head>
                <body></body>
                </html>
                """.getBytes(Charset.forName("GB18030"));

        PreviewMetadata metadata = provider.resolve(URI.create("https://bbs.nga.cn/read.php?tid=46581611"));

        assertEquals("NGA 测试帖子", metadata.title());
        assertEquals("帖子摘要内容", metadata.description());
    }

    @Test
    void reportsTlsHandshakeFailuresClearly() {
        NgaPreviewProvider tlsFailingProvider = new NgaPreviewProvider(
                new ThrowingHttpClient(new SSLHandshakeException("PKIX path building failed")),
                URI.create("https://bbs.nga.cn"),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0",
                null,
                null
        );

        UpstreamFetchException exception = assertThrows(
                UpstreamFetchException.class,
                () -> tlsFailingProvider.resolve(URI.create("https://bbs.nga.cn/read.php?tid=46581611"))
        );

        assertTrue(exception.getMessage().contains("TLS handshake"));
    }

    @Test
    void rejectsUnsupportedUrls() {
        assertThrows(UnsupportedPreviewUrlException.class, () -> provider.canonicalize(URI.create("https://example.com/read.php?tid=1")));
    }

    @Test
    void usesPassportCookieWhenConfigured() {
        NgaPreviewProvider authenticatedProvider = new NgaPreviewProvider(
                httpClient,
                URI.create("https://bbs.nga.cn"),
                Duration.ofSeconds(3),
                "LinkPeek-Test/1.0",
                "123456",
                "abcdef"
        );
        httpClient.responseBody = """
                <html><head><title>NGA 测试帖子 - NGA玩家社区</title></head><body></body></html>
                """.getBytes(Charset.forName("GB18030"));

        authenticatedProvider.resolve(URI.create("https://bbs.nga.cn/read.php?tid=46581611"));

        assertEquals("ngaPassportUid=123456; ngaPassportCid=abcdef;", httpClient.lastCookieHeader);
    }

    @Test
    void downloadsGeneratedThumbnailToTargetPath() throws IOException {
        PreviewMetadata metadata = new PreviewMetadata(
                "https://bbs.nga.cn/read.php?tid=46581611",
                "https://bbs.nga.cn/read.php?tid=46581611",
                "nga",
                "这是一个测试贴",
                "@测试用户 - 测试摘要",
                "NGA",
                "generated://nga/thread-card/46581611",
                1200,
                630,
                ContentType.ARTICLE
        );
        Path target = Files.createTempDirectory("linkpeek-nga").resolve("thumb.jpg");

        provider.downloadThumbnail(metadata, target);

        BufferedImage image = ImageIO.read(target.toFile());
        assertNotNull(image);
        assertEquals(1200, image.getWidth());
        assertEquals(630, image.getHeight());
    }

    private static final class StubHttpClient extends HttpClient {
        private byte[] responseBody = new byte[0];
        private URI lastRequestUri;
        private String lastCookieHeader;

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
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            lastRequestUri = request.uri();
            lastCookieHeader = request.headers().firstValue("Cookie").orElse(null);
            return (HttpResponse<T>) new StubHttpResponse(request.uri(), responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private record StubHttpResponse(URI uri, byte[] body) implements HttpResponse<byte[]> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(uri).build();
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("Content-Type", List.of("text/html; charset=gbk")), (left, right) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
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
