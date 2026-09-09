package io.github.shigella520.linkpeek.core.provider;

import io.github.shigella520.linkpeek.core.error.MediaNotSupportedException;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public interface PreviewProvider {
    String getId();

    boolean supports(URI sourceUrl);

    URI canonicalize(URI sourceUrl);

    PreviewMetadata resolve(URI sourceUrl);

    default PreviewMetadata enrichForAiTitle(PreviewMetadata metadata, URI sourceUrl) {
        return metadata;
    }

    default boolean supportsAiTitle(PreviewMetadata metadata) {
        return defaultSupportsAiTitle(metadata);
    }

    static boolean defaultSupportsAiTitle(PreviewMetadata metadata) {
        return metadata != null
                && metadata.thumbnailUrl() != null
                && metadata.thumbnailUrl().startsWith("generated://")
                && metadata.rawContent() != null
                && !metadata.rawContent().isBlank();
    }

    default Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        throw new MediaNotSupportedException("Thumbnail download is not supported by provider " + getId());
    }

    default Path downloadVideo(PreviewMetadata metadata, Path targetPath) throws IOException {
        throw new MediaNotSupportedException("Video download is not supported by provider " + getId());
    }
}
