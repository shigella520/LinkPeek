package io.github.shigella520.linkpeek.core.error;

import io.github.shigella520.linkpeek.core.model.PreviewErrorCode;

public class MetadataNotFoundException extends PreviewException {
    public MetadataNotFoundException(String message) {
        super(PreviewErrorCode.METADATA_NOT_FOUND, message);
    }
}
