package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageConfigRecord;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OpenAiCompatibleImageClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleImageClient.class);
    private static final int MAX_BODY_LOG_CHARS = 2_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleImageClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public ImageGenerationResult generate(ShareSummaryImageConfigRecord config, String prompt) throws IOException, InterruptedException {
        URI endpoint = endpointUri(config.getBaseUrl(), config.getEndpointPath());
        byte[] body = requestBody(config, prompt);
        Duration timeout = Duration.ofSeconds(Math.max(1, config.getRequestTimeoutSeconds()));
        log.info(
                "share_summary_image_request_start providerType={} model={} endpoint={} timeoutMs={} requestBytes={}",
                config.getProviderType(),
                config.getModel(),
                endpoint,
                timeout.toMillis(),
                body.length
        );
        log.info(
                "share_summary_image_request_body providerType={} model={} endpoint={} requestBody={}",
                config.getProviderType(),
                config.getModel(),
                endpoint,
                requestBodyLog(body)
        );
        log.info(
                "share_summary_image_request_curl providerType={} model={} endpoint={} curl={}",
                config.getProviderType(),
                config.getModel(),
                endpoint,
                curlCommand(endpoint, timeout, body, StringUtils.hasText(config.getApiKey()))
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (StringUtils.hasText(config.getApiKey())) {
            builder.header("Authorization", "Bearer " + config.getApiKey().strip());
        }

        long startedAt = System.nanoTime();
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (response.statusCode() >= 400) {
            String responseBody = bodySnippet(response.body());
            log.warn(
                    "share_summary_image_http_error model={} endpoint={} status={} durationMs={} requestId={} responseBody={}",
                    config.getModel(),
                    endpoint,
                    response.statusCode(),
                    durationMs,
                    requestId(response.headers()),
                    responseBody
            );
            throw new IOException("Image provider returned HTTP " + response.statusCode() + " body=" + responseBody);
        }

        JsonNode payload = objectMapper.readTree(response.body());
        Optional<String> base64 = firstText(payload.path("data"), "b64_json");
        Optional<String> imageUrl = firstText(payload.path("data"), "url");
        if (base64.isEmpty() && imageUrl.isEmpty()) {
            throw new IOException("Image provider response did not include data[0].b64_json or data[0].url.");
        }
        String snapshot = responseSnapshot(payload);
        log.info(
                "share_summary_image_request_success model={} endpoint={} status={} durationMs={} requestId={} responseBytes={}",
                config.getModel(),
                endpoint,
                response.statusCode(),
                durationMs,
                requestId(response.headers()),
                response.body().length
        );
        return new ImageGenerationResult(base64.orElse(null), imageUrl.orElse(null), snapshot, durationMs);
    }

    private byte[] requestBody(ShareSummaryImageConfigRecord config, String prompt) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("prompt", prompt);
        body.put("size", config.getImageSize());
        body.put("n", 1);
        if (StringUtils.hasText(config.getOutputFormat())) {
            body.put("output_format", providerOutputFormat(config.getOutputFormat()));
        }
        if (StringUtils.hasText(config.getQuality())) {
            body.put("quality", config.getQuality().strip());
        }
        body.put("moderation", "auto");
        return objectMapper.writeValueAsBytes(body);
    }

    private URI endpointUri(String baseUrl, String endpointPath) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("Image provider base URL must not be blank.");
        }
        String normalizedBase = baseUrl.strip();
        String path = StringUtils.hasText(endpointPath) ? endpointPath.strip() : "/v1/images/generations";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        URI base = URI.create(stripTrailingSlash(normalizedBase));
        return base.resolve(path);
    }

    private Optional<String> firstText(JsonNode data, String fieldName) {
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }
        String value = data.get(0).path(fieldName).asText("");
        return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
    }

    private String providerOutputFormat(String outputFormat) {
        String value = outputFormat.strip().toLowerCase();
        return "jpg".equals(value) ? "jpeg" : value;
    }

    private String responseSnapshot(JsonNode payload) throws IOException {
        JsonNode data = payload.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return bodySnippet(objectMapper.writeValueAsBytes(payload));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        JsonNode first = data.get(0);
        snapshot.put("has_b64_json", StringUtils.hasText(first.path("b64_json").asText("")));
        snapshot.put("has_url", StringUtils.hasText(first.path("url").asText("")));
        if (StringUtils.hasText(first.path("revised_prompt").asText(""))) {
            snapshot.put("revised_prompt", first.path("revised_prompt").asText(""));
        }
        return bodySnippet(objectMapper.writeValueAsBytes(snapshot));
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

    private String requestBodyLog(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, StandardCharsets.UTF_8)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .strip();
    }

    private String curlCommand(URI endpoint, Duration timeout, byte[] body, boolean hasApiKey) {
        StringBuilder command = new StringBuilder();
        command.append("curl -X POST ")
                .append(shellQuote(endpoint.toString()))
                .append(" --max-time ")
                .append(Math.max(1, timeout.toSeconds()))
                .append(" -H ")
                .append(shellQuote("Accept: application/json"))
                .append(" -H ")
                .append(shellQuote("Content-Type: application/json"));
        if (hasApiKey) {
            command.append(" -H ")
                    .append(shellQuote("Authorization: Bearer <API_KEY>"));
        }
        command.append(" --data-raw ")
                .append(shellQuote(requestBodyLog(body)));
        return command.toString();
    }

    private String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public record ImageGenerationResult(
            String base64,
            String imageUrl,
            String rawResponseSnapshot,
            long durationMs
    ) {
    }
}
