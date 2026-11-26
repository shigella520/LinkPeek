package io.github.shigella520.linkpeek.server.admin.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareSummaryAssetStorageTest {
    @TempDir
    Path tempDir;

    private Path cacheDir;
    private LinkPeekProperties properties;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private ShareSummaryAssetStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        cacheDir = Files.createDirectories(tempDir.resolve("cache"));
        properties = new LinkPeekProperties();
        properties.setCacheDir(cacheDir);
        logger = (Logger) LoggerFactory.getLogger("test.share-summary-asset-storage");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        storage = new ShareSummaryAssetStorage(properties, "image", logger);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void savesBytesUnderCacheDirectoryCreatingParents() throws IOException {
        String key = storage.save("share-summary/images/3/9.png", new byte[]{1, 2, 3});

        assertEquals("share-summary/images/3/9.png", key);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(cacheDir.resolve("share-summary/images/3/9.png")));
    }

    @Test
    void refusesToSaveOutsideCacheDirectory() {
        IOException exception = assertThrows(IOException.class, () -> storage.save("../escaped.png", new byte[]{1}));

        assertEquals("Image storage path is invalid.", exception.getMessage());
        assertFalse(Files.exists(tempDir.resolve("escaped.png")));
        IOException audio = assertThrows(IOException.class,
                () -> new ShareSummaryAssetStorage(properties, "audio", logger).save("a/../../escaped.mp3", new byte[]{1}));
        assertEquals("Audio storage path is invalid.", audio.getMessage());
    }

    @Test
    void resolvesOnlyExistingFilesInsideCacheDirectory() throws IOException {
        storage.save("share-summary/images/1/1.png", new byte[]{1});
        Files.write(tempDir.resolve("outside.png"), new byte[]{1});
        Files.createDirectories(cacheDir.resolve("share-summary/images/1/dir.png"));

        assertEquals(Optional.of(cacheDir.toAbsolutePath().normalize().resolve("share-summary/images/1/1.png")),
                storage.existingFile("share-summary/images/1/1.png"));
        assertTrue(storage.existingFile("share-summary/images/1/missing.png").isEmpty());
        assertTrue(storage.existingFile("../outside.png").isEmpty());
        assertTrue(storage.existingFile(tempDir.resolve("outside.png").toString()).isEmpty());
        assertTrue(storage.existingFile("share-summary/images/1/dir.png").isEmpty());
    }

    @Test
    void deletesImmediatelyWithoutTransactionAndSkipsUnsafeOrMissingKeys() throws IOException {
        storage.save("share-summary/images/1/1.png", new byte[]{1});
        Path outside = Files.write(tempDir.resolve("outside.png"), new byte[]{1});

        storage.deleteAfterCommit(
                Arrays.asList(new Stored(1L, "share-summary/images/1/1.png"), null, new Stored(2L, null),
                        new Stored(3L, " "), new Stored(4L, "../outside.png"), new Stored(5L, "share-summary/images/1/gone.png")),
                Stored::id, Stored::storageKey);

        assertFalse(Files.exists(cacheDir.resolve("share-summary/images/1/1.png")));
        assertTrue(Files.exists(outside));
        assertEquals(List.of("share_summary_image_delete_skipped_invalid_path imageId=4 storageKey=../outside.png"),
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void deletesOnlyAfterTransactionCommits() throws IOException {
        storage.save("share-summary/images/1/1.png", new byte[]{1});
        Path stored = cacheDir.resolve("share-summary/images/1/1.png");
        TransactionSynchronizationManager.initSynchronization();

        storage.deleteAfterCommit(List.of(new Stored(1L, "share-summary/images/1/1.png")), Stored::id, Stored::storageKey);
        assertTrue(Files.exists(stored));

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertTrue(Files.exists(stored));

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertFalse(Files.exists(stored));
    }

    @Test
    void logsDeleteFailuresWithoutThrowing() throws IOException {
        Path nonEmptyDirectory = Files.createDirectories(cacheDir.resolve("share-summary/images/1/9.png"));
        Files.write(nonEmptyDirectory.resolve("child"), new byte[]{1});

        storage.deleteAfterCommit(List.of(new Stored(9L, "share-summary/images/1/9.png")), Stored::id, Stored::storageKey);

        assertTrue(Files.exists(nonEmptyDirectory));
        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage()
                .startsWith("share_summary_image_file_delete_failed imageId=9 storageKey=share-summary/images/1/9.png message="));
    }

    @Test
    void publicBaseUrlDropsTrailingSlashes() {
        properties.setBaseUrl(null);
        assertEquals("", storage.publicBaseUrl());
        properties.setBaseUrl("https://peek.example//");
        assertEquals("https://peek.example", storage.publicBaseUrl());
    }

    private record Stored(Long id, String storageKey) {
    }
}
