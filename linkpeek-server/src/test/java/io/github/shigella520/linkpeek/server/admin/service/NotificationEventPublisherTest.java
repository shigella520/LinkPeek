package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.ai.AiProviderAutoDowngradedEvent;
import io.github.shigella520.linkpeek.server.ai.AiProviderRequestFailedEvent;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.notification.DataCrawlRequestFailedEvent;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;

import static io.github.shigella520.linkpeek.server.admin.service.NotificationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationEventPublisherTest {
    @Test
    void publishesShareSummaryImageFailedDelivery() {
        NotificationMapper mapper = mapperForPublish(
                NotificationEventType.SHARE_SUMMARY_IMAGE_FAILED,
                "Image {{run.taskName}} #{{image.id}} {{image.status}} {{error.type}} {{error.message}}"
        );
        NotificationEventPublisher service = publisher(mapper);

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
        NotificationEventPublisher service = publisher(mapper);

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
        NotificationEventPublisher service = publisher(mapper);

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
        NotificationEventPublisher service = publisher(mapper);

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
        NotificationEventPublisher service = publisher(mapper);

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
        NotificationEventPublisher service = publisher(mapper);

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

    private NotificationEventPublisher publisher(NotificationMapper mapper) {
        ExecutorService executor = directExecutor();
        LinkPeekProperties properties = new LinkPeekProperties();
        return new NotificationEventPublisher(
                mapper,
                new NotificationTemplateService(OBJECT_MAPPER),
                OBJECT_MAPPER,
                deliveryService(mapper, executor, HttpClient.newHttpClient(), properties),
                executor,
                properties,
                CLOCK
        );
    }
}
