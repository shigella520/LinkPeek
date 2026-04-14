package io.github.shigella520.linkpeek.core.error;

import io.github.shigella520.linkpeek.core.model.PreviewErrorCode;

public class UpstreamFetchException extends PreviewException {
    public UpstreamFetchException(String message) {
        super(PreviewErrorCode.UPSTREAM_FAILURE, message);
    }

    public UpstreamFetchException(String message, Throwable cause) {
        super(PreviewErrorCode.UPSTREAM_FAILURE, message, cause);
    }
}
