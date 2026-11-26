package io.github.shigella520.linkpeek.server.admin.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShareSummaryAssetErrorsTest {
    @Test
    void errorTypeUsesExceptionSimpleNameOrFallback() {
        assertEquals("IOException", ShareSummaryAssetErrors.errorType(new IOException("x"), "ImageGenerationException"));
        assertEquals("ImageGenerationException", ShareSummaryAssetErrors.errorType(null, "ImageGenerationException"));
    }

    @Test
    void errorMessageUsesFirstNonBlankMessageAlongCauseChain() {
        RuntimeException wrapped = new RuntimeException(" ", new UncheckedIOException(null, new IOException("  upstream returned HTTP 502 ")));

        assertEquals("upstream returned HTTP 502", ShareSummaryAssetErrors.errorMessage(wrapped, "fallback", "default"));
        assertEquals("  upstream returned HTTP 502 ", ShareSummaryAssetErrors.firstErrorMessage(wrapped));
        assertEquals("", ShareSummaryAssetErrors.firstErrorMessage(new RuntimeException()));
    }

    @Test
    void errorMessageFallsBackToFallbackThenDefault() {
        assertEquals("Image generation was interrupted.",
                ShareSummaryAssetErrors.errorMessage(new InterruptedException(), "Image generation was interrupted.", "Image generation failed."));
        assertEquals("Audio generation failed.",
                ShareSummaryAssetErrors.errorMessage(new RuntimeException(""), null, "Audio generation failed."));
        assertEquals("TTS test failed.", ShareSummaryAssetErrors.errorMessage(null, " ", "TTS test failed."));
    }

    @Test
    void errorMessageIsTruncatedTo500Characters() {
        String message = ShareSummaryAssetErrors.errorMessage(new IllegalStateException("x".repeat(501)), null, "default");

        assertEquals("x".repeat(500), message);
    }
}
