package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MimoTtsAudioProvider implements ShareSummaryAudioProvider {
    private static final Logger log = LoggerFactory.getLogger(MimoTtsAudioProvider.class);
    private static final int MAX_BODY_LOG_CHARS = 2_000;
    private static final String SUN_WUKONG_STYLE = "请用孙悟空式的角色语气朗读，语气机灵、有气势、节奏明快，但保持内容清晰可懂。";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MimoTtsAudioProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerType) {
        return "MIMO_TTS".equalsIgnoreCase(providerType);
    }

    @Override
    public ShareSummaryAudioClient.AudioGenerationResult generate(ShareSummaryAudioConfigRecord config, String input) throws IOException, InterruptedException {
        URI endpoint = endpointUri(config.getBaseUrl(), config.getEndpointPath());
        byte[] body = requestBody(config, input);
        Duration timeout = Duration.ofSeconds(Math.max(1, config.getRequestTimeoutSeconds()));
        log.info(
                "share_summary_mimo_audio_request_start model={} voice={} endpoint={} timeoutMs={} requestBytes={}",
                config.getModel(),
                config.getVoice(),
                endpoint,
                timeout.toMillis(),
                body.length
        );
        log.info(
                "share_summary_mimo_audio_request_body model={} voice={} endpoint={} requestBody={}",
                config.getModel(),
                config.getVoice(),
                endpoint,
                requestBodyLog(body)
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (StringUtils.hasText(config.getApiKey())) {
            builder.header("api-key", config.getApiKey().strip());
        }

        long startedAt = System.nanoTime();
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String responseBody = bodySnippet(response.body());
        if (response.statusCode() >= 400) {
            log.warn(
                    "share_summary_mimo_audio_http_error model={} voice={} endpoint={} status={} durationMs={} requestId={} responseBody={}",
                    config.getModel(),
                    config.getVoice(),
                    endpoint,
                    response.statusCode(),
                    durationMs,
                    requestId(response.headers()),
                    responseBody
            );
            throw new IOException("MiMo TTS provider returned HTTP " + response.statusCode() + " body=" + responseBody);
        }
        byte[] audioBytes = decodeAudioBytes(response.body());
        log.info(
                "share_summary_mimo_audio_request_success model={} voice={} endpoint={} status={} durationMs={} requestId={} responseBytes={}",
                config.getModel(),
                config.getVoice(),
                endpoint,
                response.statusCode(),
                durationMs,
                requestId(response.headers()),
                audioBytes.length
        );
        return new ShareSummaryAudioClient.AudioGenerationResult(audioBytes, responseSnapshot(response.statusCode(), contentType, audioBytes.length), durationMs);
    }

    private byte[] requestBody(ShareSummaryAudioConfigRecord config, String input) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", StringUtils.hasText(config.getModel()) ? config.getModel().strip() : "mimo-v2.5-tts");
        body.put("messages", messages(config, input));
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("format", providerOutputFormat(config.getOutputFormat()));
        audio.put("voice", config.getVoice());
        body.put("audio", audio);
        return objectMapper.writeValueAsBytes(body);
    }

    private List<Map<String, String>> messages(ShareSummaryAudioConfigRecord config, String input) {
        String style = styleInstruction(config.getStyle());
        String assistantContent = assistantContent(config.getStyle(), input);
        if (StringUtils.hasText(style)) {
            return List.of(
                    Map.of("role", "user", "content", style),
                    Map.of("role", "assistant", "content", assistantContent)
            );
        }
        return List.of(Map.of("role", "assistant", "content", assistantContent));
    }

    private String styleInstruction(String style) {
        if (!StringUtils.hasText(style)) {
            return "";
        }
        String value = style.strip();
        if ("孙悟空".equals(value) || "SUN_WUKONG".equalsIgnoreCase(value)) {
            return SUN_WUKONG_STYLE;
        }
        return value;
    }

    private String assistantContent(String style, String input) {
        String text = input == null ? "" : input;
        if (isSunWukongStyle(style) && !text.startsWith("(孙悟空)") && !text.startsWith("（孙悟空）")) {
            return "(孙悟空)" + text;
        }
        return text;
    }

    private boolean isSunWukongStyle(String style) {
        if (!StringUtils.hasText(style)) {
            return false;
        }
        String value = style.strip();
        return "孙悟空".equals(value)
                || "SUN_WUKONG".equalsIgnoreCase(value)
                || value.contains("孙悟空");
    }

    private String providerOutputFormat(String outputFormat) {
        String value = StringUtils.hasText(outputFormat) ? outputFormat.strip().toLowerCase() : "wav";
        return "mp3".equals(value) ? "mp3" : "wav";
    }

    private byte[] decodeAudioBytes(byte[] responseBody) throws IOException {
        if (responseBody == null || responseBody.length == 0) {
            throw new IOException("MiMo TTS provider returned an empty response.");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.path("choices").path(0).path("message").path("audio").path("data");
        if (!data.isTextual() || !StringUtils.hasText(data.asText())) {
            throw new IOException("MiMo TTS provider response did not include choices[0].message.audio.data.");
        }
        try {
            return Base64.getDecoder().decode(data.asText());
        } catch (IllegalArgumentException exception) {
            throw new IOException("MiMo TTS provider returned invalid base64 audio data.", exception);
        }
    }

    private URI endpointUri(String baseUrl, String endpointPath) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("MiMo TTS base URL must not be blank.");
        }
        String path = StringUtils.hasText(endpointPath) ? endpointPath.strip() : "/v1/chat/completions";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        URI base = URI.create(stripTrailingSlash(baseUrl.strip()));
        return base.resolve(path);
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
