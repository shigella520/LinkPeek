package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import io.github.shigella520.linkpeek.server.admin.model.NotificationTaskRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.ai.AiProviderAutoDowngradedEvent;
import io.github.shigella520.linkpeek.server.ai.AiProviderRequestFailedEvent;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.notification.DataCrawlRequestFailedEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {
    @Test
    void publishesShareSummaryImageFailedDelivery() {
        NotificationMapper mapper = mapperForPublish(
                NotificationEventType.SHARE_SUMMARY_IMAGE_FAILED,
                "Image {{run.taskName}} #{{image.id}} {{image.status}} {{error.type}} {{error.message}}"
        );
        NotificationService service = service(mapper);

        service.publishShareSummaryImageFailed(shareSummaryRun(), failedImage(), "RejectedExecutionException", "IMAGE_QUEUE_FULL");

        NotificationDeliveryRecord delivery = insertedDelivery(mapper);
        assertEquals("SHARE_SUMMARY_IMAGE_FAILED", delivery.getEventType());
        assertEquals("SHARE_SUMMARY_IMAGE_FAILED:99", delivery.getEventKey());
        assertTrue(delivery.getRequestBody().contains("Image 每周分享总结 #99 FAILED RejectedExecutionException IMAGE_QUEUE_FULL"));
    }

    @Test
    void publishesShareSummaryAudioFailedDelivery() {
        NotificationMapper mapper = mapperForPublish(
                NotificationEventType.SHARE_SUMMARY_AUDIO_FAILED,
                "Audio {{run.taskName}} #{{audio.id}} {{audio.voice}} {{audio.status}} {{error.type}} {{error.message}}"
        );
        NotificationService service = service(mapper);

        service.publishShareSummaryAudioFailed(shareSummaryRun(), failedAudio(), "RejectedExecutionException", "AUDIO_QUEUE_FULL");

        NotificationDeliveryRecord delivery = insertedDelivery(mapper);
        assertEquals("SHARE_SUMMARY_AUDIO_FAILED", delivery.getEventType());
        assertEquals("SHARE_SUMMARY_AUDIO_FAILED:88", delivery.getEventKey());
        assertTrue(delivery.getRequestBody().contains("Audio 每周分享总结 #88 zh-CN-YunhaoNeural FAILED RejectedExecutionException AUDIO_QUEUE_FULL"));
    }

    @Test
    void fallsBackEmptyErrorMessages() {
        NotificationMapper mapper = mapperForPublish(
                NotificationEventType.DATA_CRAWL_REQUEST_FAILED,
                "{{error.type}} {{error.message}}"
        );
        NotificationService service = service(mapper);

        service.publishDataCrawlRequestFailed(new DataCrawlRequestFailedEvent(
                "preview-key",
                "bilibili",
                "https://example.com/source",
                "https://example.com/canonical",
                "CRAWLER",
                502,
                789,
                "FUN",
                "UPSTREAM_ERROR",
                "",
                ""
        ));

        NotificationDeliveryRecord delivery = insertedDelivery(mapper);
        assertTrue(delivery.getRequestBody().contains("DataCrawlException Data crawl request failed."));
    }

    @Test
    void publishesAiProviderRequestFailedDelivery() {
        NotificationMapper mapper = mapperForPublish(
                NotificationEventType.AI_PROVIDER_REQUEST_FAILED,
                "Provider {{provider.name}} failed {{request.operation}} {{error.type}} {{downgrade.failureCount}}/{{downgrade.failureThreshold}}"
        );
        NotificationService service = service(mapper);

        service.publishAiProviderRequestFailed(new AiProviderRequestFailedEvent(
                provider(7L),
                "AI_TITLE",
                123,
                "IOException",
                "upstream failed",
                true,
                2,
                3,
                false
        ));

        NotificationDeliveryRecord delivery = insertedDelivery(mapper);
        assertEquals("AI_PROVIDER_REQUEST_FAILED", delivery.getEventType());
        assertTrue(delivery.getRequestBody().contains("Provider OpenAI failed AI_TITLE IOException 2/3"));
    }

    @Test
    void publishesAiProviderAutoDowngradedDelivery() {
        NotificationMapper mapper = mapperForPublish(
                NotificationEventType.AI_PROVIDER_AUTO_DOWNGRADED,
                "{{provider.name}} moved {{downgrade.oldSortOrder}} -> {{downgrade.newSortOrder}} lowest={{downgrade.alreadyLowest}}"
        );
        NotificationService service = service(mapper);

        service.publishAiProviderAutoDowngraded(new AiProviderAutoDowngradedEvent(
                provider(7L),
                "SHARE_SUMMARY",
                456,
                "IllegalStateException",
                "empty summary",
                3,
                3,
                100,
                300,
                false,
                3
        ));

        NotificationDeliveryRecord delivery = insertedDelivery(mapper);
        assertEquals("AI_PROVIDER_AUTO_DOWNGRADED", delivery.getEventType());
        assertTrue(delivery.getRequestBody().contains("OpenAI moved 100 -> 300 lowest=false"));
    }

    @Test
    void publishesDataCrawlRequestFailedDelivery() {
        NotificationMapper mapper = mapperForPublish(
                NotificationEventType.DATA_CRAWL_REQUEST_FAILED,
                "Crawl {{preview.providerId}} {{preview.previewKey}} {{request.httpStatus}} {{error.code}}"
        );
        NotificationService service = service(mapper);

        service.publishDataCrawlRequestFailed(new DataCrawlRequestFailedEvent(
                "preview-key",
                "bilibili",
                "https://example.com/source",
                "https://example.com/canonical",
                "CRAWLER",
                502,
                789,
                "FUN",
                "UPSTREAM_ERROR",
                "UpstreamFetchException",
                "bad gateway"
        ));

        NotificationDeliveryRecord delivery = insertedDelivery(mapper);
        assertEquals("DATA_CRAWL_REQUEST_FAILED", delivery.getEventType());
        assertTrue(delivery.getRequestBody().contains("Crawl bilibili preview-key 502 UPSTREAM_ERROR"));
    }

    @Test
    void deleteDeliveryRemovesExistingDelivery() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationDeliveryRecord delivery = new NotificationDeliveryRecord();
        delivery.setId(9L);
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.deleteDelivery(9L)).thenReturn(1);

        NotificationService service = service(mapper);

        NotificationService.DeleteResponse response = service.deleteDelivery(9L);

        assertEquals(1, response.deleted());
        verify(mapper).deleteDelivery(9L);
    }

    @Test
    void deleteDeliveryRejectsMissingDelivery() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationService service = service(mapper);

        assertThrows(IllegalArgumentException.class, () -> service.deleteDelivery(404L));

        verify(mapper, never()).deleteDelivery(404L);
    }

    @Test
    void retryDeliveryResetsFailedDeliveryAndSubmitsWebhook() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        NotificationDeliveryRecord delivery = failedDelivery();
        delivery.setRequestBody("{\"text\":\"hello\"}");
        NotificationChannelRecord channel = channel();
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel);
        when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);

        NotificationService service = service(mapper, executor);

        NotificationDeliveryRecord response = service.retryDelivery(9L);

        assertEquals("PENDING", response.getStatus());
        assertEquals(0, response.getAttemptCount());
        assertEquals(channel.getUrl(), response.getRequestUrl());
        assertEquals("{\"text\":\"hello\"}", response.getRequestBody());
        Assertions.assertNull(response.getResponseStatus());
        Assertions.assertNull(response.getResponseBodySnapshot());
        Assertions.assertNull(response.getErrorMessage());
        Assertions.assertNull(response.getFinishedAt());
        verify(mapper).resetDeliveryForRetry(delivery);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
    }

    @Test
    void retryDeliveryResetsSuccessfulDeliveryAndSubmitsWebhook() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        ExecutorService executor = mock(ExecutorService.class);
        NotificationDeliveryRecord delivery = failedDelivery();
        delivery.setStatus("SUCCESS");
        delivery.setRequestBody("{\"text\":\"hello\"}");
        NotificationChannelRecord channel = channel();
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel);
        when(mapper.resetDeliveryForRetry(delivery)).thenReturn(1);
        NotificationService service = service(mapper, executor);

        NotificationDeliveryRecord response = service.retryDelivery(9L);

        assertEquals("PENDING", response.getStatus());
        assertEquals(0, response.getAttemptCount());
        verify(mapper).resetDeliveryForRetry(delivery);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
    }

    @Test
    void retryDeliveryRejectsTruncatedLegacySnapshot() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationDeliveryRecord delivery = failedDelivery();
        delivery.setRequestBodySnapshot("x".repeat(8_000));
        when(mapper.selectDelivery(9L)).thenReturn(delivery);
        when(mapper.selectChannel(3L)).thenReturn(channel());
        NotificationService service = service(mapper);

        assertThrows(IllegalStateException.class, () -> service.retryDelivery(9L));

        verify(mapper, never()).resetDeliveryForRetry(delivery);
    }

    private NotificationService service(NotificationMapper mapper) {
        return service(mapper, directExecutor());
    }

    private NotificationService service(NotificationMapper mapper, ExecutorService executor) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new NotificationService(
                mapper,
                new NotificationTemplateService(objectMapper),
                objectMapper,
                HttpClient.newHttpClient(),
                executor,
                new LinkPeekProperties(),
                Clock.fixed(Instant.parse("2026-06-04T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );
    }

    private ExecutorService directExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    private NotificationDeliveryRecord failedDelivery() {
        NotificationDeliveryRecord delivery = new NotificationDeliveryRecord();
        delivery.setId(9L);
        delivery.setEventType("SHARE_SUMMARY_IMAGE_SUCCESS");
        delivery.setChannelId(3L);
        delivery.setStatus("FAILED");
        delivery.setAttemptCount(3);
        delivery.setRequestUrl("https://old.example.test/webhook");
        delivery.setRequestBodySnapshot("{\"text\":\"hello\"}");
        delivery.setResponseStatus(500);
        delivery.setResponseBodySnapshot("error");
        delivery.setErrorMessage("Webhook returned HTTP 500");
        delivery.setDurationMs(50);
        delivery.setFinishedAt(1770000000000L);
        return delivery;
    }

    private NotificationChannelRecord channel() {
        NotificationChannelRecord channel = new NotificationChannelRecord();
        channel.setId(3L);
        channel.setName("Webhook");
        channel.setEnabled(true);
        channel.setUrl("https://example.test/webhook");
        channel.setBodyTemplate("{{message.body}}");
        channel.setTimeoutSeconds(10);
        return channel;
    }

    private NotificationMapper mapperForPublish(NotificationEventType eventType, String template) {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationTaskRecord task = new NotificationTaskRecord();
        task.setId(5L);
        task.setName("Task");
        task.setEventType(eventType.name());
        task.setTemplateJson(template);
        when(mapper.selectEnabledTasksByEventType(eventType.name())).thenReturn(List.of(task));
        when(mapper.selectEnabledChannelsForTask(5L)).thenReturn(List.of(channel()));
        doAnswer(invocation -> {
            NotificationDeliveryRecord delivery = invocation.getArgument(0);
            delivery.setId(99L);
            return null;
        }).when(mapper).insertDelivery(any(NotificationDeliveryRecord.class));
        return mapper;
    }

    private NotificationDeliveryRecord insertedDelivery(NotificationMapper mapper) {
        ArgumentCaptor<NotificationDeliveryRecord> delivery = ArgumentCaptor.forClass(NotificationDeliveryRecord.class);
        verify(mapper).insertDelivery(delivery.capture());
        return delivery.getValue();
    }

    private AiProviderRecord provider(long id) {
        AiProviderRecord provider = new AiProviderRecord();
        provider.setId(id);
        provider.setName("OpenAI");
        provider.setEnabled(true);
        provider.setSortOrder(100);
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setApiKind("CHAT_COMPLETIONS");
        provider.setModel("gpt-test");
        provider.setRequestTimeoutSeconds(45);
        return provider;
    }

    private ShareSummaryRunRecord shareSummaryRun() {
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setId(12L);
        run.setTaskId(3L);
        run.setTaskName("每周分享总结");
        run.setTriggerType("SCHEDULED");
        run.setPeriodType("WEEKLY");
        run.setWindowStart(1769400000000L);
        run.setWindowEnd(1770000000000L);
        run.setStatus("SUCCESS");
        run.setLinkCount(42);
        run.setUniqueLinkCount(30);
        run.setInputLinkCount(30);
        run.setAiProviderNames("OpenAI");
        run.setAiDurationMs(18000);
        run.setReport("报告内容");
        return run;
    }

    private ShareSummaryImageRecord failedImage() {
        ShareSummaryImageRecord image = new ShareSummaryImageRecord();
        image.setId(99L);
        image.setRunId(12L);
        image.setAttemptNo(1);
        image.setStatus("FAILED");
        image.setProviderType("OPENAI_COMPATIBLE");
        image.setModel("gpt-image-2");
        image.setImageSize("auto");
        image.setOutputFormat("png");
        image.setQuality("auto");
        image.setOgTitle("LinkPeek - 2026年第22周周报");
        image.setOgDescription("描述");
        image.setErrorMessage("IMAGE_QUEUE_FULL");
        image.setCreatedAt(1770000000000L);
        image.setFinishedAt(1770000001000L);
        return image;
    }

    private ShareSummaryAudioRecord failedAudio() {
        ShareSummaryAudioRecord audio = new ShareSummaryAudioRecord();
        audio.setId(88L);
        audio.setRunId(12L);
        audio.setAttemptNo(1);
        audio.setStatus("FAILED");
        audio.setProviderType("OPENAI_COMPATIBLE");
        audio.setModel("tts-1");
        audio.setVoice("zh-CN-YunhaoNeural");
        audio.setSpeed(1.2);
        audio.setPitch(0);
        audio.setStyle("newscast");
        audio.setOutputFormat("mp3");
        audio.setErrorMessage("AUDIO_QUEUE_FULL");
        audio.setCreatedAt(1770000000000L);
        audio.setFinishedAt(1770000001000L);
        return audio;
    }
}
