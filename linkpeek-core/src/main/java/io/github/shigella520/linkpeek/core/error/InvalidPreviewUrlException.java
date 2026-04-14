package io.github.shigella520.linkpeek.core.error;

import io.github.shigella520.linkpeek.core.model.PreviewErrorCode;

public class InvalidPreviewUrlException extends PreviewException {
    public InvalidPreviewUrlException(String message) {
        super(PreviewErrorCode.INVALID_URL, message);
    }
}
