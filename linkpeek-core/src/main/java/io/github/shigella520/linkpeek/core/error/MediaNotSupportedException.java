package io.github.shigella520.linkpeek.core.error;

import io.github.shigella520.linkpeek.core.model.PreviewErrorCode;

public class MediaNotSupportedException extends PreviewException {
    public MediaNotSupportedException(String message) {
        super(PreviewErrorCode.MEDIA_UNSUPPORTED, message);
    }
}
