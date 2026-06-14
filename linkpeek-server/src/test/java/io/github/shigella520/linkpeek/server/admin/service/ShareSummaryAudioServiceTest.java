package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioStatus;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageConfigRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryTaskRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryAudioMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryImageMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ShareSummaryMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareSummaryAudioServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void publishesAudioFailedNotificationWhenGenerationThrows() throws Exception {
        FakeAudioMapper audioMapper = new FakeAudioMapper(config());
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        ShareSummaryAudioClient audioClient = mock(ShareSummaryAudioClient.class);
        when(audioClient.supports(any(String.class))).thenReturn(true);
        when(audioClient.generate(any(ShareSummaryAudioConfigRecord.class), any(String.class)))
                .thenThrow(new IOException("provider failed"));
        NotificationService notificationService = mock(NotificationService.class);
        ShareSummaryAudioService service = service(audioMapper, shareSummaryMapper, audioClient, new DirectExecutorService(), notificationService);

        service.generateAudio(1, true);

        assertEquals(ShareSummaryAudioStatus.FAILED.name(), audioMapper.latestAudio().getStatus());
        assertTrue(audioMapper.latestAudio().getErrorMessage().contains("provider failed"));
        verify(notificationService).publishShareSummaryAudioFailed(
                any(ShareSummaryRunRecord.class),
                any(ShareSummaryAudioRecord.class),
                eq("IOException"),
                eq("provider failed")
        );
    }

    @Test
    void publishesAudioFailedNotificationWhenQueueRejectsGeneration() {
        FakeAudioMapper audioMapper = new FakeAudioMapper(config());
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        NotificationService notificationService = mock(NotificationService.class);
        ShareSummaryAudioClient audioClient = mock(ShareSummaryAudioClient.class);
        when(audioClient.supports(any(String.class))).thenReturn(true);
        ShareSummaryAudioService service = service(
                audioMapper,
                shareSummaryMapper,
                audioClient,
                new RejectingExecutorService(),
                notificationService
        );

        service.generateAudio(1, true);

        assertEquals(ShareSummaryAudioStatus.FAILED.name(), audioMapper.latestAudio().getStatus());
        assertEquals("AUDIO_QUEUE_FULL", audioMapper.latestAudio().getErrorMessage());
        verify(notificationService).publishShareSummaryAudioFailed(
                any(ShareSummaryRunRecord.class),
                any(ShareSummaryAudioRecord.class),
                eq("RejectedExecutionException"),
                eq("AUDIO_QUEUE_FULL")
        );
    }

    @Test
    void audioFailureNotificationIncludesErrorTypeAndFallbackMessage() throws Exception {
        FakeAudioMapper audioMapper = new FakeAudioMapper(config());
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        ShareSummaryAudioClient audioClient = mock(ShareSummaryAudioClient.class);
        when(audioClient.supports(any(String.class))).thenReturn(true);
        when(audioClient.generate(any(ShareSummaryAudioConfigRecord.class), any(String.class)))
                .thenThrow(new IOException());
        NotificationService notificationService = mock(NotificationService.class);
        ShareSummaryAudioService service = service(audioMapper, shareSummaryMapper, audioClient, new DirectExecutorService(), notificationService);

        service.generateAudio(1, true);

        assertEquals("Audio generation failed.", audioMapper.latestAudio().getErrorMessage());
        verify(notificationService).publishShareSummaryAudioFailed(
                any(ShareSummaryRunRecord.class),
                any(ShareSummaryAudioRecord.class),
                eq("IOException"),
                eq("Audio generation failed.")
        );
    }

    @Test
    void keepsMimoSunWukongOnPresetModel() {
        ShareSummaryAudioConfigRecord config = config();
        config.setProviderType("MIMO_TTS");
        config.setBaseUrl("https://api.xiaomimimo.com");
        config.setEndpointPath("/v1/chat/completions");
        config.setModel("mimo-v2.5-tts");
        config.setVoice("苏打");
        config.setStyle("请用孙悟空式的角色语气朗读，语气机灵、有气势、节奏明快，但保持内容清晰可懂。");
        config.setOutputFormat("wav");
        FakeAudioMapper audioMapper = new FakeAudioMapper(config);
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        ShareSummaryAudioClient audioClient = mock(ShareSummaryAudioClient.class);
        when(audioClient.supports(any(String.class))).thenReturn(true);
        ShareSummaryAudioService service = service(audioMapper, shareSummaryMapper, audioClient, new DirectExecutorService(), null);

        ShareSummaryAudioService.ConfigResponse response = service.config();

        assertEquals("mimo-v2.5-tts", response.model());
        assertEquals("苏打", response.voice());
        assertEquals("孙悟空 活泼 凌厉 兴奋", response.style());
        assertEquals("wav", response.outputFormat());
    }

    @Test
    void downgradesFailedMimoVoiceDesignSunWukongConfigToPresetModel() {
        ShareSummaryAudioConfigRecord config = config();
        config.setProviderType("MIMO_TTS");
        config.setBaseUrl("https://api.xiaomimimo.com");
        config.setEndpointPath("/v1/chat/completions");
        config.setModel("mimo-v2.5-tts-voicedesign");
        config.setVoice("孙悟空");
        config.setStyle("请设计并使用一个神似孙悟空的中文男声音色：声音机灵、有英雄气、节奏明快，带一点齐天大圣的戏剧张力；朗读时保持内容清晰可懂，不要改写原文，不要额外添加台词或口头禅。");
        config.setOutputFormat("wav");
        FakeAudioMapper audioMapper = new FakeAudioMapper(config);
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        ShareSummaryAudioClient audioClient = mock(ShareSummaryAudioClient.class);
        when(audioClient.supports(any(String.class))).thenReturn(true);
        ShareSummaryAudioService service = service(audioMapper, shareSummaryMapper, audioClient, new DirectExecutorService(), null);

        ShareSummaryAudioService.ConfigResponse response = service.config();

        assertEquals("mimo-v2.5-tts", response.model());
        assertEquals("苏打", response.voice());
        assertEquals("孙悟空 活泼 凌厉 兴奋", response.style());
        assertEquals("wav", response.outputFormat());
    }

    @Test
    void keepsExplicitEmptyMimoAudioTag() {
        ShareSummaryAudioConfigRecord config = config();
        config.setProviderType("MIMO_TTS");
        config.setBaseUrl("https://api.xiaomimimo.com");
        config.setEndpointPath("/v1/chat/completions");
        config.setModel("mimo-v2.5-tts");
        config.setVoice("苏打");
        config.setStyle("");
        config.setOutputFormat("wav");
        FakeAudioMapper audioMapper = new FakeAudioMapper(config);
        FakeShareSummaryMapper shareSummaryMapper = new FakeShareSummaryMapper(successfulRun());
        ShareSummaryAudioClient audioClient = mock(ShareSummaryAudioClient.class);
        when(audioClient.supports(any(String.class))).thenReturn(true);
        ShareSummaryAudioService service = service(audioMapper, shareSummaryMapper, audioClient, new DirectExecutorService(), null);

        ShareSummaryAudioService.ConfigResponse response = service.config();

        assertEquals("mimo-v2.5-tts", response.model());
        assertEquals("苏打", response.voice());
        assertEquals("", response.style());
        assertEquals("wav", response.outputFormat());
    }

    private ShareSummaryAudioService service(
            FakeAudioMapper audioMapper,
            FakeShareSummaryMapper shareSummaryMapper,
            ShareSummaryAudioClient audioClient,
            ExecutorService executor,
            NotificationService notificationService
    ) {
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setBaseUrl("https://linkpeek.example.com");
        return new ShareSummaryAudioService(
                audioMapper,
                shareSummaryMapper,
                new EmptyImageMapper(),
                audioClient,
                null,
                executor,
                notificationService,
                properties,
                Clock.fixed(Instant.parse("2026-05-30T02:00:00Z"), ZONE)
        );
    }

    private ShareSummaryAudioConfigRecord config() {
        ShareSummaryAudioConfigRecord config = new ShareSummaryAudioConfigRecord();
        config.setEnabled(true);
        config.setAutoGenerate(true);
        config.setProviderType("OPENAI_COMPATIBLE");
        config.setBaseUrl("https://tts.example.com");
        config.setEndpointPath("/v1/audio/speech");
        config.setApiKey("sk-test");
        config.setModel("tts-1");
        config.setVoice("zh-CN-YunhaoNeural");
        config.setSpeed(1.2);
        config.setPitch(0);
        config.setStyle("newscast");
        config.setOutputFormat("mp3");
        config.setRequestTimeoutSeconds(7);
        return config;
    }

    private ShareSummaryRunRecord successfulRun() {
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setId(1L);
        run.setTaskId(3L);
        run.setTaskName("每周分享总结");
        run.setTriggerType("MANUAL");
        run.setPeriodType("DAILY");
        run.setWindowStart(LocalDate.parse("2026-05-29").atStartOfDay(ZONE).toInstant().toEpochMilli());
        run.setWindowEnd(LocalDate.parse("2026-05-30").atStartOfDay(ZONE).toInstant().toEpochMilli());
        run.setStatus("SUCCESS");
        run.setReport("报告内容");
        return run;
    }

    private static final class FakeAudioMapper implements ShareSummaryAudioMapper {
        private ShareSummaryAudioConfigRecord config;
        private final List<ShareSummaryAudioRecord> audios = new ArrayList<>();
        private long nextAudioId = 20;

        private FakeAudioMapper(ShareSummaryAudioConfigRecord config) {
            this.config = config;
        }

        @Override
        public ShareSummaryAudioConfigRecord selectConfig() {
            return config;
        }

        @Override
        public int upsertConfig(ShareSummaryAudioConfigRecord config) {
            this.config = config;
            return 1;
        }

        @Override
        public void insertAudio(ShareSummaryAudioRecord audio) {
            audio.setId(nextAudioId++);
            audios.add(audio);
        }

        @Override
        public int updateAudio(ShareSummaryAudioRecord audio) {
            return 1;
        }

        @Override
        public ShareSummaryAudioRecord selectAudio(long id) {
            return audios.stream()
                    .filter(audio -> audio.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public ShareSummaryAudioRecord selectLatestAudio(long runId) {
            return audios.stream()
                    .filter(audio -> audio.getRunId() == runId)
                    .max(Comparator.comparingInt(ShareSummaryAudioRecord::getAttemptNo))
                    .orElse(null);
        }

        @Override
        public ShareSummaryAudioRecord selectLatestSuccessfulAudio(long runId) {
            return audios.stream()
                    .filter(audio -> audio.getRunId() == runId)
                    .filter(audio -> ShareSummaryAudioStatus.SUCCESS.name().equals(audio.getStatus()))
                    .max(Comparator.comparingInt(ShareSummaryAudioRecord::getAttemptNo))
                    .orElse(null);
        }

        @Override
        public ShareSummaryAudioRecord selectActiveAudio(long runId) {
            return audios.stream()
                    .filter(audio -> audio.getRunId() == runId)
                    .filter(audio -> ShareSummaryAudioStatus.PENDING.name().equals(audio.getStatus())
                            || ShareSummaryAudioStatus.GENERATING.name().equals(audio.getStatus()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int selectNextAttemptNo(long runId) {
            return audios.stream()
                    .filter(audio -> audio.getRunId() == runId)
                    .mapToInt(ShareSummaryAudioRecord::getAttemptNo)
                    .max()
                    .orElse(0) + 1;
        }

        @Override
        public List<ShareSummaryAudioRecord> selectAudiosForRun(long runId) {
            return audios.stream()
                    .filter(audio -> audio.getRunId() == runId)
                    .sorted(Comparator.comparingInt(ShareSummaryAudioRecord::getAttemptNo).reversed())
                    .toList();
        }

        @Override
        public int deleteAudiosForRun(long runId) {
            int before = audios.size();
            audios.removeIf(audio -> audio.getRunId() == runId);
            return before - audios.size();
        }

        private ShareSummaryAudioRecord latestAudio() {
            return selectLatestAudio(1);
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

    private static final class EmptyImageMapper implements ShareSummaryImageMapper {
        @Override
        public ShareSummaryImageConfigRecord selectConfig() {
            return null;
        }

        @Override
        public int upsertConfig(ShareSummaryImageConfigRecord config) {
            return 0;
        }

        @Override
        public void insertImage(ShareSummaryImageRecord image) {
        }

        @Override
        public int updateImage(ShareSummaryImageRecord image) {
            return 0;
        }

        @Override
        public ShareSummaryImageRecord selectImage(long id) {
            return null;
        }

        @Override
        public ShareSummaryImageRecord selectImageByPublicToken(String publicToken) {
            return null;
        }

        @Override
        public ShareSummaryImageRecord selectLatestImage(long runId) {
            return null;
        }

        @Override
        public ShareSummaryImageRecord selectLatestSuccessfulImage(long runId) {
            return null;
        }

        @Override
        public ShareSummaryImageRecord selectActiveImage(long runId) {
            return null;
        }

        @Override
        public int selectNextAttemptNo(long runId) {
            return 1;
        }

        @Override
        public List<ShareSummaryImageRecord> selectImagesForRun(long runId) {
            return List.of();
        }

        @Override
        public int deleteImagesForRun(long runId) {
            return 0;
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
