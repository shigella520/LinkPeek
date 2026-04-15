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

    public StatisticsRecorder(
            StatsEventMapper statsEventMapper,
            StatsLinkMapper statsLinkMapper,
            Clock clock
    ) {
        this.statsEventMapper = statsEventMapper;
        this.statsLinkMapper = statsLinkMapper;
        this.clock = clock;
    }

    public void recordPreviewCreated(
            PreviewService.PreviewLoadResult result,
            StatisticsClientType clientType,
            int httpStatus,
            long durationMs
    ) {
        PreviewMetadata metadata = result.metadata();
        recordEvent(
                result.resolvedPreview().previewKey().value(),
                metadata.providerId(),
                metadata.canonicalUrl(),
                metadata.title(),
                metadata.siteName(),
                StatisticsEventType.PREVIEW_CREATED,
                clientType,
                httpStatus,
                result.cacheHit(),
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
                result.resolvedPreview().previewKey().value(),
                metadata.providerId(),
                metadata.canonicalUrl(),
                metadata.title(),
                metadata.siteName(),
                StatisticsEventType.PREVIEW_OPENED,
                clientType,
                httpStatus,
                result.cacheHit(),
                durationMs,
                null
        );
    }

    public void recordLinkMetadata(PreviewService.PreviewLoadResult result) {
        PreviewMetadata metadata = result.metadata();
        long occurredAt = Instant.now(clock).toEpochMilli();
        try {
            statsLinkMapper.upsertLink(linkRecord(
                    result.resolvedPreview().previewKey().value(),
                    metadata.providerId(),
                    metadata.canonicalUrl(),
                    metadata.title(),
                    metadata.siteName(),
                    occurredAt
            ));
        } catch (RuntimeException exception) {
            log.warn(
                    "statistics_link_metadata_record_failed previewKey={} provider={}",
                    result.resolvedPreview().previewKey().value(),
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
            statsEventMapper.insertEvent(eventRecord(
                    occurredAt,
                    previewKey,
                    providerId,
                    eventType,
                    clientType,
                    httpStatus,
                    cacheHit,
                    durationMs,
                    errorCode
            ));
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
        record.setDurationMs(durationMs);
        record.setErrorCode(errorCode == null ? null : errorCode.name());
        return record;
    }
}
