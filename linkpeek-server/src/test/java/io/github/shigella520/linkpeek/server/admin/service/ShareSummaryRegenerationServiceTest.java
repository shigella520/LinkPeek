package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.*;
import io.github.shigella520.linkpeek.server.admin.persistence.*;
import io.github.shigella520.linkpeek.server.ai.*;
import io.github.shigella520.linkpeek.server.config.*;
import io.github.shigella520.linkpeek.server.controller.ShareSummaryPublicController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQLite, mapper XML, transactions, file assets and services; only upstream AI and execution timing are controlled. */
class ShareSummaryRegenerationServiceTest {
    @TempDir Path directory;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    JdbcTemplate jdbc;
    ShareSummaryMapper runs;
    ShareSummaryImageMapper imageMapper;
    ShareSummaryAudioMapper audioMapper;
    NotificationMapper notificationMapper;
    ShareSummaryVersionStore versions;
    ShareSummaryService summaries;
    ShareSummaryImageService images;
    ShareSummaryAudioService audios;
    ShareSummaryRegenerationService regeneration;
    OpenAiCompatibleTextClient textClient;
    OpenAiCompatibleImageClient imageClient;
    OpenAiCompatibleAudioClient audioClient;
    NotificationService notifications;
    ShareSummaryLinkMapper links;
    QueueExecutor textQueue = new QueueExecutor(), imageQueue = new QueueExecutor(), audioQueue = new QueueExecutor();
    long rootId;
    ShareSummaryImageRecord originalImage;
    ShareSummaryAudioRecord originalAudio;
    AtomicInteger textCalls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        SQLiteConfig config = new SQLiteConfig(); config.setBusyTimeout(5000); config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("test.db"));
        new StatisticsConfiguration.StatisticsSchemaInitializer(dataSource,
                new ResourceDatabasePopulator(new ClassPathResource("db/stats-schema.sql")));
        jdbc = new JdbcTemplate(dataSource);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        org.apache.ibatis.session.Configuration mapping = new org.apache.ibatis.session.Configuration();
        mapping.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(mapping);
        factory.setMapperLocations(new ClassPathResource("mapper/ShareSummaryMapper.xml"),
                new ClassPathResource("mapper/ShareSummaryImageMapper.xml"), new ClassPathResource("mapper/ShareSummaryAudioMapper.xml"),
                new ClassPathResource("mapper/NotificationMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        runs = session.getMapper(ShareSummaryMapper.class);
        imageMapper = session.getMapper(ShareSummaryImageMapper.class);
        audioMapper = session.getMapper(ShareSummaryAudioMapper.class);
        notificationMapper = session.getMapper(NotificationMapper.class);
        versions = new ShareSummaryVersionStore(jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper(), runs, imageMapper, audioMapper, clock);
        jdbc.update("""
                INSERT INTO share_summary_image_config(id, enabled, auto_generate, base_url, api_key, model)
                VALUES (1, 1, 0, 'https://image.example.com', 'image-secret', 'image-model')
                """);
        jdbc.update("""
                INSERT INTO share_summary_audio_config(id, enabled, auto_generate, base_url, api_key, model)
                VALUES (1, 1, 0, 'https://audio.example.com', 'audio-secret', 'audio-model')
                """);
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setBaseUrl("https://linkpeek.example.com"); properties.setCacheDir(directory);
        notifications = mock(NotificationService.class);
        imageClient = mock(OpenAiCompatibleImageClient.class);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        when(imageClient.generate(any(), anyString())).thenReturn(new OpenAiCompatibleImageClient.ImageGenerationResult(
                Base64.getEncoder().encodeToString(png.toByteArray()), null, "{}", 10));
        audioClient = mock(OpenAiCompatibleAudioClient.class);
        when(audioClient.supports(anyString())).thenReturn(true);
        when(audioClient.generate(any(), anyString())).thenReturn(new ShareSummaryAudioProvider.AudioGenerationResult(new byte[]{1, 2, 3}, "{}", 10));
        images = new ShareSummaryImageService(imageMapper, runs, imageClient, HttpClient.newHttpClient(), imageQueue, notifications, properties, clock);
        audios = new ShareSummaryAudioService(audioMapper, runs, imageMapper, audioClient, null, audioQueue, notifications, properties, clock);
        images.setVersionStore(versions); audios.setVersionStore(versions);
        textClient = mock(OpenAiCompatibleTextClient.class);
        when(textClient.generateTextResult(any(), any())).thenAnswer(invocation ->
                new OpenAiCompatibleTextClient.TextResult(Optional.of("new report " + textCalls.incrementAndGet()), 10));
        AiProviderMapper providers = mock(AiProviderMapper.class);
        AiProviderRecord provider = new AiProviderRecord();
        provider.setId(1L); provider.setName("text-provider"); provider.setEnabled(true); provider.setModel("text-model");
        provider.setBaseUrl("https://text.example.com"); provider.setApiKey("text-secret"); provider.setRequestTimeoutSeconds(10);
        when(providers.selectEnabledProviders()).thenReturn(List.of(provider));
        links = mock(ShareSummaryLinkMapper.class);
        ShareSummaryLinkRow link = new ShareSummaryLinkRow(); link.setTitle("current link title"); link.setCanonicalUrl("https://example.com");
        when(links.selectSummaryLinks(anyLong(), anyLong())).thenReturn(List.of(link));
        when(links.countCreatedEvents(anyLong(), anyLong())).thenReturn(1);
        summaries = new ShareSummaryService(runs, links, providers, textClient, images, audios, null, clock);
        summaries.setVersionStore(versions);
        ShareSummaryTaskRecord task = summaries.createTask(new ShareSummaryService.TaskRequest("日报", true, "DAILY", "CURRENT", "09:00", null, "current prompt", 100, 1));
        ShareSummaryRunRecord original = new ShareSummaryRunRecord();
        original.setTaskId(task.getId()); original.setTaskName(task.getName()); original.setPeriodType("DAILY");
        original.setTriggerType("SCHEDULED"); original.setStatus("SUCCESS"); original.setReport("original report");
        original.setPromptSnapshot("original prompt"); original.setStartedAt(clock.millis() - 1000);
        original.setWindowStart(clock.millis() - 86_400_000); original.setWindowEnd(clock.millis() - 43_200_000);
        runs.insertRun(original); rootId = original.getId();
        images.generateImage(rootId, false); audios.generateAudio(rootId, false);
        imageQueue.next(); audioQueue.next();
        originalImage = imageMapper.selectLatestSuccessfulImage(rootId); originalAudio = audioMapper.selectLatestSuccessfulAudio(rootId);
        assertNotNull(originalImage); assertNotNull(originalAudio);
        clearInvocations(notifications, imageClient, audioClient);
        regeneration = new ShareSummaryRegenerationService(versions, summaries, images, audios, runs, imageMapper, audioMapper, clock, textQueue);
    }

    @AfterEach void stop() { if (regeneration != null) regeneration.shutdown(); }

    @Test void publishedVersionNotificationIsVisibleToImmediateDeliveryWorker() throws Exception {
        jdbc.update("""
                INSERT INTO notification_channel(id, name, enabled, type, url, method, timeout_seconds, created_at, updated_at)
                VALUES (1, 'test webhook', 1, 'WEBHOOK', 'https://192.0.2.1/webhook', 'POST', 10, 0, 0)
                """);
        jdbc.update("""
                INSERT INTO notification_task(id, name, enabled, event_type, template_json, created_at, updated_at)
                VALUES (1, 'summary', 1, 'SHARE_SUMMARY_IMAGE_SUCCESS', '{"url":"{{image.ogPageUrl}}"}', 0, 0)
                """);
        jdbc.update("INSERT INTO notification_task_channel(task_id, channel_id) VALUES (1, 1)");
        HttpClient webhookClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("ok");
        when(webhookClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService immediateWorker = mock(ExecutorService.class);
        // Force the worker to read on a separate connection before the submitting thread can clean up its transaction.
        doAnswer(invocation -> {
            worker.submit((Runnable) invocation.getArgument(0)).get(10, TimeUnit.SECONDS);
            return null;
        }).when(immediateWorker).execute(any(Runnable.class));
        ObjectMapper json = new ObjectMapper();
        NotificationService realNotifications = new NotificationService(notificationMapper, new NotificationTemplateService(json),
                json, webhookClient, immediateWorker, new LinkPeekProperties(), clock);
        doAnswer(invocation -> {
            realNotifications.publishShareSummaryImageSuccess(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(notifications).publishShareSummaryImageSuccess(any(), any());
        try {
            var revision = regeneration.regenerate(rootId, "notification-race");
            textQueue.next(); imageQueue.next(); regeneration.reconcile();
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
            audioQueue.next(); regeneration.reconcile(); regeneration.reconcile();

            assertEquals(revision.runId(), versions.publishedRunId(rootId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
            var delivery = notificationMapper.selectDelivery(jdbc.queryForObject("SELECT id FROM notification_delivery", Long.class));
            assertEquals("SUCCESS", delivery.getStatus());
            assertEquals(1, delivery.getAttemptCount());
            assertEquals(200, delivery.getResponseStatus());
            assertTrue(delivery.getRequestBody().contains(originalImage.getOgPageUrl()));
            verify(webhookClient, times(1)).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test void publishesOnlyCompleteVersionAndKeepsOldUrlsHistoryAndWindow() throws Exception {
        var revision = regeneration.regenerate(rootId, "request-1");
        assertEquals(revision.runId(), regeneration.regenerate(rootId, "request-1").runId());
        assertThrows(IllegalStateException.class, () -> regeneration.regenerate(rootId, "request-2"));
        assertThrows(IllegalStateException.class, () -> images.generateImage(rootId, true));
        assertThrows(IllegalStateException.class, () -> summaries.deleteRun(rootId));
        assertEquals(1, summaries.runs(1, 20, null, null, null).total());
        assertEquals(rootId, images.publicReport(originalImage.getPublicToken()).run().getId());
        textQueue.next();
        assertEquals("ASSETS", versions.revision(revision.runId()).status());
        assertEquals(1, imageQueue.jobs.size()); assertEquals(1, audioQueue.jobs.size());
        ShareSummaryRunRecord candidate = runs.selectRun(revision.runId());
        assertEquals(runs.selectRun(rootId).getWindowStart(), candidate.getWindowStart());
        assertEquals("current prompt", candidate.getPromptSnapshot());
        assertEquals("original report", images.publicReport(originalImage.getPublicToken()).run().getReport());
        imageQueue.next(); regeneration.reconcile();
        assertEquals(rootId, versions.publishedRunId(rootId));
        verify(notifications, never()).publishShareSummaryImageSuccess(any(), any());
        audioQueue.next(); regeneration.reconcile(); regeneration.reconcile();
        assertEquals(revision.runId(), versions.publishedRunId(rootId));
        assertEquals("SUCCESS", versions.revision(revision.runId()).status());
        assertEquals("new report 1", summaries.run(rootId).getReport());
        assertEquals(rootId, summaries.run(rootId).getId());
        assertEquals("SCHEDULED", summaries.run(rootId).getTriggerType());
        assertEquals(originalImage.getOgPageUrl(), summaries.run(rootId).getOgPageUrl());
        var page = images.publicReport(originalImage.getPublicToken());
        assertEquals(revision.runId(), page.image().getRunId()); assertEquals(revision.runId(), page.run().getId());
        assertNotNull(images.publicImage(originalImage.getPublicToken(), "png"));
        assertNotNull(audios.publicAudio(versions.audioToken(originalAudio.getId()), "mp3"));
        String html = new ShareSummaryPublicController(images, audios).report(originalImage.getPublicToken()).getBody();
        assertTrue(html.contains("new report 1"));
        assertTrue(html.contains(audioMapper.selectLatestSuccessfulAudio(revision.runId()).getAudioUrl()));
        var history = regeneration.history(rootId);
        assertEquals(2, history.size()); assertTrue(history.get(0).published());
        assertEquals("original report", history.get(1).run().getReport());
        verify(notifications, times(1)).publishShareSummaryImageSuccess(any(), any());
        var audio = audioMapper.selectLatestSuccessfulAudio(revision.runId());
        assertTrue(audio.getTextSnapshot().contains("new report 1"));
        assertTrue(audio.getTextSnapshot().startsWith(revision.title() + "\n" + revision.description()));
        assertFalse(versions.revision(revision.runId()).inputSnapshot().contains("text-secret"));
        assertFalse(versions.revision(revision.runId()).imageConfigSnapshot().contains("image-secret"));
        assertFalse(versions.revision(revision.runId()).audioConfigSnapshot().contains("audio-secret"));
    }

    @Test void retriesOnlyFailedAudioAndPreservesOriginalIdempotencyKey() throws Exception {
        when(audioClient.generate(any(), anyString())).thenThrow(new IOException("audio failed"))
                .thenReturn(new ShareSummaryAudioProvider.AudioGenerationResult(new byte[]{4}, "{}", 10));
        var revision = regeneration.regenerate(rootId, "first");
        textQueue.next(); imageQueue.next(); audioQueue.next(); regeneration.reconcile();
        assertEquals("PARTIAL_FAILED", versions.revision(revision.runId()).status());
        assertEquals(rootId, versions.publishedRunId(rootId));
        regeneration.retry(rootId, revision.runId(), "retry");
        assertEquals(revision.runId(), regeneration.retry(rootId, revision.runId(), "retry").runId());
        assertEquals(revision.runId(), regeneration.regenerate(rootId, "first").runId());
        textQueue.next();
        assertEquals(1, textCalls.get()); assertTrue(imageQueue.jobs.isEmpty());
        assertEquals(1, imageMapper.selectImagesForRun(revision.runId()).size());
        audioQueue.next(); regeneration.reconcile();
        assertEquals(revision.runId(), versions.publishedRunId(rootId));
        assertEquals(2, audioMapper.selectAudiosForRun(revision.runId()).size());
        verify(imageClient, times(1)).generate(any(), anyString());
    }

    @Test void textFailureDoesNotStartAssetsAndCanBeRetried() throws Exception {
        when(textClient.generateTextResult(any(), any())).thenThrow(new IOException("text failed"))
                .thenReturn(new OpenAiCompatibleTextClient.TextResult(Optional.of("retried text"), 10));
        var revision = regeneration.regenerate(rootId, "first"); textQueue.next();
        assertEquals("FAILED", versions.revision(revision.runId()).status());
        assertTrue(imageQueue.jobs.isEmpty()); assertTrue(audioQueue.jobs.isEmpty());
        assertEquals("original report", summaries.run(rootId).getReport());
        regeneration.retry(rootId, revision.runId(), "retry"); textQueue.next(); imageQueue.next(); audioQueue.next(); regeneration.reconcile();
        assertEquals("retried text", summaries.run(rootId).getReport());
    }

    @Test void emptyInputAndDisabledConfigurationNeverReplacePublishedVersion() {
        jdbc.update("UPDATE share_summary_audio_config SET enabled = 0");
        assertThrows(IllegalArgumentException.class, () -> regeneration.regenerate(rootId, "disabled"));
        assertTrue(versions.revisions(rootId).isEmpty());
        jdbc.update("UPDATE share_summary_audio_config SET enabled = 1");
        when(links.selectSummaryLinks(anyLong(), anyLong())).thenReturn(List.of());
        var revision = regeneration.regenerate(rootId, "empty"); textQueue.next();
        assertEquals("EMPTY", versions.revision(revision.runId()).status());
        assertEquals(0, textCalls.get()); assertTrue(imageQueue.jobs.isEmpty());
        assertEquals(rootId, versions.publishedRunId(rootId));
    }

    @Test void lateTextResultCannotPublishAfterTimeoutOrOverwriteNewerVersion() throws Exception {
        when(textClient.generateTextResult(any(), any())).thenAnswer(invocation -> {
            var active = versions.latest(rootId);
            jdbc.update("UPDATE share_summary_revision SET deadline = 0 WHERE run_id = ?", active.runId());
            regeneration.reconcile();
            assertEquals("FAILED", versions.revision(active.runId()).status());
            regeneration.regenerate(rootId, "newer");
            return new OpenAiCompatibleTextClient.TextResult(Optional.of("late old text"), 10);
        });
        var old = regeneration.regenerate(rootId, "old"); textQueue.next();
        assertEquals("FAILED", runs.selectRun(old.runId()).getStatus());
        assertTrue(imageQueue.jobs.isEmpty());
        doReturn(new OpenAiCompatibleTextClient.TextResult(Optional.of("newer text"), 10)).when(textClient).generateTextResult(any(), any());
        textQueue.next(); imageQueue.next(); audioQueue.next(); regeneration.reconcile();
        assertEquals("newer text", summaries.run(rootId).getReport());
        assertThrows(IllegalArgumentException.class, () -> regeneration.retry(rootId, old.runId(), "old-retry"));
    }

    @Test void queueRejectionIsPersistedAfterAdmissionCommit() {
        textQueue.reject = true;
        var revision = regeneration.regenerate(rootId, "queue-full");
        assertEquals("FAILED", versions.revision(revision.runId()).status());
        assertEquals("FAILED", runs.selectRun(revision.runId()).getStatus());
        assertEquals(rootId, versions.publishedRunId(rootId));
    }

    @Test void singleAssetOperationTargetsPublishedVersionAndDeletionRemovesAllVersions() {
        var revision = regeneration.regenerate(rootId, "first"); textQueue.next(); imageQueue.next(); audioQueue.next(); regeneration.reconcile();
        var image = images.generateImage(rootId, true);
        assertEquals(revision.runId(), image.runId()); imageQueue.next();
        assertEquals(1, imageMapper.selectImagesForRun(rootId).size());
        assertEquals(2, images.images(rootId).size());
        assertThrows(IllegalArgumentException.class, () -> images.generateImage(revision.runId(), true));
        summaries.deleteRun(rootId);
        assertNull(runs.selectRun(rootId)); assertNull(runs.selectRun(revision.runId()));
        assertTrue(versions.revisions(rootId).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> images.publicReport(originalImage.getPublicToken()));
    }

    @Test void concurrentDuplicateRequestsCreateOneCandidateAndOneQueuedJob() throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Long> request = () -> { start.await(); return regeneration.regenerate(rootId, "same-key").runId(); };
            Future<Long> first = clients.submit(request), second = clients.submit(request); start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, versions.revisions(rootId).size()); assertEquals(1, textQueue.jobs.size());
        } finally { clients.shutdownNow(); }
    }

    @Test void audioCanFinishBeforeImageAndEveryNewAudioUrlRemainsImmutable() throws Exception {
        var revision = regeneration.regenerate(rootId, "first"); textQueue.next(); audioQueue.next();
        var firstAudio = audioMapper.selectLatestSuccessfulAudio(revision.runId());
        String token = versions.audioToken(firstAudio.getId());
        assertNotNull(firstAudio.getAudioUrl());
        assertArrayEquals(new byte[]{1, 2, 3}, audios.publicAudio(token, "mp3").resource().getInputStream().readAllBytes());
        assertEquals(rootId, versions.publishedRunId(rootId));
        imageQueue.next(); regeneration.reconcile();
        when(audioClient.generate(any(), anyString())).thenReturn(new ShareSummaryAudioProvider.AudioGenerationResult(new byte[]{9}, "{}", 10));
        var next = audios.generateAudio(rootId, true); audioQueue.next();
        assertNotEquals(firstAudio.getAudioUrl(), audioMapper.selectAudio(next.id()).getAudioUrl());
        assertArrayEquals(new byte[]{1, 2, 3}, audios.publicAudio(token, "mp3").resource().getInputStream().readAllBytes());
        assertArrayEquals(new byte[]{9}, audios.publicAudio(versions.audioToken(next.id()), "mp3").resource().getInputStream().readAllBytes());
        // Existing image-token audio links remain accepted after upgrading.
        assertArrayEquals(new byte[]{1, 2, 3}, audios.publicAudio(originalImage.getPublicToken(), "mp3").resource().getInputStream().readAllBytes());
    }

    @Test void expiredAssetWorkersCannotResurrectAttemptsAndRetryReusesText() throws Exception {
        var revision = regeneration.regenerate(rootId, "first"); textQueue.next();
        jdbc.update("UPDATE share_summary_revision SET deadline = 0 WHERE run_id = ?", revision.runId());
        regeneration.reconcile();
        assertEquals("FAILED", imageMapper.selectLatestImage(revision.runId()).getStatus());
        assertEquals("FAILED", audioMapper.selectLatestAudio(revision.runId()).getStatus());
        regeneration.retry(rootId, revision.runId(), "retry"); textQueue.next();
        imageQueue.next(); audioQueue.next(); // Old work was queued before the simulated restart/timeout.
        verify(imageClient, never()).generate(any(), anyString());
        verify(audioClient, never()).generate(any(), anyString());
        imageQueue.next(); audioQueue.next(); regeneration.reconcile();
        assertEquals(1, textCalls.get()); assertEquals(revision.runId(), versions.publishedRunId(rootId));
    }

    @Test void snapshotsAreUsedEvenWhenTaskAndAssetConfigurationChangeWhileQueued() throws Exception {
        var revision = regeneration.regenerate(rootId, "first");
        jdbc.update("UPDATE share_summary_task SET prompt = 'changed prompt'");
        jdbc.update("UPDATE share_summary_image_config SET model = 'changed image model', api_key = 'rotated-image-secret'");
        jdbc.update("UPDATE share_summary_audio_config SET voice = 'changed voice'");
        textQueue.next(); imageQueue.next(); audioQueue.next(); regeneration.reconcile();
        assertEquals("current prompt", runs.selectRun(revision.runId()).getPromptSnapshot());
        var imageConfig = org.mockito.ArgumentCaptor.forClass(ShareSummaryImageConfigRecord.class);
        verify(imageClient).generate(imageConfig.capture(), anyString());
        assertEquals("image-model", imageConfig.getValue().getModel());
        assertEquals("rotated-image-secret", imageConfig.getValue().getApiKey());
        var audioConfig = org.mockito.ArgumentCaptor.forClass(ShareSummaryAudioConfigRecord.class);
        verify(audioClient).generate(audioConfig.capture(), anyString());
        assertNotEquals("changed voice", audioConfig.getValue().getVoice());
    }

    @Test void assetQueueRejectionLeavesSuccessfulTextAvailableForRetry() {
        imageQueue.reject = true;
        var revision = regeneration.regenerate(rootId, "first"); textQueue.next(); audioQueue.next(); regeneration.reconcile();
        assertEquals("PARTIAL_FAILED", versions.revision(revision.runId()).status());
        assertEquals("FAILED", imageMapper.selectLatestImage(revision.runId()).getStatus());
        assertEquals("SUCCESS", runs.selectRun(revision.runId()).getStatus());
        imageQueue.reject = false;
        regeneration.retry(rootId, revision.runId(), "retry"); textQueue.next(); imageQueue.next(); regeneration.reconcile();
        assertTrue(audioQueue.jobs.isEmpty()); assertEquals(1, textCalls.get());
        assertEquals(revision.runId(), versions.publishedRunId(rootId));
    }

    @Test void filtersUsePublishedStatusButRegenerationDoesNotAdvanceScheduledWindow() {
        jdbc.update("UPDATE share_summary_run SET status = 'FAILED' WHERE id = ?", rootId);
        var revision = regeneration.regenerate(rootId, "first"); textQueue.next(); imageQueue.next(); audioQueue.next(); regeneration.reconcile();
        assertEquals(revision.runId(), versions.publishedRunId(rootId));
        assertEquals(1, summaries.runs(1, 20, null, "SUCCESS", null).total());
        assertEquals(0, summaries.runs(1, 20, null, "FAILED", null).total());
        assertNull(runs.selectLatestCompletedScheduledRun(runs.selectRun(rootId).getTaskId()));
    }

    @Test void deletedTaskIsRejectedBeforeAnyGeneration() {
        summaries.deleteTask(runs.selectRun(rootId).getTaskId());
        assertThrows(IllegalArgumentException.class, () -> regeneration.regenerate(rootId, "first"));
        assertTrue(versions.revisions(rootId).isEmpty()); assertTrue(textQueue.jobs.isEmpty());
    }

    static class QueueExecutor extends AbstractExecutorService {
        final Queue<Runnable> jobs = new ConcurrentLinkedQueue<>();
        boolean reject;
        void next() { Objects.requireNonNull(jobs.poll(), "expected queued work").run(); }
        @Override public void execute(Runnable command) { if (reject) throw new RejectedExecutionException(); jobs.add(command); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
