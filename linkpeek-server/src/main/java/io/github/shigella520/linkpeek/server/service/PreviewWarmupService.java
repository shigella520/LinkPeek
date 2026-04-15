package io.github.shigella520.linkpeek.server.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PreviewWarmupService {
    private static final Logger log = LoggerFactory.getLogger(PreviewWarmupService.class);

    private final PreviewService previewService;
    private final StatisticsRecorder statisticsRecorder;
    private final TaskExecutor previewWarmupExecutor;
    private final LinkPeekProperties properties;
    private final Set<String> inFlightPreviewKeys = ConcurrentHashMap.newKeySet();

    public PreviewWarmupService(
            PreviewService previewService,
            StatisticsRecorder statisticsRecorder,
            @Qualifier("previewWarmupExecutor") TaskExecutor previewWarmupExecutor,
            LinkPeekProperties properties
    ) {
        this.previewService = previewService;
        this.statisticsRecorder = statisticsRecorder;
        this.previewWarmupExecutor = previewWarmupExecutor;
        this.properties = properties;
    }

    public boolean schedule(PreviewService.ResolvedPreview resolvedPreview) {
        if (!properties.isPreviewWarmupEnabled()) {
            return false;
        }

        String previewKey = resolvedPreview.previewKey().value();
        if (!inFlightPreviewKeys.add(previewKey)) {
            log.debug("preview_warmup_skipped_duplicate previewKey={} provider={}", previewKey, resolvedPreview.provider().getId());
            return false;
        }

        try {
            previewWarmupExecutor.execute(() -> warmup(resolvedPreview));
            return true;
        } catch (RuntimeException exception) {
            inFlightPreviewKeys.remove(previewKey);
            log.warn(
                    "preview_warmup_rejected previewKey={} provider={} message={}",
                    previewKey,
                    resolvedPreview.provider().getId(),
                    exception.getMessage()
            );
            return false;
        }
    }

    private void warmup(PreviewService.ResolvedPreview resolvedPreview) {
        String previewKey = resolvedPreview.previewKey().value();
        try {
            PreviewService.PreviewLoadResult result = previewService.loadPreview(resolvedPreview);
            statisticsRecorder.recordLinkMetadata(result);
            log.info(
                    "preview_warmup_completed previewKey={} provider={} cacheHit={}",
                    previewKey,
                    result.metadata().providerId(),
                    result.cacheHit()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "preview_warmup_failed previewKey={} provider={} message={}",
                    previewKey,
                    resolvedPreview.provider().getId(),
                    exception.getMessage(),
                    exception
            );
        } finally {
            inFlightPreviewKeys.remove(previewKey);
        }
    }
}
