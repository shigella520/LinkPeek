package io.github.shigella520.linkpeek.server.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Component
public class DiskCacheManager {
    private static final Logger log = LoggerFactory.getLogger(DiskCacheManager.class);

    private final ObjectMapper objectMapper;
    private final Path cacheDir;
    private final long ttlSeconds;
    private final long maxSizeBytes;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DiskCacheManager(ObjectMapper objectMapper, LinkPeekProperties properties) {
        this.objectMapper = objectMapper;
        this.cacheDir = properties.getCacheDir();
        this.ttlSeconds = properties.getCacheTtlSeconds();
        this.maxSizeBytes = (long) (properties.getCacheMaxSizeGb() * 1024 * 1024 * 1024);
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(metaDir());
        Files.createDirectories(thumbDir());
        Files.createDirectories(videoDir());
        evictIfNeeded();
    }

    public Optional<PreviewMetadata> getMetadata(PreviewKey previewKey) {
        Path path = metadataPath(previewKey);
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        try {
            CachedMetadata entry = objectMapper.readValue(path.toFile(), CachedMetadata.class);
            if (isExpired(entry.cachedAtEpochMillis())) {
                Files.deleteIfExists(path);
                return Optional.empty();
            }
            touch(path);
            return Optional.of(entry.metadata());
        } catch (IOException exception) {
            tryDelete(path);
            return Optional.empty();
        }
    }

    public void storeMetadata(PreviewKey previewKey, PreviewMetadata metadata) {
        Path path = metadataPath(previewKey);
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), new CachedMetadata(metadata, Instant.now().toEpochMilli()));
            touch(path);
            evictIfNeeded();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write preview metadata cache", exception);
        }
    }

    public Optional<Path> getThumbnailPath(PreviewKey previewKey) {
        Path path = thumbnailPath(previewKey);
        if (!Files.exists(path) || isExpired(path)) {
            tryDelete(path);
            return Optional.empty();
        }
        touch(path);
        return Optional.of(path);
    }

    public Path thumbnailPath(PreviewKey previewKey) {
        return thumbDir().resolve(previewKey.value() + ".jpg");
    }

    public Path videoPath(PreviewKey previewKey) {
        return videoDir().resolve(previewKey.value() + ".mp4");
    }

    public ReentrantLock lockFor(PreviewKey previewKey) {
        return locks.computeIfAbsent(previewKey.value(), ignored -> new ReentrantLock());
    }

    public void evictIfNeeded() throws IOException {
        if (maxSizeBytes <= 0) {
            return;
        }
        List<PathStat> files = allTrackedFiles().toList();
        long totalBytes = files.stream().mapToLong(PathStat::size).sum();

        if (totalBytes <= maxSizeBytes) {
            return;
        }

        List<PathStat> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(PathStat::lastModified));

        for (PathStat stat : sorted) {
            if (totalBytes <= (long) (maxSizeBytes * 0.8)) {
                return;
            }
            tryDelete(stat.path());
            totalBytes -= stat.size();
            log.info("cache_evicted path={} bytes={}", stat.path().getFileName(), stat.size());
        }
    }

    public void writeBytes(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        touch(target);
        evictIfNeeded();
    }

    private boolean isExpired(long cachedAtEpochMillis) {
        return Instant.now().minusSeconds(ttlSeconds).toEpochMilli() > cachedAtEpochMillis;
    }

    private boolean isExpired(Path path) {
        try {
            return Instant.now().minusSeconds(ttlSeconds).isAfter(Files.getLastModifiedTime(path).toInstant());
        } catch (IOException exception) {
            return true;
        }
    }

    private Stream<PathStat> allTrackedFiles() throws IOException {
        return Stream.of(metaDir(), thumbDir(), videoDir())
                .flatMap(this::safeWalk)
                .filter(Files::isRegularFile)
                .map(this::stat);
    }

    private Stream<Path> safeWalk(Path root) {
        try {
            return Files.list(root);
        } catch (IOException exception) {
            return Stream.empty();
        }
    }

    private PathStat stat(Path path) {
        try {
            return new PathStat(path, Files.size(path), Files.getLastModifiedTime(path).toInstant());
        } catch (IOException exception) {
            return new PathStat(path, 0L, Instant.EPOCH);
        }
    }

    private void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
        } catch (IOException exception) {
            log.debug("cache_touch_failed path={}", path, exception);
        }
    }

    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.debug("cache_delete_failed path={}", path, exception);
        }
    }

    private Path metaDir() {
        return cacheDir.resolve("meta");
    }

    private Path thumbDir() {
        return cacheDir.resolve("thumb");
    }

    private Path videoDir() {
        return cacheDir.resolve("video");
    }

    private Path metadataPath(PreviewKey previewKey) {
        return metaDir().resolve(previewKey.value() + ".json");
    }

    private record CachedMetadata(PreviewMetadata metadata, long cachedAtEpochMillis) {
    }

    private record PathStat(Path path, long size, Instant lastModified) {
    }
}
