package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;

public record AiProviderAutoDowngradedEvent(
        AiProviderRecord provider,
        String operation,
        long durationMs,
        String errorType,
        String errorMessage,
        int failureCount,
        int failureThreshold,
        int oldSortOrder,
        int newSortOrder,
        boolean alreadyLowest,
        int providerCount
) {
}
