package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioStatus;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunStatus;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryAudioMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryImageMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.render.ShareSummaryMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ShareSummaryAudioService {
    private static final Logger log = LoggerFactory.getLogger(ShareSummaryAudioService.class);
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120;
    private static final int MAX_REQUEST_TIMEOUT_SECONDS = 1800;
    private static final long MAX_AUDIO_BYTES = 20L * 1024L * 1024L;
    private static final String DEFAULT_PROVIDER_TYPE = "OPENAI_COMPATIBLE";
    private static final String DEFAULT_BASE_URL = "https://tts.wangwangit.com";
    private static final String DEFAULT_ENDPOINT_PATH = "/v1/audio/speech";
    private static final String DEFAULT_VOICE = "zh-CN-YunhaoNeural";
    private static final double DEFAULT_SPEED = 1.2;
    private static final int DEFAULT_PITCH = 0;
    private static final String DEFAULT_STYLE = "newscast";
    private static final String DEFAULT_OUTPUT_FORMAT = "mp3";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ShareSummaryAudioMapper audioMapper;
    private final ShareSummaryMapper shareSummaryMapper;
    private final ShareSummaryImageMapper imageMapper;
    private final ShareSummaryAudioClient audioClient;
    private final ExecutorService executor;
    private final LinkPeekProperties properties;
    private final Clock clock;

    public ShareSummaryAudioService(
            ShareSummaryAudioMapper audioMapper,
            ShareSummaryMapper shareSummaryMapper,
            ShareSummaryImageMapper imageMapper,
            ShareSummaryAudioClient audioClient,
            @Qualifier("shareSummaryAudioExecutor") ExecutorService executor,
            LinkPeekProperties properties,
            Clock clock
    ) {
        this.audioMapper = audioMapper;
        this.shareSummaryMapper = shareSummaryMapper;
        this.imageMapper = imageMapper;
        this.audioClient = audioClient;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    public ConfigResponse config() {
        return ConfigResponse.fromRecord(configRecord());
    }

    public ConfigResponse updateConfig(ConfigRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Audio config payload is required.");
        }
        ShareSummaryAudioConfigRecord existing = audioMapper.selectConfig();
        ShareSummaryAudioConfigRecord normalized = normalizeConfig(request, existing);
        normalized.setUpdatedAt(now());
        audioMapper.upsertConfig(normalized);
        return ConfigResponse.fromRecord(audioMapper.selectConfig());
    }

    public ConfigResponse testConfig(ConfigRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Audio config payload is required.");
        }
        ShareSummaryAudioConfigRecord normalized = normalizeConfig(request, audioMapper.selectConfig());
        validateReadyConfig(normalized);
        return ConfigResponse.fromRecord(normalized);
    }

    public AudioResponse generateAudio(long runId, boolean regenerate) {
        ShareSummaryRunRecord run = existingRun(runId);
        if (!ShareSummaryRunStatus.SUCCESS.name().equals(run.getStatus())) {
            throw new IllegalArgumentException("Only successful share summary runs can generate audio.");
        }
        ShareSummaryAudioConfigRecord config = configRecord();
        validateReadyConfig(config);
        ShareSummaryAudioRecord active = audioMapper.selectActiveAudio(runId);
        if (active != null) {
            throw new IllegalStateException("AUDIO_GENERATION_IN_PROGRESS");
        }
        if (!regenerate) {
            ShareSummaryAudioRecord successful = audioMapper.selectLatestSuccessfulAudio(runId);
            if (successful != null) {
                return AudioResponse.fromRecord(successful, publicAudioUrl(runId));
            }
        }

        ShareSummaryAudioRecord audio = createPendingAudio(run, config);
        audioMapper.insertAudio(audio);
        submitAudioGeneration(audio.getId());
        return AudioResponse.fromRecord(audioMapper.selectAudio(audio.getId()), publicAudioUrl(runId));
    }

    public void triggerAutoGeneration(ShareSummaryRunRecord run) {
        if (run == null || run.getId() == null || !ShareSummaryRunStatus.SUCCESS.name().equals(run.getStatus())) {
            return;
        }
        ShareSummaryAudioConfigRecord config = audioMapper.selectConfig();
        if (config == null || !config.isEnabled() || !config.isAutoGenerate()) {
            return;
        }
        try {
            generateAudio(run.getId(), false);
        } catch (RuntimeException exception) {
            log.warn("share_summary_auto_audio_generation_skipped runId={} message={}", run.getId(), exception.getMessage());
        }
    }

    public List<AudioResponse> audios(long runId) {
        existingRun(runId);
        String publicAudioUrl = publicAudioUrl(runId);
        return audioMapper.selectAudiosForRun(runId).stream()
                .map(audio -> AudioResponse.fromRecord(audio, publicAudioUrl))
                .toList();
    }

    public int deleteAudiosForRun(long runId) {
        if (audioMapper.selectActiveAudio(runId) != null) {
            throw new IllegalStateException("Share summary audio generation is in progress.");
        }
        List<ShareSummaryAudioRecord> audios = audioMapper.selectAudiosForRun(runId);
        int deleted = audioMapper.deleteAudiosForRun(runId);
        deleteStoredAudiosAfterCommit(audios);
        return deleted;
    }

    public AudioResponse audio(long audioId) {
        ShareSummaryAudioRecord audio = existingAudio(audioId);
        return AudioResponse.fromRecord(audio, publicAudioUrl(audio.getRunId()));
    }

    public AudioSummary audioSummary(long runId) {
        ShareSummaryAudioRecord latest = audioMapper.selectLatestAudio(runId);
        ShareSummaryAudioRecord successful = audioMapper.selectLatestSuccessfulAudio(runId);
        if (latest == null && successful == null) {
            return AudioSummary.empty();
        }
        ShareSummaryAudioRecord display = successful != null ? successful : latest;
        return AudioSummary.fromRecord(display, latest == null ? null : latest.getStatus(), latest == null ? null : latest.getErrorMessage(), publicAudioUrl(runId));
    }

    public PublicAudio publicAudio(String publicToken, String ext) {
        if (!StringUtils.hasText(publicToken)) {
            throw new IllegalArgumentException("Share summary audio token is required.");
        }
        ShareSummaryImageRecord image = imageMapper.selectImageByPublicToken(publicToken.strip());
        if (image == null) {
            throw new IllegalArgumentException("Share summary audio was not found.");
        }
        ShareSummaryAudioRecord audio = audioMapper.selectLatestSuccessfulAudio(image.getRunId());
        if (audio == null || !ShareSummaryAudioStatus.SUCCESS.name().equals(audio.getStatus()) || !StringUtils.hasText(audio.getStorageKey())) {
            throw new IllegalArgumentException("Share summary audio is not available.");
        }
        String expectedExt = audio.getOutputFormat();
        if (StringUtils.hasText(ext) && StringUtils.hasText(expectedExt) && !expectedExt.equalsIgnoreCase(ext)) {
            throw new IllegalArgumentException("Share summary audio extension does not match.");
        }
        Path path = storageRoot().resolve(audio.getStorageKey()).normalize();
        if (!path.startsWith(storageRoot()) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Share summary audio file was not found.");
        }
        return new PublicAudio(new FileSystemResource(path), MediaType.parseMediaType("audio/mpeg"));
    }

    private void submitAudioGeneration(long audioId) {
        try {
            executor.execute(() -> generateAudioNow(audioId));
        } catch (RejectedExecutionException exception) {
            ShareSummaryAudioRecord audio = audioMapper.selectAudio(audioId);
            if (audio != null) {
                audio.setStatus(ShareSummaryAudioStatus.FAILED.name());
                audio.setErrorMessage("AUDIO_QUEUE_FULL");
                audio.setFinishedAt(now());
                audioMapper.updateAudio(audio);
            }
        }
    }

    private void generateAudioNow(long audioId) {
        ShareSummaryAudioRecord audio = audioMapper.selectAudio(audioId);
        if (audio == null) {
            return;
        }
        long startedAt = now();
        audio.setStatus(ShareSummaryAudioStatus.GENERATING.name());
        audio.setStartedAt(startedAt);
        audioMapper.updateAudio(audio);
        try {
            ShareSummaryAudioConfigRecord config = configRecord();
            ShareSummaryAudioClient.AudioGenerationResult result = audioClient.generate(config, audio.getTextSnapshot());
            byte[] bytes = result.audioBytes();
            if (bytes.length > MAX_AUDIO_BYTES) {
                throw new IOException("Audio response exceeded 20 MB.");
            }
            String storageKey = saveAudioBytes(audio, bytes);
            audio.setStorageKey(storageKey);
            audio.setAudioUrl(publicAudioUrl(audio.getRunId()));
            audio.setRawResponseSnapshot(result.rawResponseSnapshot());
            audio.setDurationMs(result.durationMs());
            audio.setStatus(ShareSummaryAudioStatus.SUCCESS.name());
            audio.setErrorMessage(null);
            audio.setFinishedAt(now());
            audioMapper.updateAudio(audio);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failAudio(audio, "Audio generation was interrupted.");
        } catch (RuntimeException | IOException exception) {
            failAudio(audio, limitError(exception.getMessage()));
        }
    }

    private ShareSummaryAudioRecord createPendingAudio(ShareSummaryRunRecord run, ShareSummaryAudioConfigRecord config) {
        ShareSummaryAudioRecord audio = new ShareSummaryAudioRecord();
        audio.setRunId(run.getId());
        audio.setAttemptNo(audioMapper.selectNextAttemptNo(run.getId()));
        audio.setStatus(ShareSummaryAudioStatus.PENDING.name());
        audio.setProviderType(config.getProviderType());
        audio.setModel(config.getModel());
        audio.setVoice(config.getVoice());
        audio.setSpeed(config.getSpeed());
        audio.setPitch(config.getPitch());
        audio.setStyle(config.getStyle());
        audio.setOutputFormat(config.getOutputFormat());
        audio.setTextSnapshot(audioText(run));
        audio.setCreatedAt(now());
        return audio;
    }

    private ShareSummaryAudioConfigRecord normalizeConfig(ConfigRequest request, ShareSummaryAudioConfigRecord existing) {
        ShareSummaryAudioConfigRecord config = new ShareSummaryAudioConfigRecord();
        config.setId(1L);
        config.setEnabled(Boolean.TRUE.equals(request.enabled()));
        config.setAutoGenerate(Boolean.TRUE.equals(request.autoGenerate()));
        config.setProviderType(normalizeProviderType(request.providerType()));
        config.setBaseUrl(StringUtils.hasText(request.baseUrl()) ? request.baseUrl().strip() : DEFAULT_BASE_URL);
        config.setEndpointPath(normalizeEndpointPath(request.endpointPath()));
        String apiKey = optionalStrip(request.apiKey());
        if (!StringUtils.hasText(apiKey) && existing != null && StringUtils.hasText(existing.getApiKey())) {
            apiKey = existing.getApiKey();
        }
        config.setApiKey(apiKey);
        config.setModel(optionalStrip(request.model()));
        config.setVoice(StringUtils.hasText(request.voice()) ? request.voice().strip() : DEFAULT_VOICE);
        config.setSpeed(normalizeSpeed(request.speed()));
        config.setPitch(request.pitch() == null ? DEFAULT_PITCH : request.pitch());
        config.setStyle(StringUtils.hasText(request.style()) ? request.style().strip() : DEFAULT_STYLE);
        config.setOutputFormat(normalizeOutputFormat(request.outputFormat()));
        int timeout = request.requestTimeoutSeconds() == null ? DEFAULT_REQUEST_TIMEOUT_SECONDS : request.requestTimeoutSeconds();
        if (timeout < 1 || timeout > MAX_REQUEST_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("Audio request timeout must be between 1 and 1800 seconds.");
        }
        config.setRequestTimeoutSeconds(timeout);
        return config;
    }

    private ShareSummaryAudioConfigRecord configRecord() {
        ShareSummaryAudioConfigRecord config = audioMapper.selectConfig();
        if (config != null) {
            config.setProviderType(normalizeProviderType(config.getProviderType()));
            config.setEndpointPath(normalizeEndpointPath(config.getEndpointPath()));
            config.setOutputFormat(normalizeOutputFormat(config.getOutputFormat()));
            return config;
        }
        ShareSummaryAudioConfigRecord defaults = new ShareSummaryAudioConfigRecord();
        defaults.setId(1L);
        defaults.setEnabled(false);
        defaults.setAutoGenerate(false);
        defaults.setProviderType(DEFAULT_PROVIDER_TYPE);
        defaults.setBaseUrl(DEFAULT_BASE_URL);
        defaults.setEndpointPath(DEFAULT_ENDPOINT_PATH);
        defaults.setApiKey("");
        defaults.setModel("");
        defaults.setVoice(DEFAULT_VOICE);
        defaults.setSpeed(DEFAULT_SPEED);
        defaults.setPitch(DEFAULT_PITCH);
        defaults.setStyle(DEFAULT_STYLE);
        defaults.setOutputFormat(DEFAULT_OUTPUT_FORMAT);
        defaults.setRequestTimeoutSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS);
        defaults.setUpdatedAt(0);
        return defaults;
    }

    private void validateReadyConfig(ShareSummaryAudioConfigRecord config) {
        if (!config.isEnabled()) {
            throw new IllegalArgumentException("Share summary TTS generation is disabled.");
        }
        if (!StringUtils.hasText(config.getBaseUrl())) {
            throw new IllegalArgumentException("Audio provider base URL must not be blank.");
        }
        if (!StringUtils.hasText(config.getVoice())) {
            throw new IllegalArgumentException("Audio provider voice must not be blank.");
        }
    }

    private String audioText(ShareSummaryRunRecord run) {
        StringBuilder text = new StringBuilder();
        String title = ogTitle(run);
        if (StringUtils.hasText(title)) {
            text.append(title).append('\n');
        }
        String description = ogDescription(run);
        if (StringUtils.hasText(description)) {
            text.append(description).append('\n');
        }
        if (StringUtils.hasText(run.getReport())) {
            text.append(ShareSummaryMarkdownRenderer.toPlainText(run.getReport()));
        }
        return text.toString().strip();
    }

    private String ogTitle(ShareSummaryRunRecord run) {
        ShareSummaryImageRecord image = imageMapper.selectLatestSuccessfulImage(run.getId());
        if (image != null && StringUtils.hasText(image.getOgTitle())) {
            return image.getOgTitle();
        }
        io.github.shigella520.linkpeek.server.admin.model.ShareSummaryPeriodType periodType =
                io.github.shigella520.linkpeek.server.admin.model.ShareSummaryPeriodType.fromValue(run.getPeriodType());
        LocalDate start = millisToDate(run.getWindowStart());
        return switch (periodType) {
            case DAILY -> "LinkPeek - " + start.format(DATE_FORMATTER) + " 日报";
            case WEEKLY -> weeklyTitle(start);
            case MONTHLY -> "LinkPeek - " + start.getYear() + "年" + start.getMonthValue() + "月月报";
        };
    }

    private String ogDescription(ShareSummaryRunRecord run) {
        ShareSummaryImageRecord image = imageMapper.selectLatestSuccessfulImage(run.getId());
        if (image != null && StringUtils.hasText(image.getOgDescription())) {
            return image.getOgDescription();
        }
        return "本报告汇总了 %s 至 %s 的链接分享与内容洞察。".formatted(
                dateLabel(run.getWindowStart()),
                dateLabel(run.getWindowEnd())
        );
    }

    private String weeklyTitle(LocalDate start) {
        WeekFields weekFields = WeekFields.ISO;
        int week = start.get(weekFields.weekOfWeekBasedYear());
        int year = start.get(weekFields.weekBasedYear());
        return "LinkPeek - %d年第%02d周周报".formatted(year, week);
    }

    private LocalDate millisToDate(long millis) {
        ZoneId zone = clock.getZone();
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    private String dateLabel(long millis) {
        return millisToDate(millis).format(DATE_FORMATTER);
    }

    private String saveAudioBytes(ShareSummaryAudioRecord audio, byte[] bytes) throws IOException {
        String storageKey = "share-summary/audios/%d/%d.%s".formatted(audio.getRunId(), audio.getId(), audio.getOutputFormat());
        Path path = storageRoot().resolve(storageKey).normalize();
        if (!path.startsWith(storageRoot())) {
            throw new IOException("Audio storage path is invalid.");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes);
        return storageKey;
    }

    private void failAudio(ShareSummaryAudioRecord audio, String message) {
        audio.setStatus(ShareSummaryAudioStatus.FAILED.name());
        audio.setErrorMessage(StringUtils.hasText(message) ? message : "Audio generation failed.");
        audio.setFinishedAt(now());
        audioMapper.updateAudio(audio);
    }

    private void deleteStoredAudio(ShareSummaryAudioRecord audio) {
        if (audio == null || !StringUtils.hasText(audio.getStorageKey())) {
            return;
        }
        try {
            Path path = storageRoot().resolve(audio.getStorageKey()).normalize();
            if (!path.startsWith(storageRoot())) {
                log.warn("share_summary_audio_delete_skipped_invalid_path audioId={} storageKey={}", audio.getId(), audio.getStorageKey());
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("share_summary_audio_file_delete_failed audioId={} storageKey={} message={}", audio.getId(), audio.getStorageKey(), exception.getMessage(), exception);
        }
    }

    private void deleteStoredAudiosAfterCommit(List<ShareSummaryAudioRecord> audios) {
        Runnable cleanup = () -> audios.forEach(this::deleteStoredAudio);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    private ShareSummaryRunRecord existingRun(long runId) {
        ShareSummaryRunRecord run = shareSummaryMapper.selectRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("Share summary run was not found.");
        }
        return run;
    }

    private ShareSummaryAudioRecord existingAudio(long audioId) {
        ShareSummaryAudioRecord audio = audioMapper.selectAudio(audioId);
        if (audio == null) {
            throw new IllegalArgumentException("Share summary audio was not found.");
        }
        return audio;
    }

    private Path storageRoot() {
        return properties.getCacheDir().toAbsolutePath().normalize();
    }

    private String publicAudioUrl(long runId) {
        ShareSummaryImageRecord image = imageMapper.selectLatestSuccessfulImage(runId);
        if (image == null || !StringUtils.hasText(image.getPublicToken())) {
            return null;
        }
        return baseUrl() + "/share-summary/audios/" + image.getPublicToken() + ".mp3";
    }

    private String baseUrl() {
        String value = properties.getBaseUrl();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String normalizeProviderType(String providerType) {
        if (!StringUtils.hasText(providerType)) {
            return DEFAULT_PROVIDER_TYPE;
        }
        return providerType.strip().toUpperCase(Locale.ROOT);
    }

    private String normalizeEndpointPath(String endpointPath) {
        if (!StringUtils.hasText(endpointPath)) {
            return DEFAULT_ENDPOINT_PATH;
        }
        String path = endpointPath.strip();
        return path.startsWith("/") ? path : "/" + path;
    }

    private double normalizeSpeed(Double speed) {
        double value = speed == null ? DEFAULT_SPEED : speed;
        if (value < 0.25 || value > 4.0) {
            throw new IllegalArgumentException("Audio speed must be between 0.25 and 4.0.");
        }
        return value;
    }

    private String normalizeOutputFormat(String outputFormat) {
        String value = StringUtils.hasText(outputFormat) ? outputFormat.strip().toLowerCase(Locale.ROOT) : DEFAULT_OUTPUT_FORMAT;
        if (!DEFAULT_OUTPUT_FORMAT.equals(value)) {
            throw new IllegalArgumentException("Audio output format must be mp3.");
        }
        return value;
    }

    private String optionalStrip(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private String limitError(String message) {
        if (!StringUtils.hasText(message)) {
            return "Audio generation failed.";
        }
        String stripped = message.strip();
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500);
    }

    private long now() {
        return Instant.now(clock).toEpochMilli();
    }

    public record ConfigRequest(
            Boolean enabled,
            Boolean autoGenerate,
            String providerType,
            String baseUrl,
            String endpointPath,
            String apiKey,
            String model,
            String voice,
            Double speed,
            Integer pitch,
            String style,
            String outputFormat,
            Integer requestTimeoutSeconds
    ) {
    }

    public record ConfigResponse(
            boolean enabled,
            boolean autoGenerate,
            String providerType,
            String baseUrl,
            String endpointPath,
            boolean apiKeyConfigured,
            String model,
            String voice,
            double speed,
            int pitch,
            String style,
            String outputFormat,
            int requestTimeoutSeconds,
            long updatedAt
    ) {
        static ConfigResponse fromRecord(ShareSummaryAudioConfigRecord record) {
            return new ConfigResponse(
                    record.isEnabled(),
                    record.isAutoGenerate(),
                    record.getProviderType(),
                    record.getBaseUrl(),
                    record.getEndpointPath(),
                    StringUtils.hasText(record.getApiKey()),
                    record.getModel(),
                    record.getVoice(),
                    record.getSpeed(),
                    record.getPitch(),
                    record.getStyle(),
                    record.getOutputFormat(),
                    record.getRequestTimeoutSeconds(),
                    record.getUpdatedAt()
            );
        }
    }

    public record AudioResponse(
            Long id,
            long runId,
            int attemptNo,
            String status,
            String providerType,
            String model,
            String voice,
            double speed,
            int pitch,
            String style,
            String outputFormat,
            String audioUrl,
            String errorMessage,
            long durationMs,
            long createdAt,
            Long startedAt,
            Long finishedAt
    ) {
        static AudioResponse fromRecord(ShareSummaryAudioRecord record, String publicAudioUrl) {
            return new AudioResponse(
                    record.getId(),
                    record.getRunId(),
                    record.getAttemptNo(),
                    record.getStatus(),
                    record.getProviderType(),
                    record.getModel(),
                    record.getVoice(),
                    record.getSpeed(),
                    record.getPitch(),
                    record.getStyle(),
                    record.getOutputFormat(),
                    StringUtils.hasText(publicAudioUrl) ? publicAudioUrl : record.getAudioUrl(),
                    record.getErrorMessage(),
                    record.getDurationMs(),
                    record.getCreatedAt(),
                    record.getStartedAt(),
                    record.getFinishedAt()
            );
        }
    }

    public record AudioSummary(
            String audioStatus,
            String audioUrl,
            String audioErrorMessage
    ) {
        static AudioSummary empty() {
            return new AudioSummary(ShareSummaryAudioStatus.NOT_GENERATED.name(), null, null);
        }

        static AudioSummary fromRecord(ShareSummaryAudioRecord record, String latestStatus, String latestError, String audioUrl) {
            return new AudioSummary(
                    StringUtils.hasText(latestStatus) ? latestStatus : record.getStatus(),
                    ShareSummaryAudioStatus.SUCCESS.name().equals(record.getStatus()) ? audioUrl : null,
                    latestError
            );
        }
    }

    public record PublicAudio(Resource resource, MediaType mediaType) {
    }
}
