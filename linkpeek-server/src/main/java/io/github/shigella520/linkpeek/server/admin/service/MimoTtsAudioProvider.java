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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class MimoTtsAudioProvider implements ShareSummaryAudioProvider {
    private static final Logger log = LoggerFactory.getLogger(MimoTtsAudioProvider.class);
    private static final int MAX_BODY_LOG_CHARS = 2_000;
    private static final String PRESET_TTS_MODEL = "mimo-v2.5-tts";
    private static final String VOICE_DESIGN_TTS_MODEL = "mimo-v2.5-tts-voicedesign";
    private static final String SUN_WUKONG_TAG = "孙悟空 活泼 凌厉 兴奋";

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
        String model = effectiveModel(config);
        String voice = effectiveVoice(config);
        byte[] body = requestBody(config, input);
        Duration timeout = Duration.ofSeconds(Math.max(1, config.getRequestTimeoutSeconds()));
        log.info(
                "share_summary_mimo_audio_request_start model={} voice={} endpoint={} timeoutMs={} requestBytes={}",
                model,
                voice,
                endpoint,
                timeout.toMillis(),
                body.length
        );
        log.info(
                "share_summary_mimo_audio_request_body model={} voice={} endpoint={} requestBody={}",
                model,
                voice,
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
        HttpRequest request = builder.build();

        long startedAt = System.nanoTime();
        HttpResponse<byte[]> response = sendWithTotalTimeout(request, timeout, model, voice, endpoint, startedAt);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String responseBody = bodySnippet(response.body());
        if (response.statusCode() >= 400) {
            log.warn(
                    "share_summary_mimo_audio_http_error model={} voice={} endpoint={} status={} durationMs={} requestId={} responseBody={}",
                    model,
                    voice,
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
                model,
                voice,
                endpoint,
                response.statusCode(),
                durationMs,
                requestId(response.headers()),
                audioBytes.length
        );
        return new ShareSummaryAudioClient.AudioGenerationResult(audioBytes, responseSnapshot(response.statusCode(), contentType, audioBytes.length), durationMs);
    }

    private byte[] requestBody(ShareSummaryAudioConfigRecord config, String input) throws IOException {
        String model = effectiveModel(config);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages(config, input));
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("format", providerOutputFormat(config.getOutputFormat()));
        audio.put("voice", effectiveVoice(config));
        body.put("audio", audio);
        return objectMapper.writeValueAsBytes(body);
    }

    private String effectiveModel(ShareSummaryAudioConfigRecord config) {
        String configured = StringUtils.hasText(config.getModel()) ? config.getModel().strip() : PRESET_TTS_MODEL;
        return isVoiceDesignModel(configured) ? PRESET_TTS_MODEL : configured;
    }

    private String effectiveVoice(ShareSummaryAudioConfigRecord config) {
        return StringUtils.hasText(config.getVoice()) ? config.getVoice().strip() : "mimo_default";
    }

    private boolean isVoiceDesignModel(String model) {
        return VOICE_DESIGN_TTS_MODEL.equalsIgnoreCase(model);
    }

    private List<Map<String, String>> messages(ShareSummaryAudioConfigRecord config, String input) {
        String assistantContent = assistantContent(config.getStyle(), input);
        return List.of(Map.of("role", "assistant", "content", assistantContent));
    }

    private String assistantContent(String style, String input) {
        String text = input == null ? "" : input;
        String audioTag = presetAudioTag(style);
        if (StringUtils.hasText(audioTag) && !hasLeadingAudioTag(text)) {
            return "(" + audioTag + ")" + text;
        }
        return text;
    }

    private boolean hasLeadingAudioTag(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.stripLeading();
        return value.startsWith("(")
                || value.startsWith("（")
                || value.startsWith("[");
    }

    private String presetAudioTag(String style) {
        if (!StringUtils.hasText(style)) {
            return "";
        }
        String value = stripTagBrackets(style.strip());
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if ("SUN_WUKONG".equalsIgnoreCase(value) || value.contains("孙悟空") || value.contains("神似孙悟空")) {
            return SUN_WUKONG_TAG;
        }
        if (isLegacyInstruction(value)) {
            String mapped = legacyInstructionTag(value);
            return StringUtils.hasText(mapped) ? mapped : "";
        }
        return value;
    }

    private String stripTagBrackets(String value) {
        String result = value;
        if ((result.startsWith("(") && result.endsWith(")"))
                || (result.startsWith("（") && result.endsWith("）"))
                || (result.startsWith("[") && result.endsWith("]"))) {
            result = result.substring(1, result.length() - 1).strip();
        }
        return result;
    }

    private boolean isLegacyInstruction(String value) {
        return value.startsWith("请用")
                || value.contains("朗读")
                || value.contains("演绎")
                || value.contains("角色语气")
                || value.contains("风格");
    }

    private String legacyInstructionTag(String value) {
        if (value.contains("林黛玉")) {
            return "林黛玉";
        }
        if (value.contains("粤语")) {
            return "粤语";
        }
        if (value.contains("四川话")) {
            return "四川话";
        }
        if (value.contains("东北话")) {
            return "东北话";
        }
        if (value.contains("唱歌") || value.contains("sing")) {
            return "唱歌";
        }
        if (value.contains("磁性")) {
            return "磁性";
        }
        if (value.contains("严肃")) {
            return "严肃";
        }
        if (value.contains("活泼")) {
            return "活泼";
        }
        return "";
    }

    private HttpResponse<byte[]> sendWithTotalTimeout(
            HttpRequest request,
            Duration timeout,
            String model,
            String voice,
            URI endpoint,
            long startedAt
    ) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> future;
        try {
            future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (RuntimeException exception) {
            logRequestFailure(model, voice, endpoint, startedAt, exception);
            throw new IOException("MiMo TTS provider request failed.", exception);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            logRequestFailure(model, voice, endpoint, startedAt, exception, "MiMo TTS provider timed out after " + timeout.toMillis() + " ms");
            throw new IOException("MiMo TTS provider timed out after " + timeout.toMillis() + " ms.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            logRequestFailure(model, voice, endpoint, startedAt, cause);
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            }
            throw new IOException("MiMo TTS provider request failed.", cause);
        } catch (InterruptedException exception) {
            future.cancel(true);
            logRequestFailure(model, voice, endpoint, startedAt, exception);
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private void logRequestFailure(String model, String voice, URI endpoint, long startedAt, Throwable exception) {
        logRequestFailure(model, voice, endpoint, startedAt, exception, exception.getMessage());
    }

    private void logRequestFailure(String model, String voice, URI endpoint, long startedAt, Throwable exception, String message) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.warn(
                "share_summary_mimo_audio_request_failed model={} voice={} endpoint={} durationMs={} errorType={} message={}",
                model,
                voice,
                endpoint,
                durationMs,
                exception.getClass().getSimpleName(),
                message
        );
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
