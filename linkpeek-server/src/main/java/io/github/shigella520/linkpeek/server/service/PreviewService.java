package io.github.shigella520.linkpeek.server.service;

import io.github.shigella520.linkpeek.core.error.MetadataNotFoundException;
import io.github.shigella520.linkpeek.core.error.UnsupportedPreviewUrlException;
import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.core.util.UrlNormalizer;
import io.github.shigella520.linkpeek.server.ai.AiTitleService;
import io.github.shigella520.linkpeek.server.cache.DiskCacheManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
public class PreviewService {
    private static final Pattern THUMBNAIL_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final PreviewProviderRegistry providerRegistry;
    private final DiskCacheManager cacheManager;
    private final AiTitleService aiTitleService;
    private final ConcurrentHashMap<String, ReentrantLock> metadataLocks = new ConcurrentHashMap<>();

    public PreviewService(
            PreviewProviderRegistry providerRegistry,
            DiskCacheManager cacheManager,
            AiTitleService aiTitleService
    ) {
        this.providerRegistry = providerRegistry;
        this.cacheManager = cacheManager;
        this.aiTitleService = aiTitleService;
    }

    public ResolvedPreview prepare(String rawUrl) {
        URI sourceUrl = UrlNormalizer.parseHttpUrl(rawUrl);
        PreviewProvider provider = providerRegistry.findSupporting(sourceUrl)
                .orElseThrow(() -> new UnsupportedPreviewUrlException("No preview provider is available for this URL."));
        URI canonicalUrl = provider.canonicalize(sourceUrl);
        return new ResolvedPreview(sourceUrl, canonicalUrl, PreviewKey.fromCanonicalUrl(canonicalUrl), provider);
    }

    public boolean supports(String rawUrl) {
        URI sourceUrl = UrlNormalizer.parseHttpUrl(rawUrl);
        return providerRegistry.findSupporting(sourceUrl).isPresent();
    }

    public PreviewLoadResult loadPreview(ResolvedPreview resolvedPreview) {
        return loadPreview(resolvedPreview, null);
    }

    public PreviewLoadResult loadPreview(ResolvedPreview resolvedPreview, String style) {
        String requestedStyle = requestedStyle(style);
        Optional<AiTitleService.StylePrompt> stylePrompt = aiTitleService.resolveStylePrompt(style, resolvedPreview.canonicalUrl());
        if (stylePrompt.isPresent()) {
            return loadStyledPreview(resolvedPreview, stylePrompt.get(), requestedStyle);
        }

        PreviewLoadResult result = loadBasePreview(resolvedPreview);
        return requestedStyle == null ? result : result.withStyleStats(requestedStyle, null);
    }

    private PreviewLoadResult loadStyledPreview(
            ResolvedPreview resolvedPreview,
            AiTitleService.StylePrompt stylePrompt,
            String requestedStyle
    ) {
        PreviewKey styledPreviewKey = aiTitleService.styledPreviewKey(resolvedPreview.canonicalUrl(), stylePrompt);
        Optional<PreviewMetadata> cachedStyled = cacheManager.getMetadata(styledPreviewKey);
        if (cachedStyled.isPresent()) {
            markFreestyleSelectionSucceededIfNeeded(requestedStyle, resolvedPreview, stylePrompt);
            return new PreviewLoadResult(
                    resolvedPreview,
                    cachedStyled.get(),
                    styledPreviewKey,
                    true,
                    true,
                    true,
                    requestedStyle,
                    stylePrompt.style(),
                    List.of(),
                    0,
                    0
            );
        }

        ReentrantLock lock = metadataLockFor(styledPreviewKey);
        lock.lock();
        try {
            cachedStyled = cacheManager.getMetadata(styledPreviewKey);
            if (cachedStyled.isPresent()) {
                markFreestyleSelectionSucceededIfNeeded(requestedStyle, resolvedPreview, stylePrompt);
                return new PreviewLoadResult(
                        resolvedPreview,
                        cachedStyled.get(),
                        styledPreviewKey,
                        true,
                        true,
                        true,
                        requestedStyle,
                        stylePrompt.style(),
                        List.of(),
                        0,
                        0
                );
            }

            PreviewLoadResult baseResult = loadBasePreview(resolvedPreview);
            PreviewMetadata aiMetadata = resolvedPreview.provider().enrichForAiTitle(baseResult.metadata(), resolvedPreview.sourceUrl());
            if (!resolvedPreview.provider().supportsAiTitle(aiMetadata)) {
                return baseResult.withStyleStats(requestedStyle, stylePrompt.style());
            }
            AiTitleService.StyledMetadataResult styledResult = aiTitleService.generateSupportedStyledMetadataResult(aiMetadata, stylePrompt);
            if (styledResult.metadata().isPresent()) {
                PreviewMetadata styledMetadata = styledResult.metadata().get();
                cacheManager.storeMetadata(styledPreviewKey, styledMetadata);
                markFreestyleSelectionSucceededIfNeeded(requestedStyle, resolvedPreview, stylePrompt);
                return new PreviewLoadResult(
                        resolvedPreview,
                        styledMetadata,
                        styledPreviewKey,
                        false,
                        true,
                        true,
                        requestedStyle,
                        stylePrompt.style(),
                        styledResult.providerNames(),
                        styledResult.durationMs(),
                        baseResult.crawlDurationMs()
                );
            }
            return baseResult.withAiStats(true, false)
                    .withStyleStats(requestedStyle, stylePrompt.style())
                    .withAiAttemptStats(styledResult.providerNames(), styledResult.durationMs());
        } finally {
            lock.unlock();
        }
    }

    private void markFreestyleSelectionSucceededIfNeeded(
            String requestedStyle,
            ResolvedPreview resolvedPreview,
            AiTitleService.StylePrompt stylePrompt
    ) {
        if (AiTitleService.isFreestyleStyle(requestedStyle)) {
            aiTitleService.markFreestyleSelectionSucceeded(resolvedPreview.canonicalUrl(), stylePrompt);
        }
    }

    private PreviewLoadResult loadBasePreview(ResolvedPreview resolvedPreview) {
        Optional<PreviewMetadata> cached = cacheManager.getMetadata(resolvedPreview.previewKey());
        if (cached.isPresent()) {
            return new PreviewLoadResult(resolvedPreview, cached.get(), true);
        }

        ReentrantLock lock = metadataLockFor(resolvedPreview.previewKey());
        lock.lock();
        try {
            cached = cacheManager.getMetadata(resolvedPreview.previewKey());
            if (cached.isPresent()) {
                return new PreviewLoadResult(resolvedPreview, cached.get(), true);
            }

            long startedAt = System.nanoTime();
            PreviewMetadata metadata = resolvedPreview.provider().resolve(resolvedPreview.sourceUrl());
            long crawlDurationMs = elapsedMillis(startedAt);
            cacheManager.storeMetadata(resolvedPreview.previewKey(), metadata);
            return new PreviewLoadResult(resolvedPreview, metadata, false).withCrawlDuration(crawlDurationMs);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock metadataLockFor(PreviewKey previewKey) {
        return metadataLocks.computeIfAbsent(previewKey.value(), ignored -> new ReentrantLock());
    }

    public Optional<PreviewLoadResult> getCachedPreview(ResolvedPreview resolvedPreview) {
        return cacheManager.getMetadata(resolvedPreview.previewKey())
                .map(metadata -> new PreviewLoadResult(resolvedPreview, metadata, true));
    }

    private String requestedStyle(String style) {
        if (style == null || style.isBlank()) {
            return null;
        }
        return style.strip().toUpperCase(Locale.ROOT);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public Path ensureThumbnail(String previewKeyValue) {
        return ensureThumbnailResult(previewKeyValue).path();
    }

    public ThumbnailResult ensureThumbnailResult(String previewKeyValue) {
        return ensureThumbnailResult(previewKeyValue, null);
    }

    public ThumbnailResult ensureThumbnailResult(String previewKeyValue, String version) {
        PreviewKey previewKey = new PreviewKey(previewKeyValue);
        String normalizedVersion = normalizedThumbnailVersion(version);
        Optional<Path> cached = cacheManager.getThumbnailPath(previewKey, normalizedVersion);
        if (cached.isPresent()) {
            return new ThumbnailResult(cached.get(), true, cacheManager.getMetadata(previewKey).orElse(null));
        }

        ReentrantLock lock = cacheManager.lockFor(previewKey, normalizedVersion);
        lock.lock();
        try {
            Optional<Path> lockedCached = cacheManager.getThumbnailPath(previewKey, normalizedVersion);
            if (lockedCached.isPresent()) {
                return new ThumbnailResult(lockedCached.get(), true, cacheManager.getMetadata(previewKey).orElse(null));
            }
            return downloadThumbnail(previewKey, normalizedVersion);
        } finally {
            lock.unlock();
        }
    }

    private ThumbnailResult downloadThumbnail(PreviewKey previewKey, String version) {
        PreviewMetadata metadata = cacheManager.getMetadata(previewKey)
                .orElseThrow(() -> new MetadataNotFoundException("Preview metadata is missing or expired."));
        PreviewProvider provider = providerRegistry.getById(metadata.providerId())
                .orElseThrow(() -> new UnsupportedPreviewUrlException("The provider for this preview is not available."));

        Path targetPath = cacheManager.thumbnailPath(previewKey, version);
        try {
            Path downloaded = provider.downloadThumbnail(metadata, targetPath);
            cacheManager.evictIfNeeded();
            return new ThumbnailResult(downloaded, false, metadata);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store thumbnail in cache", exception);
        }
    }

    private String normalizedThumbnailVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String stripped = version.strip();
        if (!THUMBNAIL_VERSION_PATTERN.matcher(stripped).matches()) {
            throw new IllegalArgumentException("Thumbnail version is invalid.");
        }
        return stripped;
    }

    public record ResolvedPreview(
            URI sourceUrl,
            URI canonicalUrl,
            PreviewKey previewKey,
            PreviewProvider provider
    ) {
    }

    public record PreviewLoadResult(
            ResolvedPreview resolvedPreview,
            PreviewMetadata metadata,
            PreviewKey previewKey,
            boolean cacheHit,
            boolean aiRequested,
            boolean aiSucceeded,
            String requestedStyle,
            String actualStyle,
            List<String> aiProviderNames,
            long aiDurationMs,
            long crawlDurationMs
    ) {
        public PreviewLoadResult(ResolvedPreview resolvedPreview, PreviewMetadata metadata, boolean cacheHit) {
            this(resolvedPreview, metadata, resolvedPreview.previewKey(), cacheHit, false, false, null, null, List.of(), 0, 0);
        }

        public PreviewLoadResult {
            aiProviderNames = aiProviderNames == null ? List.of() : List.copyOf(aiProviderNames);
        }

        public PreviewLoadResult withAiStats(boolean aiRequested, boolean aiSucceeded) {
            return new PreviewLoadResult(
                    resolvedPreview,
                    metadata,
                    previewKey,
                    cacheHit,
                    aiRequested,
                    aiSucceeded,
                    requestedStyle,
                    actualStyle,
                    aiProviderNames,
                    aiDurationMs,
                    crawlDurationMs
            );
        }

        public PreviewLoadResult withStyleStats(String requestedStyle, String actualStyle) {
            return new PreviewLoadResult(
                    resolvedPreview,
                    metadata,
                    previewKey,
                    cacheHit,
                    aiRequested,
                    aiSucceeded,
                    requestedStyle,
                    actualStyle,
                    aiProviderNames,
                    aiDurationMs,
                    crawlDurationMs
            );
        }

        public PreviewLoadResult withAiAttemptStats(List<String> providerNames, long durationMs) {
            return new PreviewLoadResult(
                    resolvedPreview,
                    metadata,
                    previewKey,
                    cacheHit,
                    aiRequested,
                    aiSucceeded,
                    requestedStyle,
                    actualStyle,
                    providerNames,
                    durationMs,
                    crawlDurationMs
            );
        }

        public PreviewLoadResult withCrawlDuration(long durationMs) {
            return new PreviewLoadResult(
                    resolvedPreview,
                    metadata,
                    previewKey,
                    cacheHit,
                    aiRequested,
                    aiSucceeded,
                    requestedStyle,
                    actualStyle,
                    aiProviderNames,
                    aiDurationMs,
                    durationMs
            );
        }
    }

    public record ThumbnailResult(
            Path path,
            boolean cacheHit,
            PreviewMetadata metadata
    ) {
    }
}
