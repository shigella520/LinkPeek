package io.github.shigella520.linkpeek.core.error;

import io.github.shigella520.linkpeek.core.model.PreviewErrorCode;

public class UnsupportedPreviewUrlException extends PreviewException {
    public UnsupportedPreviewUrlException(String message) {
        super(PreviewErrorCode.UNSUPPORTED_URL, message);
    }
}
