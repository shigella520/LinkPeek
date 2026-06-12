package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageConfigRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageStatus;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryImageMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareSummaryImageServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path cacheDir;

    @Test
    void preservesExistingSuccessfulImageWhenRegenerationRedirectsToPrivateAddress() {
        FakeImageMapper imageMapper = new FakeImageMapper(config());
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        NotificationService notificationService = mock(NotificationService.class);
        ShareSummaryImageClient imageClient = new ShareSummaryImageClient(
                new ImageProviderHttpClient(200, """
                        {"data":[{"url":"http://93.184.216.34/image.png"}]}
                        """),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setBaseUrl("https://linkpeek.example.com");
        properties.setCacheDir(cacheDir);
        ShareSummaryImageService service = new ShareSummaryImageService(
                imageMapper,
                shareSummaryMapper,
                imageClient,
                new RedirectingImageHttpClient(),
                new DirectExecutorService(),
                notificationService,
                properties,
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );

        ShareSummaryImageRecord successful = existingSuccessfulImage();
        imageMapper.images.add(successful);
        service.generateImage(1, true);

        ShareSummaryImageService.ImageSummary summary = service.imageSummary(1);

        assertEquals(ShareSummaryImageStatus.FAILED.name(), imageMapper.latestImage().getStatus());
        assertTrue(imageMapper.latestImage().getErrorMessage().contains("Image URL host is not allowed"));
        assertEquals(successful.getOgImageUrl(), summary.ogImageUrl());
        assertEquals(ShareSummaryImageStatus.FAILED.name(), summary.imageStatus());
        verify(notificationService).publishShareSummaryImageFailed(
                any(ShareSummaryRunRecord.class),
                any(ShareSummaryImageRecord.class),
                eq("IOException"),
                org.mockito.ArgumentMatchers.contains("Image URL host is not allowed")
        );
    }

    @Test
    void publishesImageFailedNotificationWhenQueueRejectsGeneration() {
        FakeImageMapper imageMapper = new FakeImageMapper(config());
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        NotificationService notificationService = mock(NotificationService.class);
        ShareSummaryImageService service = new ShareSummaryImageService(
                imageMapper,
                shareSummaryMapper,
                new ShareSummaryImageClient(new ImageProviderHttpClient(200, "{\"data\":[]}"), new com.fasterxml.jackson.databind.ObjectMapper()),
                new RedirectingImageHttpClient(),
                new RejectingExecutorService(),
                notificationService,
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );

        service.generateImage(1, true);

        assertEquals(ShareSummaryImageStatus.FAILED.name(), imageMapper.latestImage().getStatus());
        assertEquals("IMAGE_QUEUE_FULL", imageMapper.latestImage().getErrorMessage());
        verify(notificationService).publishShareSummaryImageFailed(
                any(ShareSummaryRunRecord.class),
                any(ShareSummaryImageRecord.class),
                eq("RejectedExecutionException"),
                eq("IMAGE_QUEUE_FULL")
        );
    }

    @Test
    void imageFailureNotificationIncludesErrorTypeAndFallbackMessage() {
        FakeImageMapper imageMapper = new FakeImageMapper(config());
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        NotificationService notificationService = mock(NotificationService.class);
        ShareSummaryImageClient imageClient = mock(ShareSummaryImageClient.class);
        try {
            when(imageClient.generate(any(ShareSummaryImageConfigRecord.class), any(String.class)))
                    .thenThrow(new IOException());
        } catch (InterruptedException | IOException exception) {
            throw new IllegalStateException(exception);
        }
        ShareSummaryImageService service = new ShareSummaryImageService(
                imageMapper,
                shareSummaryMapper,
                imageClient,
                new RedirectingImageHttpClient(),
                new DirectExecutorService(),
                notificationService,
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );

        service.generateImage(1, true);

        assertEquals("Image generation failed.", imageMapper.latestImage().getErrorMessage());
        verify(notificationService).publishShareSummaryImageFailed(
                any(ShareSummaryRunRecord.class),
                any(ShareSummaryImageRecord.class),
                eq("IOException"),
                eq("Image generation failed.")
        );
    }

    @Test
    void generatesPeriodSpecificOgTitles() {
        ShareSummaryImageService service = new ShareSummaryImageService(
                new FakeImageMapper(config()),
                new FakeShareSummaryMapper(successfulRun()),
                new ShareSummaryImageClient(new ImageProviderHttpClient(200, "{\"data\":[]}"), new com.fasterxml.jackson.databind.ObjectMapper()),
                new RedirectingImageHttpClient(),
                new DirectExecutorService(),
                null,
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );

        ShareSummaryRunRecord daily = run("DAILY", "2026-05-29", "2026-05-30");
        ShareSummaryRunRecord weekly = run("WEEKLY", "2026-05-25", "2026-06-01");
        ShareSummaryRunRecord monthly = run("MONTHLY", "2026-05-01", "2026-06-01");

        assertEquals("LinkPeek - 2026-05-29 日报", service.ogTitle(daily));
        assertEquals("LinkPeek - 2026年第22周周报", service.ogTitle(weekly));
        assertEquals("LinkPeek - 2026年5月月报", service.ogTitle(monthly));
    }

    @Test
    void acceptsAutoImageSizeInConfig() {
        ShareSummaryImageService service = new ShareSummaryImageService(
                new FakeImageMapper(config()),
                new FakeShareSummaryMapper(successfulRun()),
                new ShareSummaryImageClient(new ImageProviderHttpClient(200, "{\"data\":[]}"), new com.fasterxml.jackson.databind.ObjectMapper()),
                new RedirectingImageHttpClient(),
                new DirectExecutorService(),
                null,
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );

        assertDoesNotThrow(() -> service.updateConfig(new ShareSummaryImageService.ConfigRequest(
                true,
                false,
                "OPENAI_COMPATIBLE",
                "https://api.example.com",
                "/api-proxy/images/generations",
                "sk-test",
                "gpt-image-2",
                "auto",
                "auto",
                "png",
                "style",
                300
        )));
    }

    @Test
    void acceptsExtendedRequestTimeoutInConfig() {
        FakeImageMapper imageMapper = new FakeImageMapper(config());
        ShareSummaryImageService service = new ShareSummaryImageService(
                imageMapper,
                new FakeShareSummaryMapper(successfulRun()),
                new ShareSummaryImageClient(new ImageProviderHttpClient(200, "{\"data\":[]}"), new com.fasterxml.jackson.databind.ObjectMapper()),
                new RedirectingImageHttpClient(),
                new DirectExecutorService(),
                null,
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );

        ShareSummaryImageService.ConfigResponse response = service.updateConfig(new ShareSummaryImageService.ConfigRequest(
                true,
                false,
                "OPENAI_COMPATIBLE",
                "https://api.example.com",
                "/api-proxy/images/generations",
                "sk-test",
                "gpt-image-2",
                "auto",
                "auto",
                "png",
                "style",
                1800
        ));

        assertEquals(1800, response.requestTimeoutSeconds());
        assertEquals(1800, imageMapper.config.getRequestTimeoutSeconds());
    }

    @Test
    void deleteImagesForRunRemovesStoredFilesAndRows() throws Exception {
        FakeImageMapper imageMapper = new FakeImageMapper(config());
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setCacheDir(cacheDir);
        ShareSummaryImageService service = new ShareSummaryImageService(
                imageMapper,
                new FakeShareSummaryMapper(successfulRun()),
                new ShareSummaryImageClient(new ImageProviderHttpClient(200, "{\"data\":[]}"), new com.fasterxml.jackson.databind.ObjectMapper()),
                new RedirectingImageHttpClient(),
                new DirectExecutorService(),
                null,
                properties,
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );
        ShareSummaryImageRecord image = existingSuccessfulImage();
        image.setId(77L);
        image.setStorageKey("share-summary/images/1/77.png");
        imageMapper.images.add(image);
        Path imagePath = cacheDir.resolve(image.getStorageKey());
        Files.createDirectories(imagePath.getParent());
        Files.write(imagePath, new byte[]{1, 2, 3});

        int deleted = service.deleteImagesForRun(1L);

        assertEquals(1, deleted);
        assertTrue(Files.notExists(imagePath));
        assertTrue(imageMapper.selectImagesForRun(1L).isEmpty());
    }

    private ShareSummaryImageConfigRecord config() {
        ShareSummaryImageConfigRecord config = new ShareSummaryImageConfigRecord();
        config.setEnabled(true);
        config.setAutoGenerate(true);
        config.setProviderType("OPENAI_COMPATIBLE");
        config.setBaseUrl("https://api.example.com");
        config.setEndpointPath("/v1/images/generations");
        config.setApiKey("sk-test");
        config.setModel("test-image");
        config.setImageSize("1200x630");
        config.setQuality("auto");
        config.setOutputFormat("png");
        config.setStylePrompt("style");
        config.setRequestTimeoutSeconds(7);
        return config;
    }

    private ShareSummaryRunRecord successfulRun() {
        ShareSummaryRunRecord run = run("DAILY", "2026-05-29", "2026-05-30");
        run.setId(1L);
        run.setStatus("SUCCESS");
        run.setReport("报告内容");
        return run;
    }

    private ShareSummaryRunRecord run(String periodType, String startDate, String endDate) {
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setId(1L);
        run.setPeriodType(periodType);
        run.setWindowStart(LocalDate.parse(startDate).atStartOfDay(ZONE).toInstant().toEpochMilli());
        run.setWindowEnd(LocalDate.parse(endDate).atStartOfDay(ZONE).toInstant().toEpochMilli());
        run.setStatus("SUCCESS");
        run.setReport("报告内容");
        return run;
    }

    private ShareSummaryImageRecord existingSuccessfulImage() {
        ShareSummaryImageRecord image = new ShareSummaryImageRecord();
        image.setId(1L);
        image.setRunId(1);
        image.setAttemptNo(1);
        image.setStatus(ShareSummaryImageStatus.SUCCESS.name());
        image.setProviderType("OPENAI_COMPATIBLE");
        image.setModel("test-image");
        image.setImageSize("1200x630");
        image.setOutputFormat("png");
        image.setQuality("auto");
        image.setStylePromptSnapshot("style");
        image.setPromptSnapshot("prompt");
        image.setPublicToken("existing-token");
        image.setImageUrl("https://linkpeek.example.com/share-summary/og-images/existing-token.png");
        image.setOgImageUrl("https://linkpeek.example.com/share-summary/og-images/existing-token.png");
        image.setOgPageUrl("https://linkpeek.example.com/share-summary/reports/existing-token");
        image.setOgTitle("LinkPeek - 2026-05-29 日报");
        image.setOgDescription("本报告汇总了 2026-05-29 至 2026-05-30 的链接分享与内容洞察。");
        image.setCreatedAt(1);
        return image;
    }

    private static final class FakeImageMapper implements ShareSummaryImageMapper {
        private ShareSummaryImageConfigRecord config;
        private final List<ShareSummaryImageRecord> images = new ArrayList<>();
        private long nextImageId = 10;

        private FakeImageMapper(ShareSummaryImageConfigRecord config) {
            this.config = config;
        }

        @Override
        public ShareSummaryImageConfigRecord selectConfig() {
            return config;
        }

        @Override
        public int upsertConfig(ShareSummaryImageConfigRecord config) {
            this.config = config;
            return 1;
        }

        @Override
        public void insertImage(ShareSummaryImageRecord image) {
            image.setId(nextImageId++);
            images.add(image);
        }

        @Override
        public int updateImage(ShareSummaryImageRecord image) {
            return 1;
        }

        @Override
        public ShareSummaryImageRecord selectImage(long id) {
            return images.stream()
                    .filter(image -> image.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public ShareSummaryImageRecord selectImageByPublicToken(String publicToken) {
            return images.stream()
                    .filter(image -> publicToken.equals(image.getPublicToken()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public ShareSummaryImageRecord selectLatestImage(long runId) {
            return images.stream()
                    .filter(image -> image.getRunId() == runId)
                    .max(Comparator.comparingInt(ShareSummaryImageRecord::getAttemptNo))
                    .orElse(null);
        }

        @Override
        public ShareSummaryImageRecord selectLatestSuccessfulImage(long runId) {
            return images.stream()
                    .filter(image -> image.getRunId() == runId)
                    .filter(image -> ShareSummaryImageStatus.SUCCESS.name().equals(image.getStatus()))
                    .max(Comparator.comparingInt(ShareSummaryImageRecord::getAttemptNo))
                    .orElse(null);
        }

        @Override
        public ShareSummaryImageRecord selectActiveImage(long runId) {
            return images.stream()
                    .filter(image -> image.getRunId() == runId)
                    .filter(image -> ShareSummaryImageStatus.PENDING.name().equals(image.getStatus())
                            || ShareSummaryImageStatus.GENERATING.name().equals(image.getStatus()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int selectNextAttemptNo(long runId) {
            return images.stream()
                    .filter(image -> image.getRunId() == runId)
                    .mapToInt(ShareSummaryImageRecord::getAttemptNo)
                    .max()
                    .orElse(0) + 1;
        }

        @Override
        public List<ShareSummaryImageRecord> selectImagesForRun(long runId) {
            return images.stream()
                    .filter(image -> image.getRunId() == runId)
                    .sorted(Comparator.comparingInt(ShareSummaryImageRecord::getAttemptNo).reversed())
                    .toList();
        }

        @Override
        public int deleteImagesForRun(long runId) {
            int before = images.size();
            images.removeIf(image -> image.getRunId() == runId);
            return before - images.size();
        }

        private ShareSummaryImageRecord latestImage() {
            return selectLatestImage(1);
        }
    }

    private static final class FakeShareSummaryMapper implements ShareSummaryMapper {
        private final ShareSummaryRunRecord run;

        private FakeShareSummaryMapper(ShareSummaryRunRecord run) {
            this.run = run;
        }

        @Override
        public List<ShareSummaryTaskRecord> selectTasks() {
            return List.of();
        }

        @Override
        public List<ShareSummaryTaskRecord> selectEnabledTasks() {
            return List.of();
        }

        @Override
        public ShareSummaryTaskRecord selectTask(long id) {
            return null;
        }

        @Override
        public void insertTask(ShareSummaryTaskRecord task) {
        }

        @Override
        public int updateTask(ShareSummaryTaskRecord task) {
            return 0;
        }

        @Override
        public int deleteTask(long id, long deletedAt) {
            return 0;
        }

        @Override
        public void insertRun(ShareSummaryRunRecord run) {
        }

        @Override
        public int updateRun(ShareSummaryRunRecord run) {
            return 0;
        }

        @Override
        public int markStaleRunningRunsFailed(long threshold, long finishedAt) {
            return 0;
        }

        @Override
        public ShareSummaryRunRecord selectRun(long id) {
            return run;
        }

        @Override
        public int deleteRun(long id) {
            return 0;
        }

        @Override
        public ShareSummaryRunRecord selectLatestCompletedScheduledRun(long taskId) {
            return null;
        }

        @Override
        public ShareSummaryRunRecord selectScheduledRunForWindow(long taskId, long windowStart, long windowEnd) {
            return null;
        }

        @Override
        public long countRuns(Long taskId, String status, String triggerType) {
            return 0;
        }

        @Override
        public List<ShareSummaryRunRecord> selectRuns(Long taskId, String status, String triggerType, int limit, int offset) {
            return List.of();
        }
    }

    private static final class ImageProviderHttpClient extends StubHttpClient {
        private final int statusCode;
        private final String responseBody;

        private ImageProviderHttpClient(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new StubHttpResponse(
                    request.uri(),
                    statusCode,
                    responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Map.of("Content-Type", List.of("application/json"))
            );
            return response;
        }
    }

    private static final class RedirectingImageHttpClient extends StubHttpClient {
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new StubHttpResponse(
                    request.uri(),
                    302,
                    new byte[0],
                    Map.of("Location", List.of("http://127.0.0.1/private.png"))
            );
            return response;
        }
    }

    private abstract static class StubHttpClient extends HttpClient {
        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
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

    private record StubHttpResponse(
            URI uri,
            int statusCode,
            byte[] body,
            Map<String, List<String>> headerValues
    ) implements HttpResponse<byte[]> {
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
            return HttpHeaders.of(headerValues, (left, right) -> true);
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

    private static class DirectExecutorService implements ExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout,
                TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout,
                TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class RejectingExecutorService extends DirectExecutorService {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("queue full");
        }
    }
}
