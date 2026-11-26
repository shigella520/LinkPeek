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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    // Gateways differ in which optional fields they echo back. Only reject output that is
    // positively identified as unfinished, refused, or non-final (reasoning, tool calls).
    private static final Set<String> RESPONSES_FINAL_PART_TYPES = Set.of("output_text", "text");
    private static final Set<String> RESPONSES_UNFINISHED_STATUSES = Set.of("incomplete", "failed", "cancelled", "in_progress", "queued");
    private static final Set<String> CHAT_UNUSABLE_FINISH_REASONS = Set.of(
            "length", "max_tokens", "content_filter", "tool_calls", "function_call", "safety", "recitation", "error");

    private Optional<String> extractResponsesText(JsonNode payload) {
        if (isUnfinishedStatus(payload.path("status")) || isPresent(payload.path("error"))
                || isPresent(payload.path("incomplete_details"))) {
            return Optional.empty();
        }
        JsonNode output = payload.path("output");
        boolean typedNonFinalOutput = false;
        String finalText = null;
        if (output.isArray()) {
            for (JsonNode item : output) {
                String type = item.path("type").asText("message");
                if (!"message".equals(type)) {
                    typedNonFinalOutput = true;
                    continue;
                }
                if (!"assistant".equals(item.path("role").asText("assistant"))) {
                    continue;
                }
                if (isUnfinishedStatus(item.path("status"))) {
                    return Optional.empty();
                }
                JsonNode content = item.path("content");
                List<String> parts = new ArrayList<>();
                if (content.isTextual() && StringUtils.hasText(content.asText())) {
                    parts.add(content.asText());
                } else if (content.isArray()) {
                    for (JsonNode part : content) {
                        String partType = part.path("type").asText("text");
                        if ("refusal".equals(partType)) {
                            return Optional.empty();
                        }
                        if (RESPONSES_FINAL_PART_TYPES.contains(partType)
                                && part.path("text").isTextual() && StringUtils.hasText(part.path("text").asText())) {
                            parts.add(part.path("text").asText());
                        } else if (!RESPONSES_FINAL_PART_TYPES.contains(partType)) {
                            typedNonFinalOutput = true;
                        }
                    }
                }
                if (!parts.isEmpty()) {
                    finalText = String.join("\n", parts);
                }
            }
        }
        if (finalText != null) {
            return Optional.of(finalText);
        }
        // The top-level convenience field may concatenate reasoning on some gateways, so only
        // trust it when the typed output does not contain anything other than final messages.
        JsonNode text = payload.path("output_text");
        return !typedNonFinalOutput && text.isTextual() && StringUtils.hasText(text.asText())
                ? Optional.of(text.asText()) : Optional.empty();
    }

    private boolean isUnfinishedStatus(JsonNode status) {
        return status.isTextual() && RESPONSES_UNFINISHED_STATUSES.contains(status.asText().toLowerCase(Locale.ROOT));
    }

    private boolean isPresent(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return StringUtils.hasText(node.asText());
        }
        return !node.isContainerNode() || !node.isEmpty();
    }

    private Optional<String> extractChatText(JsonNode payload) {
        JsonNode choices = payload.path("choices");
        if (!choices.isArray() || choices.isEmpty() || isPresent(payload.path("error"))) {
            return Optional.empty();
        }
        JsonNode choice = choices.get(0);
        String finish = choice.path("finish_reason").asText("").toLowerCase(Locale.ROOT);
        JsonNode message = choice.path("message");
        if (CHAT_UNUSABLE_FINISH_REASONS.contains(finish) || isPresent(message.path("refusal"))
                || !"assistant".equals(message.path("role").asText("assistant"))) {
            return Optional.empty();
        }
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return StringUtils.hasText(content.asText()) ? Optional.of(content.asText()) : Optional.empty();
        }
        if (!content.isArray()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode part : content) {
            if (RESPONSES_FINAL_PART_TYPES.contains(part.path("type").asText("text"))
                    && part.path("text").isTextual() && StringUtils.hasText(part.path("text").asText())) {
                parts.add(part.path("text").asText());
            }
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", parts));
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
