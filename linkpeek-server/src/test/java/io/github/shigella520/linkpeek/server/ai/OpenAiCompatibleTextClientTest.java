package io.github.shigella520.linkpeek.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleTextClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsChatCompletionsRequestAndParsesMessageContent() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
                {"choices":[{"message":{"content":"AI 生成标题"}}]}
                """);
        OpenAiCompatibleTextClient client = new OpenAiCompatibleTextClient(httpClient, objectMapper);

        Optional<String> title = client.generateTitle(
                provider("https://api.example.com/v1", "CHAT_COMPLETIONS", "gpt-test", "low", "sk-test"),
                prompt()
        );

        JsonNode body = objectMapper.readTree(httpClient.lastRequestBody);
        assertEquals(Optional.of("AI 生成标题"), title);
        assertEquals("/v1/chat/completions", httpClient.lastRequestUri.getPath());
        assertEquals("Bearer sk-test", httpClient.lastAuthorization);
        assertEquals("gpt-test", body.path("model").asText());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("标题格式", body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("Style Prompt\nUC 风格", body.path("messages").get(1).path("content").asText());
        assertEquals("user", body.path("messages").get(2).path("role").asText());
        assertEquals("Raw Content\n原文内容", body.path("messages").get(2).path("content").asText());
        assertEquals("low", body.path("reasoning_effort").asText());
        assertEquals(Optional.of(Duration.ofSeconds(45)), httpClient.lastRequestTimeout);
    }

    @Test
    void sendsResponsesRequestAndParsesOutputText() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
                {"output":[{"content":[{"type":"output_text","text":"Responses 标题"}]}]}
                """);
        OpenAiCompatibleTextClient client = new OpenAiCompatibleTextClient(httpClient, objectMapper);

        Optional<String> title = client.generateTitle(
                provider("https://api.example.com/v1", "RESPONSES", "gpt-test", "medium", ""),
                prompt()
        );

        JsonNode body = objectMapper.readTree(httpClient.lastRequestBody);
        assertEquals(Optional.of("Responses 标题"), title);
        assertEquals("/v1/responses", httpClient.lastRequestUri.getPath());
        assertEquals("", httpClient.lastAuthorization);
        assertEquals("gpt-test", body.path("model").asText());
        assertEquals("标题格式", body.path("instructions").asText());
        assertEquals("user", body.path("input").get(0).path("role").asText());
        assertEquals("Style Prompt\nUC 风格", body.path("input").get(0).path("content").asText());
        assertEquals("user", body.path("input").get(1).path("role").asText());
        assertEquals("Raw Content\n原文内容", body.path("input").get(1).path("content").asText());
        assertEquals("medium", body.path("reasoning").path("effort").asText());
        assertEquals(Optional.of(Duration.ofSeconds(45)), httpClient.lastRequestTimeout);
    }

    @Test
    void usesProviderRequestTimeoutWhenPresent() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(200, """
                {"choices":[{"message":{"content":"AI 生成标题"}}]}
                """);
        OpenAiCompatibleTextClient client = new OpenAiCompatibleTextClient(httpClient, objectMapper);
        AiProviderRecord provider = provider("https://api.example.com/v1", "CHAT_COMPLETIONS", "gpt-test", "", "sk-test");
        provider.setRequestTimeoutSeconds(12);

        client.generateTitle(provider, prompt());

        assertEquals(Optional.of(Duration.ofSeconds(12)), httpClient.lastRequestTimeout);
    }

    @Test
    void rejectsBaseUrlWithoutV1Path() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AiApiKind.normalizeBaseUrl("https://api.example.com/v1/completions")
        );
    }

    @Test
    void rejectsBlankApiFormat() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AiApiKind.fromValue("")
        );
    }

    @Test
    void includesResponseBodySnippetWhenProviderReturnsHttpError() {
        CapturingHttpClient httpClient = new CapturingHttpClient(503, """
                {"error":{"message":"upstream overloaded","type":"service_unavailable"}}
                """);
        OpenAiCompatibleTextClient client = new OpenAiCompatibleTextClient(httpClient, objectMapper);

        IOException exception = assertThrows(
                IOException.class,
                () -> client.generateTitle(
                        provider("https://api.example.com/v1", "CHAT_COMPLETIONS", "gpt-test", "", "sk-test"),
                        prompt()
                )
        );

        assertTrue(exception.getMessage().contains("HTTP 503"));
        assertTrue(exception.getMessage().contains("upstream overloaded"));
    }

    private AiProviderRecord provider(String baseUrl, String apiKind, String model, String effort, String apiKey) {
        AiProviderRecord provider = new AiProviderRecord();
        provider.setId(1L);
        provider.setName("test");
        provider.setEnabled(true);
        provider.setSortOrder(1);
        provider.setBaseUrl(baseUrl);
        provider.setApiKind(apiKind);
        provider.setModel(model);
        provider.setEffort(effort);
        provider.setApiKey(apiKey);
        provider.setUpdatedAt(1L);
        return provider;
    }

    private AiTitlePrompt prompt() {
        return new AiTitlePrompt("标题格式", "UC 风格", "原文内容");
    }

    private static final class CapturingHttpClient extends HttpClient {
        private final int statusCode;
        private final byte[] responseBody;
        private URI lastRequestUri;
        private Optional<Duration> lastRequestTimeout = Optional.empty();
        private String lastRequestBody = "";
        private String lastAuthorization = "";

        private CapturingHttpClient(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody.getBytes(StandardCharsets.UTF_8);
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
            lastRequestTimeout = request.timeout();
            lastRequestBody = BodyCollector.collect(request);
            lastAuthorization = request.headers().firstValue("Authorization").orElse("");
            return (HttpResponse<T>) new StubHttpResponse(request.uri(), statusCode, responseBody);
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

    private record StubHttpResponse(URI uri, int statusCode, byte[] body) implements HttpResponse<byte[]> {
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
                    "Content-Type", List.of("application/json"),
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
