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
                "{}_request_start providerId={} apiKind={} model={} baseUrl={} timeoutMs={} requestBytes={}",
                operation,
                provider.getId(),
                apiKind,
                provider.getModel(),
                endpointUri,
                requestTimeout.toMillis(),
                body.length
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
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn(
                    "{}_http_error providerId={} apiKind={} model={} baseUrl={} status={} durationMs={} requestId={}",
                    operation,
                    provider.getId(),
                    apiKind,
                    provider.getModel(),
                    endpointUri,
                    response.statusCode(),
                    durationMs,
                    requestId(response.headers())
            );
            throw new IOException("AI provider returned HTTP " + response.statusCode());
        }

        log.info(
                "{}_request_success providerId={} apiKind={} model={} baseUrl={} status={} durationMs={} requestId={} responseBytes={}",
                operation,
                provider.getId(),
                apiKind,
                provider.getModel(),
                endpointUri,
                response.statusCode(),
                durationMs,
                requestId(response.headers()),
                response.body().length
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
        if (!completedOrUnspecified(payload.path("status"))
                || payload.hasNonNull("incomplete_details") || payload.hasNonNull("error")) {
            return Optional.empty();
        }
        JsonNode output = payload.path("output");
        String finalText = null;
        if (output.isArray()) {
            for (JsonNode item : output) {
                // Some compatible gateways omit type/role, but content must still be output_text.
                if (!"message".equals(item.path("type").asText("message"))
                        || !"assistant".equals(item.path("role").asText("assistant"))) {
                    continue;
                }
                if (!completedOrUnspecified(item.path("status"))) {
                    return Optional.empty();
                }
                JsonNode content = item.path("content");
                if (!content.isArray()) {
                    continue;
                }
                List<String> parts = new ArrayList<>();
                for (JsonNode part : content) {
                    if ("refusal".equals(part.path("type").asText())) {
                        return Optional.empty();
                    }
                    if ("output_text".equals(part.path("type").asText())
                            && part.path("text").isTextual() && StringUtils.hasText(part.path("text").asText())) {
                        parts.add(part.path("text").asText());
                    }
                }
                if (!parts.isEmpty()) {
                    finalText = String.join("\n", parts);
                }
            }
            // Never use a top-level convenience field to override typed reasoning/refusal output.
            return Optional.ofNullable(finalText);
        }
        JsonNode text = payload.path("output_text");
        return text.isTextual() && StringUtils.hasText(text.asText())
                ? Optional.of(text.asText()) : Optional.empty();
    }

    private boolean completedOrUnspecified(JsonNode status) {
        return status.isMissingNode() || status.isNull() || "completed".equals(status.asText());
    }

    private Optional<String> extractChatText(JsonNode payload) {
        JsonNode choices = payload.path("choices");
        if (!choices.isArray() || choices.isEmpty() || payload.hasNonNull("error")) {
            return Optional.empty();
        }
        JsonNode choice = choices.get(0);
        String finish = choice.path("finish_reason").asText("");
        JsonNode message = choice.path("message");
        if ((!finish.isEmpty() && !"stop".equals(finish)) || message.hasNonNull("refusal")
                || !"assistant".equals(message.path("role").asText("assistant"))) {
            return Optional.empty();
        }
        JsonNode content = message.path("content");
        return content.isTextual() && StringUtils.hasText(content.asText())
                ? Optional.of(content.asText()) : Optional.empty();
    }

    private String requestId(HttpHeaders headers) {
        return headers.firstValue("x-request-id")
                .or(() -> headers.firstValue("request-id"))
                .or(() -> headers.firstValue("openai-request-id"))
                .or(() -> headers.firstValue("cf-ray"))
                .orElse("n/a");
    }

    public record TitleResult(Optional<String> title, long durationMs) {
    }

    public record TextResult(Optional<String> text, long durationMs) {
    }
}
