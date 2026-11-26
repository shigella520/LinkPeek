package io.github.shigella520.linkpeek.server.admin.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Lifecycle rules shared by share summary asset generation (images and audio): when an active
 * (PENDING/GENERATING) record counts as stale, and how generation is handed to its executor.
 */
final class ShareSummaryAssetLifecycle {
    /** Error message stored on active records that were marked FAILED because they went stale. */
    static final String STALE_ACTIVE_ERROR_MESSAGE = "GENERATION timeout exceeded.";
    private static final long STALE_ACTIVE_BUFFER_MILLIS = 60_000L;

    private ShareSummaryAssetLifecycle() {
    }

    /**
     * Active records created before the returned instant are stale: twice the provider request
     * timeout (at least one second) plus a one-minute buffer.
     */
    static long staleActiveThreshold(long now, int requestTimeoutSeconds) {
        int seconds = Math.max(1, requestTimeoutSeconds);
        return now - (seconds * 2_000L + STALE_ACTIVE_BUFFER_MILLIS);
    }

    /**
     * Submits {@code generation} to {@code executor}. If the executor rejects it, {@code onRejected}
     * runs on the calling thread, in its own transaction when a version store is available.
     */
    static void submit(ExecutorService executor, Runnable generation, ShareSummaryVersionStore versions, Runnable onRejected) {
        try {
            executor.execute(generation);
        } catch (RejectedExecutionException exception) {
            if (versions == null) onRejected.run(); else versions.independent(onRejected);
        }
    }
}
