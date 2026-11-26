package io.github.shigella520.linkpeek.server.admin.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ShareSummaryAssetLifecycleTest {
    @Test
    void staleThresholdIsTwiceTheRequestTimeoutPlusOneMinute() {
        assertEquals(1_000_000L - 300 * 2_000L - 60_000L, ShareSummaryAssetLifecycle.staleActiveThreshold(1_000_000L, 300));
        assertEquals(1_000_000L - 2_000L - 60_000L, ShareSummaryAssetLifecycle.staleActiveThreshold(1_000_000L, 0));
    }

    @Test
    void submitRunsGenerationOnExecutorWithoutRejectionHandler() {
        List<String> calls = new ArrayList<>();
        RecordingExecutor executor = new RecordingExecutor(false);

        ShareSummaryAssetLifecycle.submit(executor, () -> calls.add("generate"), null, () -> calls.add("rejected"));

        assertEquals(1, executor.submitted.size());
        executor.submitted.get(0).run();
        assertEquals(List.of("generate"), calls);
    }

    @Test
    void rejectedSubmissionRunsHandlerDirectlyOrInIndependentTransaction() {
        List<String> calls = new ArrayList<>();
        ShareSummaryAssetLifecycle.submit(new RecordingExecutor(true), () -> calls.add("generate"), null, () -> calls.add("rejected"));
        assertEquals(List.of("rejected"), calls);

        ShareSummaryVersionStore versions = mock(ShareSummaryVersionStore.class);
        doAnswer(invocation -> {
            calls.add("independent");
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(versions).independent(any());
        ShareSummaryAssetLifecycle.submit(new RecordingExecutor(true), () -> calls.add("generate"), versions, () -> calls.add("rejected"));
        assertEquals(List.of("rejected", "independent", "rejected"), calls);

        ShareSummaryVersionStore unused = mock(ShareSummaryVersionStore.class);
        ShareSummaryAssetLifecycle.submit(new RecordingExecutor(false), () -> { }, unused, () -> calls.add("rejected"));
        verify(unused, never()).independent(any());
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        private final boolean reject;
        private final List<Runnable> submitted = new ArrayList<>();

        private RecordingExecutor(boolean reject) {
            this.reject = reject;
        }

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("queue full");
            }
            submitted.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
