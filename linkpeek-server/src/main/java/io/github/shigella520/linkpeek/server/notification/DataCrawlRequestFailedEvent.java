package io.github.shigella520.linkpeek.server.notification;

public record DataCrawlRequestFailedEvent(
        String previewKey,
        String providerId,
        String sourceUrl,
        String canonicalUrl,
        String clientType,
        int httpStatus,
        long durationMs,
        String requestedStyle,
        String errorCode,
        String errorType,
        String errorMessage
) {
}
