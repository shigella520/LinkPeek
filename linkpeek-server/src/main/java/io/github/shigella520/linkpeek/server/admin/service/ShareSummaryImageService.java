package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageConfigRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageProviderType;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageStatus;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryPeriodType;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunStatus;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryImageMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ShareSummaryImageService {
    private static final Logger log = LoggerFactory.getLogger(ShareSummaryImageService.class);
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_IMAGE_REDIRECTS = 5;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_WIDTH = 1200;
    private static final int DEFAULT_HEIGHT = 630;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Set<String> OPENAI_IMAGE_SIZES = Set.of("auto", "1024x1024", "1536x1024", "1024x1536");
    private static final String DEFAULT_STYLE_PROMPT = "现代数据报告封面，清晰、有层次，科技感但不过度炫光，适合产品运营和内容分析场景，画面干净，色彩专业。";

    private final ShareSummaryImageMapper imageMapper;
    private final ShareSummaryMapper shareSummaryMapper;
    private final ShareSummaryImageClient imageClient;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final NotificationService notificationService;
    private final LinkPeekProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public ShareSummaryImageService(
            ShareSummaryImageMapper imageMapper,
            ShareSummaryMapper shareSummaryMapper,
            ShareSummaryImageClient imageClient,
            @Qualifier("shareSummaryImageHttpClient") HttpClient httpClient,
            @Qualifier("shareSummaryImageExecutor") ExecutorService executor,
            NotificationService notificationService,
            LinkPeekProperties properties,
            Clock clock
    ) {
        this.imageMapper = imageMapper;
        this.shareSummaryMapper = shareSummaryMapper;
        this.imageClient = imageClient;
        this.httpClient = httpClient;
        this.executor = executor;
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
    }

    public ConfigResponse config() {
        return ConfigResponse.fromRecord(configRecord());
    }

    public ConfigResponse updateConfig(ConfigRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Image config payload is required.");
        }
        ShareSummaryImageConfigRecord existing = imageMapper.selectConfig();
        ShareSummaryImageConfigRecord normalized = normalizeConfig(request, existing);
        normalized.setUpdatedAt(now());
        imageMapper.upsertConfig(normalized);
        return ConfigResponse.fromRecord(imageMapper.selectConfig());
    }

    public ConfigResponse testConfig(ConfigRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Image config payload is required.");
        }
        ShareSummaryImageConfigRecord normalized = normalizeConfig(request, imageMapper.selectConfig());
        validateReadyConfig(normalized);
        return ConfigResponse.fromRecord(normalized);
    }

    public ImageResponse generateImage(long runId, boolean regenerate) {
        ShareSummaryRunRecord run = existingRun(runId);
        if (!ShareSummaryRunStatus.SUCCESS.name().equals(run.getStatus())) {
            throw new IllegalArgumentException("Only successful share summary runs can generate images.");
        }
        ShareSummaryImageConfigRecord config = configRecord();
        validateReadyConfig(config);
        ShareSummaryImageRecord active = imageMapper.selectActiveImage(runId);
        if (active != null) {
            throw new IllegalStateException("IMAGE_GENERATION_IN_PROGRESS");
        }
        if (!regenerate) {
            ShareSummaryImageRecord successful = imageMapper.selectLatestSuccessfulImage(runId);
            if (successful != null) {
                return ImageResponse.fromRecord(successful);
            }
        }

        ShareSummaryImageRecord image = createPendingImage(run, config);
        imageMapper.insertImage(image);
        submitImageGeneration(image.getId());
        return ImageResponse.fromRecord(imageMapper.selectImage(image.getId()));
    }

    public void triggerAutoGeneration(ShareSummaryRunRecord run) {
        if (run == null || run.getId() == null || !ShareSummaryRunStatus.SUCCESS.name().equals(run.getStatus())) {
            return;
        }
        ShareSummaryImageConfigRecord config = imageMapper.selectConfig();
        if (config == null || !config.isEnabled() || !config.isAutoGenerate()) {
            return;
        }
        try {
            generateImage(run.getId(), false);
        } catch (RuntimeException exception) {
            log.warn("share_summary_auto_image_generation_skipped runId={} message={}", run.getId(), exception.getMessage());
        }
    }

    public List<ImageResponse> images(long runId) {
        existingRun(runId);
        return imageMapper.selectImagesForRun(runId).stream()
                .map(ImageResponse::fromRecord)
                .toList();
    }

    public int deleteImagesForRun(long runId) {
        if (imageMapper.selectActiveImage(runId) != null) {
            throw new IllegalStateException("Share summary image generation is in progress.");
        }
        List<ShareSummaryImageRecord> images = imageMapper.selectImagesForRun(runId);
        int deleted = imageMapper.deleteImagesForRun(runId);
        deleteStoredImagesAfterCommit(images);
        return deleted;
    }

    public ImageResponse image(long imageId) {
        return ImageResponse.fromRecord(existingImage(imageId));
    }

    public ImageSummary imageSummary(long runId) {
        ShareSummaryImageRecord latest = imageMapper.selectLatestImage(runId);
        ShareSummaryImageRecord successful = imageMapper.selectLatestSuccessfulImage(runId);
        if (latest == null && successful == null) {
            return ImageSummary.empty();
        }
        ShareSummaryImageRecord display = successful != null ? successful : latest;
        return ImageSummary.fromRecord(display, latest == null ? null : latest.getStatus(), latest == null ? null : latest.getErrorMessage());
    }

    public PublicImage publicImage(String publicToken, String ext) {
        ShareSummaryImageRecord image = existingPublicImage(publicToken);
        if (!ShareSummaryImageStatus.SUCCESS.name().equals(image.getStatus()) || !StringUtils.hasText(image.getStorageKey())) {
            throw new IllegalArgumentException("Share summary image is not available.");
        }
        String expectedExt = image.getOutputFormat();
        if (StringUtils.hasText(ext) && StringUtils.hasText(expectedExt) && !expectedExt.equalsIgnoreCase(ext)) {
            throw new IllegalArgumentException("Share summary image extension does not match.");
        }
        Path path = storageRoot().resolve(image.getStorageKey()).normalize();
        if (!path.startsWith(storageRoot()) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Share summary image file was not found.");
        }
        MediaType mediaType = "jpg".equalsIgnoreCase(image.getOutputFormat()) || "jpeg".equalsIgnoreCase(image.getOutputFormat())
                ? MediaType.IMAGE_JPEG
                : MediaType.IMAGE_PNG;
        return new PublicImage(new FileSystemResource(path), mediaType);
    }

    public PublicReport publicReport(String publicToken) {
        ShareSummaryImageRecord image = existingPublicImage(publicToken);
        ShareSummaryRunRecord run = existingRun(image.getRunId());
        return new PublicReport(image, run);
    }

    private void submitImageGeneration(long imageId) {
        try {
            executor.execute(() -> generateImageNow(imageId));
        } catch (RejectedExecutionException exception) {
            ShareSummaryImageRecord image = imageMapper.selectImage(imageId);
            if (image != null) {
                image.setStatus(ShareSummaryImageStatus.FAILED.name());
                image.setErrorMessage("IMAGE_QUEUE_FULL");
                image.setFinishedAt(now());
                imageMapper.updateImage(image);
            }
        }
    }

    private void generateImageNow(long imageId) {
        ShareSummaryImageRecord image = imageMapper.selectImage(imageId);
        if (image == null) {
            return;
        }
        long startedAt = now();
        image.setStatus(ShareSummaryImageStatus.GENERATING.name());
        image.setStartedAt(startedAt);
        imageMapper.updateImage(image);
        try {
            ShareSummaryImageConfigRecord config = configRecord();
            ShareSummaryImageClient.ImageGenerationResult result = imageClient.generate(config, image.getPromptSnapshot());
            byte[] sourceBytes = StringUtils.hasText(result.base64())
                    ? decodeBase64Image(result.base64())
                    : downloadImage(result.imageUrl(), config.getRequestTimeoutSeconds());
            byte[] outputBytes = standardizeImage(sourceBytes, image.getOutputFormat());
            String storageKey = saveImageBytes(image, outputBytes);
            image.setStorageKey(storageKey);
            image.setImageUrl(publicImageUrl(image));
            image.setOgImageUrl(publicImageUrl(image));
            image.setOgPageUrl(publicReportUrl(image));
            image.setRawResponseSnapshot(result.rawResponseSnapshot());
            image.setDurationMs(result.durationMs());
            image.setStatus(ShareSummaryImageStatus.SUCCESS.name());
            image.setErrorMessage(null);
            image.setFinishedAt(now());
            imageMapper.updateImage(image);
            publishImageSuccess(image);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failImage(image, ShareSummaryImageStatus.FAILED, "Image generation was interrupted.");
        } catch (RuntimeException | IOException exception) {
            failImage(image, ShareSummaryImageStatus.FAILED, limitError(exception.getMessage()));
        }
    }

    private ShareSummaryImageRecord createPendingImage(ShareSummaryRunRecord run, ShareSummaryImageConfigRecord config) {
        ShareSummaryImageRecord image = new ShareSummaryImageRecord();
        image.setRunId(run.getId());
        image.setAttemptNo(imageMapper.selectNextAttemptNo(run.getId()));
        image.setStatus(ShareSummaryImageStatus.PENDING.name());
        image.setProviderType(config.getProviderType());
        image.setModel(config.getModel());
        image.setImageSize(config.getImageSize());
        image.setOutputFormat(config.getOutputFormat());
        image.setQuality(config.getQuality());
        image.setStylePromptSnapshot(config.getStylePrompt());
        image.setPromptSnapshot(imagePrompt(run, config));
        image.setPublicToken(newPublicToken());
        image.setOgTitle(ogTitle(run));
        image.setOgDescription(ogDescription(run));
        image.setCreatedAt(now());
        return image;
    }

    private ShareSummaryImageConfigRecord normalizeConfig(ConfigRequest request, ShareSummaryImageConfigRecord existing) {
        ShareSummaryImageConfigRecord config = new ShareSummaryImageConfigRecord();
        config.setId(1L);
        config.setEnabled(Boolean.TRUE.equals(request.enabled()));
        config.setAutoGenerate(Boolean.TRUE.equals(request.autoGenerate()));
        config.setProviderType(ShareSummaryImageProviderType.fromValue(request.providerType()).name());
        config.setBaseUrl(optionalStrip(request.baseUrl()));
        config.setEndpointPath(normalizeEndpointPath(request.endpointPath()));
        String apiKey = optionalStrip(request.apiKey());
        if (!StringUtils.hasText(apiKey) && existing != null && StringUtils.hasText(existing.getApiKey())) {
            apiKey = existing.getApiKey();
        }
        config.setApiKey(apiKey);
        config.setModel(optionalStrip(request.model()));
        config.setImageSize(normalizeImageSize(request.imageSize()));
        config.setQuality(StringUtils.hasText(request.quality()) ? request.quality().strip() : "auto");
        config.setOutputFormat(normalizeOutputFormat(request.outputFormat()));
        config.setStylePrompt(StringUtils.hasText(request.stylePrompt()) ? request.stylePrompt().strip() : DEFAULT_STYLE_PROMPT);
        int timeout = request.requestTimeoutSeconds() == null ? DEFAULT_REQUEST_TIMEOUT_SECONDS : request.requestTimeoutSeconds();
        if (timeout < 1 || timeout > 600) {
            throw new IllegalArgumentException("Image request timeout must be between 1 and 600 seconds.");
        }
        config.setRequestTimeoutSeconds(timeout);
        return config;
    }

    private ShareSummaryImageConfigRecord configRecord() {
        ShareSummaryImageConfigRecord config = imageMapper.selectConfig();
        if (config != null) {
            config.setImageSize(normalizeStoredImageSize(config.getImageSize()));
            return config;
        }
        ShareSummaryImageConfigRecord defaults = new ShareSummaryImageConfigRecord();
        defaults.setId(1L);
        defaults.setEnabled(false);
        defaults.setAutoGenerate(false);
        defaults.setProviderType(ShareSummaryImageProviderType.OPENAI_COMPATIBLE.name());
        defaults.setBaseUrl("");
        defaults.setEndpointPath("/v1/images/generations");
        defaults.setApiKey("");
        defaults.setModel("");
        defaults.setImageSize("auto");
        defaults.setQuality("auto");
        defaults.setOutputFormat("png");
        defaults.setStylePrompt(DEFAULT_STYLE_PROMPT);
        defaults.setRequestTimeoutSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS);
        defaults.setUpdatedAt(0);
        return defaults;
    }

    private void validateReadyConfig(ShareSummaryImageConfigRecord config) {
        if (!config.isEnabled()) {
            throw new IllegalArgumentException("Share summary AI image generation is disabled.");
        }
        if (!StringUtils.hasText(config.getBaseUrl())) {
            throw new IllegalArgumentException("Image provider base URL must not be blank.");
        }
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new IllegalArgumentException("Image provider API key must not be blank.");
        }
        if (!StringUtils.hasText(config.getModel())) {
            throw new IllegalArgumentException("Image provider model must not be blank.");
        }
    }

    private String imagePrompt(ShareSummaryRunRecord run, ShareSummaryImageConfigRecord config) {
        String report = Optional.ofNullable(run.getReport()).orElse("").strip();
        if (report.length() > 2_000) {
            report = report.substring(0, 2_000);
        }
        return """
                请生成一张适合 Open Graph 分享卡片的横版封面图。

                报告标题：
                %s

                报告时间范围：
                %s 至 %s

                报告摘要：
                %s

                风格要求：
                %s

                图片要求：
                - 横版构图，适合社交平台预览卡片。
                - 主题体现数据分析、链接洞察、内容报告和 LinkPeek。
                - 画面简洁，主体明确，适合作为报告封面。
                - 不要生成复杂 UI 截图。
                - 不要包含大量文字、小字或错误文字。
                - 不要包含真实个人信息、密钥、二维码或水印。
                """.formatted(
                ogTitle(run),
                dateLabel(run.getWindowStart()),
                dateLabel(run.getWindowEnd()),
                report,
                config.getStylePrompt()
        ).strip();
    }

    public String ogTitle(ShareSummaryRunRecord run) {
        ShareSummaryPeriodType periodType = ShareSummaryPeriodType.fromValue(run.getPeriodType());
        LocalDate start = millisToDate(run.getWindowStart());
        LocalDate endExclusive = millisToDate(run.getWindowEnd());
        return switch (periodType) {
            case DAILY -> "LinkPeek - " + start.format(DATE_FORMATTER) + " 日报";
            case WEEKLY -> weeklyTitle(start);
            case MONTHLY -> "LinkPeek - " + start.getYear() + "年" + start.getMonthValue() + "月月报";
        };
    }

    private String weeklyTitle(LocalDate start) {
        WeekFields weekFields = WeekFields.ISO;
        int week = start.get(weekFields.weekOfWeekBasedYear());
        int year = start.get(weekFields.weekBasedYear());
        return "LinkPeek - %d年第%02d周周报".formatted(year, week);
    }

    private String ogDescription(ShareSummaryRunRecord run) {
        return "本报告汇总了 %s 至 %s 的链接分享与内容洞察。".formatted(
                dateLabel(run.getWindowStart()),
                dateLabel(run.getWindowEnd())
        );
    }

    private byte[] decodeBase64Image(String base64) {
        String normalized = base64;
        int commaIndex = normalized.indexOf(',');
        if (commaIndex >= 0) {
            normalized = normalized.substring(commaIndex + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(normalized);
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image response exceeded 10 MB.");
        }
        return bytes;
    }

    private byte[] downloadImage(String imageUrl, int timeoutSeconds) throws IOException, InterruptedException {
        if (!StringUtils.hasText(imageUrl)) {
            throw new IllegalArgumentException("Image provider did not return an image URL.");
        }
        URI uri = URI.create(imageUrl.strip());
        validateDownloadUri(uri);
        HttpResponse<byte[]> response = sendImageDownloadRequest(uri, timeoutSeconds, 0);
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw new IOException("Image URL redirect was invalid.");
        }
        if (response.statusCode() >= 400) {
            throw new IOException("Image URL returned HTTP " + response.statusCode());
        }
        if (response.body().length > MAX_IMAGE_BYTES) {
            throw new IOException("Downloaded image exceeded 10 MB.");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IOException("Image URL returned non-image content type.");
        }
        return response.body();
    }

    private HttpResponse<byte[]> sendImageDownloadRequest(URI uri, int timeoutSeconds, int redirects)
            throws IOException, InterruptedException {
        validateDownloadUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int statusCode = response.statusCode();
        if (statusCode < 300 || statusCode >= 400) {
            return response;
        }
        if (redirects >= MAX_IMAGE_REDIRECTS) {
            throw new IOException("Image URL redirected too many times.");
        }
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IOException("Image URL redirect did not include Location."));
        URI nextUri = uri.resolve(location);
        return sendImageDownloadRequest(nextUri, timeoutSeconds, redirects + 1);
    }

    private void validateDownloadUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Image URL must use http or https.");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IOException("Image URL host is required.");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("Image URL host is not allowed.");
            }
        }
    }

    private byte[] standardizeImage(byte[] sourceBytes, String outputFormat) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        if (source == null) {
            throw new IOException("Image response could not be decoded.");
        }
        BufferedImage output = new BufferedImage(DEFAULT_WIDTH, DEFAULT_HEIGHT, "jpg".equals(outputFormat) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            double scale = Math.max(DEFAULT_WIDTH / (double) source.getWidth(), DEFAULT_HEIGHT / (double) source.getHeight());
            int scaledWidth = (int) Math.round(source.getWidth() * scale);
            int scaledHeight = (int) Math.round(source.getHeight() * scale);
            int x = (DEFAULT_WIDTH - scaledWidth) / 2;
            int y = (DEFAULT_HEIGHT - scaledHeight) / 2;
            graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        if (!ImageIO.write(output, outputFormat, outputStream)) {
            throw new IOException("Image output format is not supported.");
        }
        return outputStream.toByteArray();
    }

    private String saveImageBytes(ShareSummaryImageRecord image, byte[] bytes) throws IOException {
        String storageKey = "share-summary/images/%d/%d.%s".formatted(image.getRunId(), image.getId(), image.getOutputFormat());
        Path path = storageRoot().resolve(storageKey).normalize();
        if (!path.startsWith(storageRoot())) {
            throw new IOException("Image storage path is invalid.");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes);
        return storageKey;
    }

    private void failImage(ShareSummaryImageRecord image, ShareSummaryImageStatus status, String message) {
        image.setStatus(status.name());
        image.setErrorMessage(StringUtils.hasText(message) ? message : "Image generation failed.");
        image.setFinishedAt(now());
        imageMapper.updateImage(image);
    }

    private void publishImageSuccess(ShareSummaryImageRecord image) {
        if (notificationService == null) {
            return;
        }
        try {
            ShareSummaryRunRecord run = shareSummaryMapper.selectRun(image.getRunId());
            notificationService.publishShareSummaryImageSuccess(run, image);
        } catch (RuntimeException exception) {
            log.warn("share_summary_image_notification_failed imageId={} runId={} message={}", image.getId(), image.getRunId(), exception.getMessage(), exception);
        }
    }

    private void deleteStoredImage(ShareSummaryImageRecord image) {
        if (image == null || !StringUtils.hasText(image.getStorageKey())) {
            return;
        }
        try {
            Path path = storageRoot().resolve(image.getStorageKey()).normalize();
            if (!path.startsWith(storageRoot())) {
                log.warn("share_summary_image_delete_skipped_invalid_path imageId={} storageKey={}", image.getId(), image.getStorageKey());
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("share_summary_image_file_delete_failed imageId={} storageKey={} message={}", image.getId(), image.getStorageKey(), exception.getMessage(), exception);
        }
    }

    private void deleteStoredImagesAfterCommit(List<ShareSummaryImageRecord> images) {
        Runnable cleanup = () -> images.forEach(this::deleteStoredImage);
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

    private ShareSummaryImageRecord existingImage(long imageId) {
        ShareSummaryImageRecord image = imageMapper.selectImage(imageId);
        if (image == null) {
            throw new IllegalArgumentException("Share summary image was not found.");
        }
        return image;
    }

    private ShareSummaryImageRecord existingPublicImage(String publicToken) {
        if (!StringUtils.hasText(publicToken)) {
            throw new IllegalArgumentException("Share summary image token is required.");
        }
        ShareSummaryImageRecord image = imageMapper.selectImageByPublicToken(publicToken.strip());
        if (image == null) {
            throw new IllegalArgumentException("Share summary image was not found.");
        }
        return image;
    }

    private Path storageRoot() {
        return properties.getCacheDir().toAbsolutePath().normalize();
    }

    private String publicImageUrl(ShareSummaryImageRecord image) {
        return baseUrl() + "/share-summary/og-images/" + image.getPublicToken() + "." + image.getOutputFormat();
    }

    private String publicReportUrl(ShareSummaryImageRecord image) {
        return baseUrl() + "/share-summary/reports/" + image.getPublicToken();
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

    private String newPublicToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String normalizeEndpointPath(String endpointPath) {
        if (!StringUtils.hasText(endpointPath)) {
            return "/v1/images/generations";
        }
        String path = endpointPath.strip();
        return path.startsWith("/") ? path : "/" + path;
    }

    private String normalizeImageSize(String imageSize) {
        if (!StringUtils.hasText(imageSize)) {
            return "auto";
        }
        String value = imageSize.strip().toLowerCase(Locale.ROOT);
        if (!OPENAI_IMAGE_SIZES.contains(value)) {
            throw new IllegalArgumentException("Image size must be one of auto, 1024x1024, 1536x1024, 1024x1536.");
        }
        return value;
    }

    private String normalizeStoredImageSize(String imageSize) {
        if (!StringUtils.hasText(imageSize)) {
            return "auto";
        }
        String value = imageSize.strip().toLowerCase(Locale.ROOT);
        return OPENAI_IMAGE_SIZES.contains(value) ? value : "auto";
    }

    private String normalizeOutputFormat(String outputFormat) {
        String value = StringUtils.hasText(outputFormat) ? outputFormat.strip().toLowerCase(Locale.ROOT) : "png";
        if ("jpeg".equals(value)) {
            return "jpg";
        }
        if (!"jpg".equals(value) && !"png".equals(value)) {
            throw new IllegalArgumentException("Image output format must be jpg or png.");
        }
        return value;
    }

    private String optionalStrip(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private LocalDate millisToDate(long millis) {
        ZoneId zone = clock.getZone();
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    private String dateLabel(long millis) {
        return millisToDate(millis).format(DATE_FORMATTER);
    }

    private String limitError(String message) {
        if (!StringUtils.hasText(message)) {
            return "Image generation failed.";
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
            String imageSize,
            String quality,
            String outputFormat,
            String stylePrompt,
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
            String imageSize,
            String quality,
            String outputFormat,
            String stylePrompt,
            int requestTimeoutSeconds,
            long updatedAt
    ) {
        static ConfigResponse fromRecord(ShareSummaryImageConfigRecord record) {
            return new ConfigResponse(
                    record.isEnabled(),
                    record.isAutoGenerate(),
                    record.getProviderType(),
                    record.getBaseUrl(),
                    record.getEndpointPath(),
                    StringUtils.hasText(record.getApiKey()),
                    record.getModel(),
                    record.getImageSize(),
                    record.getQuality(),
                    record.getOutputFormat(),
                    record.getStylePrompt(),
                    record.getRequestTimeoutSeconds(),
                    record.getUpdatedAt()
            );
        }
    }

    public record ImageResponse(
            Long id,
            long runId,
            int attemptNo,
            String status,
            String providerType,
            String model,
            String imageSize,
            String outputFormat,
            String quality,
            String stylePromptSnapshot,
            String imageUrl,
            String ogImageUrl,
            String ogPageUrl,
            String ogTitle,
            String ogDescription,
            String errorMessage,
            long durationMs,
            long createdAt,
            Long startedAt,
            Long finishedAt
    ) {
        static ImageResponse fromRecord(ShareSummaryImageRecord record) {
            return new ImageResponse(
                    record.getId(),
                    record.getRunId(),
                    record.getAttemptNo(),
                    record.getStatus(),
                    record.getProviderType(),
                    record.getModel(),
                    record.getImageSize(),
                    record.getOutputFormat(),
                    record.getQuality(),
                    record.getStylePromptSnapshot(),
                    record.getImageUrl(),
                    record.getOgImageUrl(),
                    record.getOgPageUrl(),
                    record.getOgTitle(),
                    record.getOgDescription(),
                    record.getErrorMessage(),
                    record.getDurationMs(),
                    record.getCreatedAt(),
                    record.getStartedAt(),
                    record.getFinishedAt()
            );
        }
    }

    public record ImageSummary(
            String imageStatus,
            String latestImageUrl,
            String ogImageUrl,
            String ogPageUrl,
            String ogTitle,
            String ogDescription,
            String imageErrorMessage
    ) {
        static ImageSummary empty() {
            return new ImageSummary(ShareSummaryImageStatus.NOT_GENERATED.name(), null, null, null, null, null, null);
        }

        static ImageSummary fromRecord(ShareSummaryImageRecord record, String latestStatus, String latestError) {
            return new ImageSummary(
                    StringUtils.hasText(latestStatus) ? latestStatus : record.getStatus(),
                    record.getImageUrl(),
                    record.getOgImageUrl(),
                    record.getOgPageUrl(),
                    record.getOgTitle(),
                    record.getOgDescription(),
                    latestError
            );
        }
    }

    public record PublicImage(Resource resource, MediaType mediaType) {
    }

    public record PublicReport(ShareSummaryImageRecord image, ShareSummaryRunRecord run) {
    }
}
