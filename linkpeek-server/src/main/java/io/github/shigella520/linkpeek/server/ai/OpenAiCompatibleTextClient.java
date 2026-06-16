package io.github.shigella520.linkpeek.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OpenAiCompatibleTextClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleTextClient.class);

    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 45;
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS);
    private static final int MAX_BODY_LOG_CHARS = 2_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleTextClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Optional<String> generateTitle(AiProviderRecord provider, AiTitlePrompt prompt) throws IOException, InterruptedException {
        return generateTitleResult(provider, prompt).title();
    }

    public TitleResult generateTitleResult(AiProviderRecord provider, AiTitlePrompt prompt) throws IOException, InterruptedException {
        AiTextPrompt textPrompt = new AiTextPrompt(
                prompt.titleFormatPrompt(),
                prompt.styleMessage(),
                prompt.rawContentMessage()
        );
        TextResult result = generateTextResult(provider, textPrompt, "ai_title", null);
        return new TitleResult(result.text(), result.durationMs());
    }

    public TextResult generateTextResult(AiProviderRecord provider, AiTextPrompt prompt) throws IOException, InterruptedException {
        return generateTextResult(provider, prompt, "ai_text", null);
    }

    public TextResult generateTextResult(AiProviderRecord provider, AiTextPrompt prompt, Duration timeout) throws IOException, InterruptedException {
        return generateTextResult(provider, prompt, "ai_text", timeout);
    }

    private TextResult generateTextResult(AiProviderRecord provider, AiTextPrompt prompt, String operation, Duration timeout) throws IOException, InterruptedException {
        AiApiKind apiKind = AiApiKind.fromValue(provider.getApiKind());
        URI endpointUri = apiKind.endpointUri(provider.getBaseUrl());
        byte[] body = switch (apiKind) {
            case RESPONSES -> responsesBody(provider, prompt);
            case CHAT_COMPLETIONS -> chatCompletionsBody(provider, prompt);
        };
        Duration requestTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? requestTimeout(provider)
                : timeout;
        log.info(
                "{}_request_start providerId={} apiKind={} model={} baseUrl={} timeoutMs={} requestBytes={} requestBody={}",
                operation,
                provider.getId(),
                apiKind,
                provider.getModel(),
                endpointUri,
                requestTimeout.toMillis(),
                body.length,
                bodySnippet(body)
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpointUri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (StringUtils.hasText(provider.getApiKey())) {
            builder.header("Authorization", "Bearer " + provider.getApiKey().strip());
        }

        long startedAt = System.nanoTime();
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (response.statusCode() >= 400) {
            String responseBody = bodySnippet(response.body());
            log.warn(
                    "{}_http_error providerId={} apiKind={} model={} baseUrl={} status={} durationMs={} requestId={} responseBody={}",
                    operation,
                    provider.getId(),
                    apiKind,
                    provider.getModel(),
                    endpointUri,
                    response.statusCode(),
                    durationMs,
                    requestId(response.headers()),
                    responseBody
            );
            throw new IOException("AI provider returned HTTP " + response.statusCode() + " body=" + responseBody);
        }

        String responseBody = bodySnippet(response.body());
        log.info(
                "{}_request_success providerId={} apiKind={} model={} baseUrl={} status={} durationMs={} requestId={} responseBytes={} responseBody={}",
                operation,
                provider.getId(),
                apiKind,
                provider.getModel(),
                endpointUri,
                response.statusCode(),
                durationMs,
                requestId(response.headers()),
                response.body().length,
                responseBody
        );
        JsonNode payload = objectMapper.readTree(response.body());
        Optional<String> title = switch (apiKind) {
            case RESPONSES -> extractResponsesText(payload);
            case CHAT_COMPLETIONS -> extractChatText(payload);
        };
        return new TextResult(title, durationMs);
    }

    private byte[] responsesBody(AiProviderRecord provider, AiTextPrompt prompt) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        if (prompt.hasInstructions()) {
            body.put("instructions", prompt.instructions());
        }
        List<Map<String, String>> input = new ArrayList<>();
        input.add(message("user", prompt.prompt()));
        if (prompt.hasContent()) {
            input.add(message("user", prompt.content()));
        }
        body.put("input", input);
        if (StringUtils.hasText(provider.getEffort())) {
            body.put("reasoning", Map.of("effort", provider.getEffort().strip()));
        }
        return objectMapper.writeValueAsBytes(body);
    }

    private byte[] chatCompletionsBody(AiProviderRecord provider, AiTextPrompt prompt) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        List<Map<String, String>> messages = new ArrayList<>();
        if (prompt.hasInstructions()) {
            messages.add(message("system", prompt.instructions()));
        }
        messages.add(message("user", prompt.prompt()));
        if (prompt.hasContent()) {
            messages.add(message("user", prompt.content()));
        }
        body.put("messages", messages);
        if (StringUtils.hasText(provider.getEffort())) {
            body.put("reasoning_effort", provider.getEffort().strip());
        }
        return objectMapper.writeValueAsBytes(body);
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private Duration requestTimeout(AiProviderRecord provider) {
        int timeoutSeconds = provider.getRequestTimeoutSeconds();
        return timeoutSeconds > 0 ? Duration.ofSeconds(timeoutSeconds) : DEFAULT_REQUEST_TIMEOUT;
    }

    private Optional<String> extractResponsesText(JsonNode payload) {
        String outputText = payload.path("output_text").asText("");
        if (StringUtils.hasText(outputText)) {
            return Optional.of(outputText);
        }

        JsonNode output = payload.path("output");
        if (!output.isArray()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            JsonNode content = item.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode contentItem : content) {
                String value = contentItem.path("text").asText("");
                if (StringUtils.hasText(value)) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(value);
                }
            }
        }
        return text.length() == 0 ? Optional.empty() : Optional.of(text.toString());
    }

    private Optional<String> extractChatText(JsonNode payload) {
        JsonNode choices = payload.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return Optional.empty();
        }
        String content = choices.get(0).path("message").path("content").asText("");
        return StringUtils.hasText(content) ? Optional.of(content) : Optional.empty();
    }

    private String requestId(HttpHeaders headers) {
        return headers.firstValue("x-request-id")
                .or(() -> headers.firstValue("request-id"))
                .or(() -> headers.firstValue("openai-request-id"))
                .or(() -> headers.firstValue("cf-ray"))
                .orElse("n/a");
    }

    private String bodySnippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        if (text.length() <= MAX_BODY_LOG_CHARS) {
            return text;
        }
        return text.substring(0, MAX_BODY_LOG_CHARS).stripTrailing() + "...";
    }

    public record TitleResult(Optional<String> title, long durationMs) {
    }

    public record TextResult(Optional<String> text, long durationMs) {
    }
}
