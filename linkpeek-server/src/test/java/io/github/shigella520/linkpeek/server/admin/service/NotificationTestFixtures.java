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
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Synthetic notification fixtures shared by the notification component tests. */
final class NotificationTestFixtures {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-04T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NotificationTestFixtures() {
    }

    static NotificationDeliveryService deliveryService(NotificationMapper mapper, ExecutorService executor,
                                                       HttpClient client, LinkPeekProperties properties) {
        return new NotificationDeliveryService(mapper, OBJECT_MAPPER, client, executor, properties, CLOCK);
    }

    static ExecutorService directExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    static NotificationConfigService.ChannelRequest channelRequest(String url) {
        return new NotificationConfigService.ChannelRequest("BlueBubbles", true, url, null, null, null, 10);
    }

    static NotificationDeliveryRecord failedDelivery() {
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

    static NotificationChannelRecord channel() {
        NotificationChannelRecord channel = new NotificationChannelRecord();
        channel.setId(3L);
        channel.setName("Webhook");
        channel.setEnabled(true);
        channel.setUrl("https://example.test/webhook");
        channel.setBodyTemplate("{{message.body}}");
        channel.setTimeoutSeconds(10);
        return channel;
    }

    static NotificationMapper mapperForPublish(NotificationEventType eventType, String template) {
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

    static NotificationDeliveryRecord insertedDelivery(NotificationMapper mapper) {
        ArgumentCaptor<NotificationDeliveryRecord> delivery = ArgumentCaptor.forClass(NotificationDeliveryRecord.class);
        verify(mapper).insertDelivery(delivery.capture());
        return delivery.getValue();
    }

    static AiProviderRecord provider(long id) {
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

    static ShareSummaryRunRecord shareSummaryRun() {
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

    static ShareSummaryImageRecord failedImage() {
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

    static ShareSummaryAudioRecord failedAudio() {
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
