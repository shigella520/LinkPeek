package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.server.admin.model.AdminPreviewEventRow;
import io.github.shigella520.linkpeek.server.admin.persistence.AdminPreviewEventMapper;
import io.github.shigella520.linkpeek.server.cache.DiskCacheManager;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsEventDeduplicator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class AdminPreviewEventService {
    private static final Pattern PREVIEW_KEY_PATTERN = Pattern.compile("^[A-Fa-f0-9]{64}$");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminPreviewEventMapper adminPreviewEventMapper;
    private final DiskCacheManager cacheManager;
    private final StatisticsEventDeduplicator eventDeduplicator;

    public AdminPreviewEventService(
            AdminPreviewEventMapper adminPreviewEventMapper,
            DiskCacheManager cacheManager,
            StatisticsEventDeduplicator eventDeduplicator
    ) {
        this.adminPreviewEventMapper = adminPreviewEventMapper;
        this.cacheManager = cacheManager;
        this.eventDeduplicator = eventDeduplicator;
    }

    public PreviewEventPage previewEvents(Integer page, Integer size, String query) {
        int normalizedSize = normalizeSize(size);
        int normalizedPage = normalizePage(page);
        String normalizedQuery = normalizeQuery(query);
        long total = adminPreviewEventMapper.countPreviewEvents(normalizedQuery);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / normalizedSize);
        int offset = (normalizedPage - 1) * normalizedSize;
        List<PreviewEventItem> items = adminPreviewEventMapper.selectPreviewEvents(
                        normalizedQuery,
                        normalizedSize,
                        offset
                )
                .stream()
                .map(this::item)
                .toList();
        return new PreviewEventPage(items, normalizedPage, normalizedSize, total, totalPages);
    }

    public CacheClearResponse clearCache(String previewKeyValue) {
        PreviewKey previewKey = validatedPreviewKey(previewKeyValue);
        DiskCacheManager.CacheEvictionResult result = cacheManager.evictPreview(previewKey);
        eventDeduplicator.forgetPreviewKey(previewKey.value());
        return new CacheClearResponse(previewKey.value(), result.deletedFiles());
    }

    private PreviewEventItem item(AdminPreviewEventRow row) {
        PreviewKey previewKey = parsePreviewKey(row.getPreviewKey());
        DiskCacheManager.CacheStatus cacheStatus = previewKey == null
                ? new DiskCacheManager.CacheStatus(false, false, false)
                : cacheManager.cacheStatus(previewKey);
        return new PreviewEventItem(
                row.getId(),
                row.getOccurredAt(),
                row.getPreviewKey(),
                row.getSourceUrl(),
                row.getCanonicalUrl(),
                row.getMetadataTitle(),
                row.getProviderId(),
                row.isAiRequested(),
                row.isAiSucceeded(),
                row.getRequestedStyle(),
                row.getActualStyle(),
                row.getAiProviderNames(),
                row.getAiDurationMs(),
                row.getCrawlDurationMs(),
                row.getDurationMs(),
                row.isCacheHit(),
                cacheStatus.metadata(),
                cacheStatus.thumbnail(),
                cacheStatus.video()
        );
    }

    private PreviewKey parsePreviewKey(String previewKey) {
        if (!StringUtils.hasText(previewKey) || !PREVIEW_KEY_PATTERN.matcher(previewKey).matches()) {
            return null;
        }
        return new PreviewKey(previewKey);
    }

    private int normalizePage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String normalizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String stripped = query.strip();
        return stripped.length() > 200 ? stripped.substring(0, 200) : stripped;
    }

    private PreviewKey validatedPreviewKey(String previewKey) {
        if (!StringUtils.hasText(previewKey) || !PREVIEW_KEY_PATTERN.matcher(previewKey.strip()).matches()) {
            throw new IllegalArgumentException("Preview key is invalid.");
        }
        return new PreviewKey(previewKey.strip().toLowerCase(java.util.Locale.ROOT));
    }

    public record PreviewEventPage(
            List<PreviewEventItem> items,
            int page,
            int size,
            long total,
            int totalPages
    ) {
    }

    public record PreviewEventItem(
            long id,
            long occurredAt,
            String previewKey,
            String sourceUrl,
            String canonicalUrl,
            String metadataTitle,
            String providerId,
            boolean aiRequested,
            boolean aiSucceeded,
            String requestedStyle,
            String actualStyle,
            String aiProviderNames,
            long aiDurationMs,
            long crawlDurationMs,
            long durationMs,
            boolean cacheHit,
            boolean metadataCached,
            boolean thumbnailCached,
            boolean videoCached
    ) {
    }

    public record CacheClearResponse(String previewKey, int deletedFiles) {
    }
}
