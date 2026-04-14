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

    default Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
        throw new MediaNotSupportedException("Thumbnail download is not supported by provider " + getId());
    }

    default Path downloadVideo(PreviewMetadata metadata, Path targetPath) throws IOException {
        throw new MediaNotSupportedException("Video download is not supported by provider " + getId());
    }
}
