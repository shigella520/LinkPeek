package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord;
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
import java.util.Map;

@Component
public class OpenAiCompatibleAudioClient implements ShareSummaryAudioProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAudioClient.class);
    private static final int MAX_BODY_LOG_CHARS = 2_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleAudioClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerType) {
        return "OPENAI_COMPATIBLE".equalsIgnoreCase(providerType) || "OPENAI_SPEECH".equalsIgnoreCase(providerType);
    }

    @Override
    public ShareSummaryAudioProvider.AudioGenerationResult generate(ShareSummaryAudioConfigRecord config, String input) throws IOException, InterruptedException {
        URI endpoint = endpointUri(config.getBaseUrl(), config.getEndpointPath());
        byte[] body = requestBody(config, input);
        Duration timeout = Duration.ofSeconds(Math.max(1, config.getRequestTimeoutSeconds()));
        log.info(
                "share_summary_audio_request_start providerType={} model={} voice={} endpoint={} timeoutMs={} requestBytes={}",
                config.getProviderType(),
                config.getModel(),
                config.getVoice(),
                endpoint,
                timeout.toMillis(),
                body.length
        );
        log.info(
                "share_summary_audio_request_body providerType={} model={} voice={} endpoint={} requestBody={}",
                config.getProviderType(),
                config.getModel(),
                config.getVoice(),
                endpoint,
                requestBodyLog(body)
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "audio/mpeg, audio/*")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (StringUtils.hasText(config.getApiKey())) {
            builder.header("Authorization", "Bearer " + config.getApiKey().strip());
        }

        long startedAt = System.nanoTime();
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() >= 400) {
            String responseBody = bodySnippet(response.body());
            log.warn(
                    "share_summary_audio_http_error model={} voice={} endpoint={} status={} durationMs={} requestId={} responseBody={}",
                    config.getModel(),
                    config.getVoice(),
                    endpoint,
                    response.statusCode(),
                    durationMs,
                    requestId(response.headers()),
                    responseBody
            );
            throw new IOException("Audio provider returned HTTP " + response.statusCode() + " body=" + responseBody);
        }
        byte[] audioBytes = response.body();
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IOException("Audio provider returned an empty response.");
        }
        if (!isAudioResponse(contentType, audioBytes)) {
            throw new IOException("Audio provider returned non-audio content type: " + contentType);
        }
        log.info(
                "share_summary_audio_request_success model={} voice={} endpoint={} status={} durationMs={} requestId={} responseBytes={}",
                config.getModel(),
                config.getVoice(),
                endpoint,
                response.statusCode(),
                durationMs,
                requestId(response.headers()),
                audioBytes.length
        );
        return new ShareSummaryAudioProvider.AudioGenerationResult(audioBytes, responseSnapshot(response.statusCode(), contentType, audioBytes.length), durationMs);
    }

    private byte[] requestBody(ShareSummaryAudioConfigRecord config, String input) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (StringUtils.hasText(config.getModel())) {
            body.put("model", config.getModel().strip());
        }
        body.put("input", input);
        body.put("voice", config.getVoice());
        body.put("speed", config.getSpeed());
        body.put("pitch", config.getPitch());
        if (StringUtils.hasText(config.getStyle())) {
            body.put("style", config.getStyle().strip());
        }
        return objectMapper.writeValueAsBytes(body);
    }

    private URI endpointUri(String baseUrl, String endpointPath) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("Audio provider base URL must not be blank.");
        }
        String path = StringUtils.hasText(endpointPath) ? endpointPath.strip() : "/v1/audio/speech";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        URI base = URI.create(stripTrailingSlash(baseUrl.strip()));
        return base.resolve(path);
    }

    private boolean isAudioResponse(String contentType, byte[] bytes) {
        if (StringUtils.hasText(contentType) && contentType.toLowerCase().startsWith("audio/")) {
            return true;
        }
        return bytes.length >= 3
                && (bytes[0] & 0xff) == 0x49
                && (bytes[1] & 0xff) == 0x44
                && (bytes[2] & 0xff) == 0x33;
    }

    private String responseSnapshot(int statusCode, String contentType, int responseBytes) throws IOException {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", statusCode);
        snapshot.put("content_type", contentType);
        snapshot.put("response_bytes", responseBytes);
        return objectMapper.writeValueAsString(snapshot);
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

    private String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
