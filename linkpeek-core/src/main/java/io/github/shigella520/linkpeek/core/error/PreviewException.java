package io.github.shigella520.linkpeek.core.error;

import io.github.shigella520.linkpeek.core.model.PreviewErrorCode;

public class PreviewException extends RuntimeException {
    private final PreviewErrorCode code;

    public PreviewException(PreviewErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public PreviewException(PreviewErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public PreviewErrorCode getCode() {
        return code;
    }
}
