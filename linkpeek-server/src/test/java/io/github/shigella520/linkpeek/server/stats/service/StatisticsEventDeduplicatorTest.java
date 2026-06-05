package io.github.shigella520.linkpeek.server.stats.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsClientType;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsEventDeduplicatorTest {
    @Test
    void releasingExpiredClaimDoesNotRemoveNewClaim() {
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setStatsEventDedupeTtl(Duration.ofMillis(100));
        StatisticsEventDeduplicator deduplicator = new StatisticsEventDeduplicator(properties);
        StatisticsEventDeduplicator.EventKey key = new StatisticsEventDeduplicator.EventKey(
                "preview-key",
                "provider",
                StatisticsEventType.PREVIEW_CREATED,
                StatisticsClientType.CRAWLER,
                200,
                "https://example.com/watch/1",
                null
        );

        StatisticsEventDeduplicator.Claim firstClaim = deduplicator.tryClaimPreviewCreated(key, 1_000);
        StatisticsEventDeduplicator.Claim duplicateClaim = deduplicator.tryClaimPreviewCreated(key, 1_050);
        StatisticsEventDeduplicator.Claim nextClaim = deduplicator.tryClaimPreviewCreated(key, 1_101);

        assertTrue(firstClaim.acquired());
        assertFalse(duplicateClaim.acquired());
        assertTrue(nextClaim.acquired());

        deduplicator.release(firstClaim);

        assertFalse(deduplicator.tryClaimPreviewCreated(key, 1_102).acquired());
    }
}
