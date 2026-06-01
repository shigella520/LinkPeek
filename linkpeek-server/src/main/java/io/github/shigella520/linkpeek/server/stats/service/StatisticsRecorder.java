package io.github.shigella520.linkpeek.server.stats.service;

import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.server.service.PreviewService;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsClientType;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsErrorCode;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsEventRecord;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsEventType;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsLinkRecord;
import io.github.shigella520.linkpeek.server.stats.persistence.StatsEventMapper;
import io.github.shigella520.linkpeek.server.stats.persistence.StatsLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class StatisticsRecorder {
    private static final Logger log = LoggerFactory.getLogger(StatisticsRecorder.class);

    private final StatsEventMapper statsEventMapper;
    private final StatsLinkMapper statsLinkMapper;
    private final Clock clock;
    private final StatisticsEventDeduplicator eventDeduplicator;

    public StatisticsRecorder(
            StatsEventMapper statsEventMapper,
            StatsLinkMapper statsLinkMapper,
            Clock clock,
            StatisticsEventDeduplicator eventDeduplicator
    ) {
        this.statsEventMapper = statsEventMapper;
        this.statsLinkMapper = statsLinkMapper;
        this.clock = clock;
        this.eventDeduplicator = eventDeduplicator;
    }

    public void recordPreviewCreated(
            PreviewService.PreviewLoadResult result,
            StatisticsClientType clientType,
            int httpStatus,
            long durationMs
    ) {
        PreviewMetadata metadata = result.metadata();
        recordEvent(
                result.previewKey().value(),
                metadata.providerId(),
                metadata.canonicalUrl(),
                metadata.title(),
                metadata.siteName(),
                StatisticsEventType.PREVIEW_CREATED,
                clientType,
                httpStatus,
                result.cacheHit(),
                result.aiRequested(),
                result.aiSucceeded(),
                result.resolvedPreview().sourceUrl().toString(),
                result.requestedStyle(),
                result.actualStyle(),
                joinAiProviderNames(result.aiProviderNames()),
                result.aiDurationMs(),
                result.crawlDurationMs(),
                durationMs,
                null
        );
    }

    public void recordPreviewOpened(
            PreviewService.ResolvedPreview resolvedPreview,
            StatisticsClientType clientType,
            int httpStatus,
            long durationMs
    ) {
        recordEvent(
                resolvedPreview.previewKey().value(),
                resolvedPreview.provider().getId(),
                resolvedPreview.canonicalUrl().toString(),
                "",
                "",
                StatisticsEventType.PREVIEW_OPENED,
                clientType,
                httpStatus,
                false,
                false,
                false,
                resolvedPreview.sourceUrl().toString(),
                null,
                null,
                "",
                0,
                0,
                durationMs,
                null
        );
    }

    public void recordPreviewOpened(
            PreviewService.PreviewLoadResult result,
            StatisticsClientType clientType,
            int httpStatus,
            long durationMs
    ) {
        PreviewMetadata metadata = result.metadata();
        recordEvent(
                result.previewKey().value(),
                metadata.providerId(),
                metadata.canonicalUrl(),
                metadata.title(),
                metadata.siteName(),
                StatisticsEventType.PREVIEW_OPENED,
                clientType,
                httpStatus,
                result.cacheHit(),
                result.aiRequested(),
                result.aiSucceeded(),
                result.resolvedPreview().sourceUrl().toString(),
                result.requestedStyle(),
                result.actualStyle(),
                joinAiProviderNames(result.aiProviderNames()),
                result.aiDurationMs(),
                result.crawlDurationMs(),
                durationMs,
                null
        );
    }

    public void recordLinkMetadata(PreviewService.PreviewLoadResult result) {
        PreviewMetadata metadata = result.metadata();
        long occurredAt = Instant.now(clock).toEpochMilli();
        try {
            statsLinkMapper.upsertLink(linkRecord(
                    result.previewKey().value(),
                    metadata.providerId(),
                    metadata.canonicalUrl(),
                    metadata.title(),
                    metadata.siteName(),
                    occurredAt
            ));
        } catch (RuntimeException exception) {
            log.warn(
                    "statistics_link_metadata_record_failed previewKey={} provider={}",
                    result.previewKey().value(),
                    metadata.providerId(),
                    exception
            );
        }
    }

    public void recordPreviewFailed(
            PreviewService.ResolvedPreview resolvedPreview,
            StatisticsClientType clientType,
            int httpStatus,
            long durationMs,
            StatisticsErrorCode errorCode
    ) {
        recordEvent(
                resolvedPreview == null ? null : resolvedPreview.previewKey().value(),
                resolvedPreview == null ? null : resolvedPreview.provider().getId(),
                resolvedPreview == null ? null : resolvedPreview.canonicalUrl().toString(),
                "",
                "",
                StatisticsEventType.PREVIEW_FAILED,
                clientType,
                httpStatus,
                false,
                false,
                false,
                resolvedPreview == null ? null : resolvedPreview.sourceUrl().toString(),
                null,
                null,
                "",
                0,
                0,
                durationMs,
                errorCode
        );
    }

    public void recordThumbnailServed(
            String previewKey,
            PreviewMetadata metadata,
            boolean cacheHit,
            long durationMs
    ) {
        recordEvent(
                previewKey,
                metadata == null ? null : metadata.providerId(),
                metadata == null ? null : metadata.canonicalUrl(),
                metadata == null ? "" : metadata.title(),
                metadata == null ? "" : metadata.siteName(),
                StatisticsEventType.THUMBNAIL_SERVED,
                StatisticsClientType.MEDIA,
                200,
                cacheHit,
                false,
                false,
                metadata == null ? null : metadata.sourceUrl(),
                null,
                null,
                "",
                0,
                0,
                durationMs,
                null
        );
    }

    private void recordEvent(
            String previewKey,
            String providerId,
            String canonicalUrl,
            String title,
            String siteName,
            StatisticsEventType eventType,
            StatisticsClientType clientType,
            int httpStatus,
            boolean cacheHit,
            boolean aiRequested,
            boolean aiSucceeded,
            String sourceUrl,
            String requestedStyle,
            String actualStyle,
            String aiProviderNames,
            long aiDurationMs,
            long crawlDurationMs,
            long durationMs,
            StatisticsErrorCode errorCode
    ) {
        long occurredAt = Instant.now(clock).toEpochMilli();
        try {
            if (previewKey != null && canonicalUrl != null && !canonicalUrl.isBlank()) {
                statsLinkMapper.upsertLink(linkRecord(
                        previewKey,
                        providerId,
                        canonicalUrl,
                        title,
                        siteName,
                        occurredAt
                ));
            }
            StatisticsEventRecord eventRecord = eventRecord(
                    occurredAt,
                    previewKey,
                    providerId,
                    eventType,
                    clientType,
                    httpStatus,
                    cacheHit,
                    aiRequested,
                    aiSucceeded,
                    sourceUrl,
                    requestedStyle,
                    actualStyle,
                    aiProviderNames,
                    aiDurationMs,
                    crawlDurationMs,
                    durationMs,
                    errorCode
            );
            StatisticsEventDeduplicator.EventKey dedupeKey = dedupeKey(eventRecord);
            if (dedupeKey != null && !eventDeduplicator.shouldRecord(dedupeKey, occurredAt)) {
                log.debug(
                        "statistics_duplicate_event_skipped eventType={} previewKey={} provider={} clientType={}",
                        eventType,
                        previewKey == null ? "n/a" : previewKey,
                        providerId == null ? "n/a" : providerId,
                        clientType
                );
                return;
            }
            try {
                statsEventMapper.insertEvent(eventRecord);
            } catch (RuntimeException exception) {
                if (dedupeKey != null) {
                    eventDeduplicator.forget(dedupeKey);
                }
                throw exception;
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "statistics_record_failed eventType={} previewKey={} provider={}",
                    eventType,
                    previewKey == null ? "n/a" : previewKey,
                    providerId == null ? "n/a" : providerId,
                    exception
            );
        }
    }

    private StatisticsEventDeduplicator.EventKey dedupeKey(StatisticsEventRecord eventRecord) {
        if (!shouldDedupe(eventRecord)) {
            return null;
        }
        return new StatisticsEventDeduplicator.EventKey(
                eventRecord.getPreviewKey(),
                eventRecord.getProviderId(),
                StatisticsEventType.valueOf(eventRecord.getEventType()),
                StatisticsClientType.valueOf(eventRecord.getClientType()),
                eventRecord.getHttpStatus(),
                eventRecord.getSourceUrl(),
                eventRecord.getRequestedStyle(),
                eventRecord.getActualStyle()
        );
    }

    private boolean shouldDedupe(StatisticsEventRecord eventRecord) {
        return StatisticsEventType.PREVIEW_CREATED.name().equals(eventRecord.getEventType())
                && StatisticsClientType.CRAWLER.name().equals(eventRecord.getClientType())
                && eventRecord.getPreviewKey() != null
                && !eventRecord.getPreviewKey().isBlank();
    }

    private StatisticsLinkRecord linkRecord(
            String previewKey,
            String providerId,
            String canonicalUrl,
            String title,
            String siteName,
            long occurredAt
    ) {
        StatisticsLinkRecord record = new StatisticsLinkRecord();
        record.setPreviewKey(previewKey);
        record.setProviderId(providerId);
        record.setCanonicalUrl(canonicalUrl);
        record.setTitle(title == null ? "" : title);
        record.setSiteName(siteName == null ? "" : siteName);
        record.setFirstSeenAt(occurredAt);
        record.setLastSeenAt(occurredAt);
        return record;
    }

    private StatisticsEventRecord eventRecord(
            long occurredAt,
            String previewKey,
            String providerId,
            StatisticsEventType eventType,
            StatisticsClientType clientType,
            int httpStatus,
            boolean cacheHit,
            boolean aiRequested,
            boolean aiSucceeded,
            String sourceUrl,
            String requestedStyle,
            String actualStyle,
            String aiProviderNames,
            long aiDurationMs,
            long crawlDurationMs,
            long durationMs,
            StatisticsErrorCode errorCode
    ) {
        StatisticsEventRecord record = new StatisticsEventRecord();
        record.setOccurredAt(occurredAt);
        record.setPreviewKey(previewKey);
        record.setProviderId(providerId);
        record.setEventType(eventType.name());
        record.setClientType(clientType.name());
        record.setHttpStatus(httpStatus);
        record.setCacheHit(cacheHit);
        record.setAiRequested(aiRequested);
        record.setAiSucceeded(aiSucceeded);
        record.setSourceUrl(sourceUrl);
        record.setRequestedStyle(requestedStyle);
        record.setActualStyle(actualStyle);
        record.setAiProviderNames(aiProviderNames);
        record.setAiDurationMs(Math.max(0, aiDurationMs));
        record.setCrawlDurationMs(Math.max(0, crawlDurationMs));
        record.setDurationMs(durationMs);
        record.setErrorCode(errorCode == null ? null : errorCode.name());
        return record;
    }

    private String joinAiProviderNames(java.util.List<String> providerNames) {
        if (providerNames == null || providerNames.isEmpty()) {
            return "";
        }
        return String.join("/", providerNames);
    }
}
