package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;

public record AiProviderRequestFailedEvent(
        AiProviderRecord provider,
        String operation,
        long durationMs,
        String errorType,
        String errorMessage,
        boolean downgradeEnabled,
        int failureCount,
        int failureThreshold,
        boolean downgradeTriggered
) {
}
