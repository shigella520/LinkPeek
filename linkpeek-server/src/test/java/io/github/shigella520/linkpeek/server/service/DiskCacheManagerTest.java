package io.github.shigella520.linkpeek.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.server.cache.DiskCacheManager;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskCacheManagerTest {
    @Test
    void storesAndLoadsMetadata() throws IOException {
        Path tempDir = Files.createTempDirectory("linkpeek-cache");
        DiskCacheManager cacheManager = new DiskCacheManager(new ObjectMapper(), properties(tempDir));
        cacheManager.init();
        PreviewKey key = PreviewKey.fromCanonicalUrl("https://www.bilibili.com/video/BV1xx411c7mD");
        PreviewMetadata metadata = new PreviewMetadata(
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "bilibili",
                "title",
                "description",
                "Bilibili",
                "https://img.example/test.jpg",
                1200,
                630,
                ContentType.VIDEO
        );

        cacheManager.storeMetadata(key, metadata);

        assertTrue(cacheManager.getMetadata(key).isPresent());
        assertEquals("title", cacheManager.getMetadata(key).orElseThrow().title());
    }

    @Test
    void expiresThumbnailByTtl() throws IOException {
        Path tempDir = Files.createTempDirectory("linkpeek-cache");
        LinkPeekProperties properties = properties(tempDir);
        properties.setCacheTtlSeconds(1);
        DiskCacheManager cacheManager = new DiskCacheManager(new ObjectMapper(), properties);
        cacheManager.init();
        PreviewKey key = PreviewKey.fromCanonicalUrl("https://www.bilibili.com/video/BV1xx411c7mD");
        Path path = cacheManager.thumbnailPath(key);
        cacheManager.writeBytes(path, "thumb".getBytes());
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(5)));

        assertFalse(cacheManager.getThumbnailPath(key).isPresent());
    }

    private LinkPeekProperties properties(Path cacheDir) {
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setCacheDir(cacheDir);
        properties.setCacheTtlSeconds(3600);
        properties.setCacheMaxSizeGb(1);
        return properties;
    }
}
