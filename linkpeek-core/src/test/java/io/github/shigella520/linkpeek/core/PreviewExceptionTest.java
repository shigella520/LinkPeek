package io.github.shigella520.linkpeek.core;

import io.github.shigella520.linkpeek.core.error.InvalidPreviewUrlException;
import io.github.shigella520.linkpeek.core.model.PreviewErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewExceptionTest {
    @Test
    void exposesErrorCode() {
        InvalidPreviewUrlException exception = new InvalidPreviewUrlException("bad");

        assertEquals(PreviewErrorCode.INVALID_URL, exception.getCode());
    }
}
