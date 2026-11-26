package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Stores generated share summary asset files (images, audio) under the cache directory and
 * resolves them safely. Storage keys are relative to the cache directory; any key that would
 * resolve outside of it is rejected.
 *
 * <p>Messages and log events are derived from the asset name, e.g. for {@code "image"}:
 * {@code "Image storage path is invalid."},
 * {@code share_summary_image_delete_skipped_invalid_path imageId=.. storageKey=..} and
 * {@code share_summary_image_file_delete_failed imageId=.. storageKey=.. message=..}.
 * Log events are written to the owning service's logger.
 */
final class ShareSummaryAssetStorage {
    private final LinkPeekProperties properties;
    private final Logger log;
    private final String invalidStoragePathMessage;
    private final String deleteSkippedInvalidPathEvent;
    private final String fileDeleteFailedEvent;

    ShareSummaryAssetStorage(LinkPeekProperties properties, String assetName, Logger log) {
        this.properties = properties;
        this.log = log;
        String label = Character.toUpperCase(assetName.charAt(0)) + assetName.substring(1);
        this.invalidStoragePathMessage = label + " storage path is invalid.";
        this.deleteSkippedInvalidPathEvent = "share_summary_" + assetName + "_delete_skipped_invalid_path " + assetName + "Id={} storageKey={}";
        this.fileDeleteFailedEvent = "share_summary_" + assetName + "_file_delete_failed " + assetName + "Id={} storageKey={} message={}";
    }

    /** Absolute, normalized cache directory that all storage keys are relative to. */
    Path root() {
        return properties.getCacheDir().toAbsolutePath().normalize();
    }

    /** Writes {@code bytes} at {@code storageKey}, creating parent directories, and returns the key. */
    String save(String storageKey, byte[] bytes) throws IOException {
        Path path = root().resolve(storageKey).normalize();
        if (!path.startsWith(root())) {
            throw new IOException(invalidStoragePathMessage);
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes);
        return storageKey;
    }

    /** The stored regular file for {@code storageKey}, if it exists inside the storage root. */
    Optional<Path> existingFile(String storageKey) {
        Path path = root().resolve(storageKey).normalize();
        if (!path.startsWith(root()) || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    /**
     * Deletes the stored files of {@code assets} after the current transaction commits, or
     * immediately when no transaction synchronization is active. Deletion failures are logged,
     * never thrown; assets without a storage key are skipped.
     */
    <T> void deleteAfterCommit(List<T> assets, Function<T, Long> idOf, Function<T, String> storageKeyOf) {
        ShareSummaryVersionStore.afterCommit(() -> assets.forEach(asset -> {
            if (asset != null) {
                delete(idOf.apply(asset), storageKeyOf.apply(asset));
            }
        }));
    }

    private void delete(Long assetId, String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return;
        }
        try {
            Path path = root().resolve(storageKey).normalize();
            if (!path.startsWith(root())) {
                log.warn(deleteSkippedInvalidPathEvent, assetId, storageKey);
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn(fileDeleteFailedEvent, assetId, storageKey, exception.getMessage(), exception);
        }
    }

    /** Configured public base URL without trailing slashes, or an empty string when unset. */
    String publicBaseUrl() {
        String value = properties.getBaseUrl();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
