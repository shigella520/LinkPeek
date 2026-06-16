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

class OpenAiCompatibleAudioClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsBlankModelAndSendsWangwangitExtensionFields() throws Exception {
        byte[] audioBytes = testMp3Bytes();
        CapturingHttpClient httpClient = new CapturingHttpClient(200, "audio/mpeg", audioBytes);
        OpenAiCompatibleAudioClient client = new OpenAiCompatibleAudioClient(httpClient, objectMapper);

        ShareSummaryAudioProvider.AudioGenerationResult result = client.generate(config(""), "测试文本");

        JsonNode body = objectMapper.readTree(httpClient.lastRequestBody);
        assertArrayEquals(audioBytes, result.audioBytes());
        assertEquals("/v1/audio/speech", httpClient.lastRequestUri.getPath());
        assertEquals("", httpClient.lastAuthorization);
        assertTrue(body.path("model").isMissingNode());
        assertEquals("测试文本", body.path("input").asText());
        assertEquals("zh-CN-YunhaoNeural", body.path("voice").asText());
        assertEquals(1.2, body.path("speed").asDouble());
        assertEquals(0, body.path("pitch").asInt());
        assertEquals("newscast", body.path("style").asText());
    }

    @Test
    void sendsConfiguredModelAndAuthorization() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, "audio/mpeg", testMp3Bytes());
        OpenAiCompatibleAudioClient client = new OpenAiCompatibleAudioClient(httpClient, objectMapper);
        ShareSummaryAudioConfigRecord config = config("tts-1");
        config.setApiKey("sk-audio");

        client.generate(config, "测试文本");

        JsonNode body = objectMapper.readTree(httpClient.lastRequestBody);
        assertEquals("tts-1", body.path("model").asText());
        assertEquals("Bearer sk-audio", httpClient.lastAuthorization);
    }

    @Test
    void failsWhenProviderReturnsNonAudio() {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
        OpenAiCompatibleAudioClient client = new OpenAiCompatibleAudioClient(httpClient, objectMapper);

        IOException exception = assertThrows(IOException.class, () -> client.generate(config(""), "测试文本"));

        assertTrue(exception.getMessage().contains("non-audio"));
    }

    @Test
    void includesProviderErrorBody() {
        CapturingHttpClient httpClient = new CapturingHttpClient(500, "application/json", "{\"error\":\"failed\"}".getBytes(StandardCharsets.UTF_8));
        OpenAiCompatibleAudioClient client = new OpenAiCompatibleAudioClient(httpClient, objectMapper);

        IOException exception = assertThrows(IOException.class, () -> client.generate(config(""), "测试文本"));

        assertTrue(exception.getMessage().contains("HTTP 500"));
        assertTrue(exception.getMessage().contains("failed"));
    }

    private ShareSummaryAudioConfigRecord config(String model) {
        ShareSummaryAudioConfigRecord config = new ShareSummaryAudioConfigRecord();
        config.setEnabled(true);
        config.setAutoGenerate(true);
        config.setProviderType("OPENAI_COMPATIBLE");
        config.setBaseUrl("https://tts.example.com");
        config.setEndpointPath("/v1/audio/speech");
        config.setApiKey("");
        config.setModel(model);
        config.setVoice("zh-CN-YunhaoNeural");
        config.setSpeed(1.2);
        config.setPitch(0);
        config.setStyle("newscast");
        config.setOutputFormat("mp3");
        config.setRequestTimeoutSeconds(7);
        return config;
    }

    private static byte[] testMp3Bytes() {
        return new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 0};
    }

    private static final class CapturingHttpClient extends HttpClient {
        private final int statusCode;
        private final String contentType;
        private final byte[] responseBody;
        private URI lastRequestUri;
        private String lastRequestBody = "";
        private String lastAuthorization = "";

        private CapturingHttpClient(int statusCode, String contentType, byte[] responseBody) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.responseBody = responseBody;
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
            lastAuthorization = request.headers().firstValue("Authorization").orElse("");
            return (HttpResponse<T>) new StubHttpResponse(request.uri(), statusCode, contentType, responseBody);
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
