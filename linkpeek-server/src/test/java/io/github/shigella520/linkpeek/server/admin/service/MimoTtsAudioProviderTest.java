package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MimoTtsAudioProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsChatCompletionsRequestAndDecodesBase64Audio() throws Exception {
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        CapturingHttpClient httpClient = new CapturingHttpClient(200, "application/json", """
                {"choices":[{"message":{"audio":{"data":"%s"}}}]}
                """.formatted(Base64.getEncoder().encodeToString(audioBytes)).getBytes(StandardCharsets.UTF_8));
        MimoTtsAudioProvider provider = new MimoTtsAudioProvider(httpClient, objectMapper);

        ShareSummaryAudioProvider.AudioGenerationResult result = provider.generate(config(), "报告正文");

        JsonNode body = objectMapper.readTree(httpClient.lastRequestBody);
        assertArrayEquals(audioBytes, result.audioBytes());
        assertEquals("/v1/chat/completions", httpClient.lastRequestUri.getPath());
        assertEquals("sk-mimo", httpClient.lastApiKey);
        assertEquals("mimo-v2.5-tts", body.path("model").asText());
        assertEquals(1, body.path("messages").size());
        assertEquals("assistant", body.path("messages").path(0).path("role").asText());
        assertEquals("(孙悟空 活泼 凌厉 兴奋)报告正文", body.path("messages").path(0).path("content").asText());
        assertEquals("wav", body.path("audio").path("format").asText());
        assertEquals("苏打", body.path("audio").path("voice").asText());
        assertTrue(body.path("audio").path("optimize_text_preview").isMissingNode());
    }

    @Test
    void sendsLegacyStyleInstructionAsAudioTag() throws Exception {
        byte[] audioBytes = new byte[]{4, 3, 2, 1};
        CapturingHttpClient httpClient = new CapturingHttpClient(200, "application/json", """
                {"choices":[{"message":{"audio":{"data":"%s"}}}]}
                """.formatted(Base64.getEncoder().encodeToString(audioBytes)).getBytes(StandardCharsets.UTF_8));
        MimoTtsAudioProvider provider = new MimoTtsAudioProvider(httpClient, objectMapper);
        ShareSummaryAudioConfigRecord config = config();
        config.setStyle("请用严肃、清晰、适合新闻播报的语气朗读。");

        provider.generate(config, "报告正文");

        JsonNode body = objectMapper.readTree(httpClient.lastRequestBody);
        assertEquals("mimo-v2.5-tts", body.path("model").asText());
        assertEquals(1, body.path("messages").size());
        assertEquals("assistant", body.path("messages").path(0).path("role").asText());
        assertEquals("(严肃)报告正文", body.path("messages").path(0).path("content").asText());
        assertEquals("苏打", body.path("audio").path("voice").asText());
        assertTrue(body.path("audio").path("optimize_text_preview").isMissingNode());
    }

    @Test
    void downgradesVoiceDesignModelToPresetAudioTagRequest() throws Exception {
        byte[] audioBytes = new byte[]{5, 6};
        CapturingHttpClient httpClient = new CapturingHttpClient(200, "application/json", """
                {"choices":[{"message":{"audio":{"data":"%s"}}}]}
                """.formatted(Base64.getEncoder().encodeToString(audioBytes)).getBytes(StandardCharsets.UTF_8));
        MimoTtsAudioProvider provider = new MimoTtsAudioProvider(httpClient, objectMapper);
        ShareSummaryAudioConfigRecord config = config();
        config.setModel("mimo-v2.5-tts-voicedesign");
        config.setStyle("孙悟空 活泼 凌厉 兴奋");

        provider.generate(config, "报告正文");

        JsonNode body = objectMapper.readTree(httpClient.lastRequestBody);
        assertEquals("mimo-v2.5-tts", body.path("model").asText());
        assertEquals(1, body.path("messages").size());
        assertEquals("(孙悟空 活泼 凌厉 兴奋)报告正文", body.path("messages").path(0).path("content").asText());
        assertEquals("苏打", body.path("audio").path("voice").asText());
        assertTrue(body.path("audio").path("optimize_text_preview").isMissingNode());
    }

    @Test
    void failsWhenAudioDataIsMissing() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, "application/json", "{\"choices\":[{}]}".getBytes(StandardCharsets.UTF_8));
        MimoTtsAudioProvider provider = new MimoTtsAudioProvider(httpClient, objectMapper);

        IOException exception = assertThrows(IOException.class, () -> provider.generate(config(), "报告正文"));

        assertTrue(exception.getMessage().contains("audio.data"));
    }

    @Test
    void failsWhenRequestExceedsTotalTimeout() {
        CapturingHttpClient httpClient = CapturingHttpClient.neverCompletes();
        MimoTtsAudioProvider provider = new MimoTtsAudioProvider(httpClient, objectMapper);
        ShareSummaryAudioConfigRecord config = config();
        config.setRequestTimeoutSeconds(1);

        IOException exception = assertThrows(IOException.class, () -> provider.generate(config, "报告正文"));

        assertTrue(exception.getMessage().contains("timed out"));
        assertEquals("/v1/chat/completions", httpClient.lastRequestUri.getPath());
    }

    private ShareSummaryAudioConfigRecord config() {
        ShareSummaryAudioConfigRecord config = new ShareSummaryAudioConfigRecord();
        config.setEnabled(true);
        config.setAutoGenerate(true);
        config.setProviderType("MIMO_TTS");
        config.setBaseUrl("https://api.xiaomimimo.com");
        config.setEndpointPath("/v1/chat/completions");
        config.setApiKey("sk-mimo");
        config.setModel("mimo-v2.5-tts");
        config.setVoice("苏打");
        config.setSpeed(1.2);
        config.setPitch(0);
        config.setStyle("孙悟空");
        config.setOutputFormat("wav");
        config.setRequestTimeoutSeconds(7);
        return config;
    }

    private static final class CapturingHttpClient extends HttpClient {
        private final int statusCode;
        private final String contentType;
        private final byte[] responseBody;
        private final boolean neverCompletes;
        private URI lastRequestUri;
        private String lastRequestBody = "";
        private String lastApiKey = "";

        private CapturingHttpClient(int statusCode, String contentType, byte[] responseBody) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.responseBody = responseBody;
            this.neverCompletes = false;
        }

        private CapturingHttpClient(boolean neverCompletes) {
            this.statusCode = 200;
            this.contentType = "application/json";
            this.responseBody = new byte[0];
            this.neverCompletes = neverCompletes;
        }

        private static CapturingHttpClient neverCompletes() {
            return new CapturingHttpClient(true);
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
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            lastRequestUri = request.uri();
            lastRequestBody = BodyCollector.collect(request);
            lastApiKey = request.headers().firstValue("api-key").orElse("");
            return (HttpResponse<T>) new StubHttpResponse(request.uri(), statusCode, contentType, responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                lastRequestUri = request.uri();
                lastRequestBody = BodyCollector.collect(request);
                lastApiKey = request.headers().firstValue("api-key").orElse("");
            } catch (IOException exception) {
                CompletableFuture<HttpResponse<T>> failed = new CompletableFuture<>();
                failed.completeExceptionally(exception);
                return failed;
            }
            if (neverCompletes) {
                return new CompletableFuture<>();
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new StubHttpResponse(request.uri(), statusCode, contentType, responseBody);
            return CompletableFuture.completedFuture(response);
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

    private record StubHttpResponse(URI uri, int statusCode, String contentType, byte[] body) implements HttpResponse<byte[]> {
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
            return HttpHeaders.of(Map.of(
                    "Content-Type", List.of(contentType),
                    "x-request-id", List.of("req-test")
            ), (left, right) -> true);
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

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {
        private final CountDownLatch complete = new CountDownLatch(1);
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        static String collect(HttpRequest request) throws IOException {
            Optional<HttpRequest.BodyPublisher> bodyPublisher = request.bodyPublisher();
            if (bodyPublisher.isEmpty()) {
                return "";
            }

            BodyCollector collector = new BodyCollector();
            bodyPublisher.get().subscribe(collector);
            try {
                if (!collector.complete.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out while reading request body.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading request body.", exception);
            }
            if (collector.error.get() != null) {
                throw new IOException("Failed to read request body.", collector.error.get());
            }
            return collector.output.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            output.writeBytes(bytes);
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            complete.countDown();
        }

        @Override
        public void onComplete() {
            complete.countDown();
        }
    }
}
