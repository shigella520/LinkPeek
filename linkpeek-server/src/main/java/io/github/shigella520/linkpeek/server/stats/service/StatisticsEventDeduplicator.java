package io.github.shigella520.linkpeek.server.stats.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsClientType;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsEventType;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class StatisticsEventDeduplicator {
    private static final long MIN_CLEANUP_INTERVAL_MILLIS = 1_000;
    private static final long MAX_CLEANUP_INTERVAL_MILLIS = 60_000;

    private final LinkPeekProperties properties;
    private final ConcurrentHashMap<EventKey, Long> eventExpirations = new ConcurrentHashMap<>();
    private final AtomicLong nextCleanupAt = new AtomicLong();

    public StatisticsEventDeduplicator(LinkPeekProperties properties) {
        this.properties = properties;
    }

    public Claim tryClaimPreviewCreated(EventKey key, long nowMillis) {
        long ttlMillis = ttlMillis();
        int maxEntries = properties.getStatsEventDedupeMaxEntries();
        if (ttlMillis <= 0 || maxEntries <= 0) {
            return Claim.untracked(key);
        }

        cleanupIfNeeded(nowMillis, ttlMillis, maxEntries);

        AtomicBoolean claimed = new AtomicBoolean();
        long expiresAt = nowMillis + ttlMillis;
        eventExpirations.compute(key, (ignored, existingExpiresAt) -> {
            if (existingExpiresAt != null && existingExpiresAt > nowMillis) {
                return existingExpiresAt;
            }
            claimed.set(true);
            return expiresAt;
        });

        if (claimed.get()) {
            trimIfNeeded(maxEntries);
        }
        return new Claim(key, claimed.get(), claimed.get(), expiresAt);
    }

    public void release(Claim claim) {
        if (claim != null && claim.tracked()) {
            eventExpirations.remove(claim.key(), claim.expiresAt());
        }
    }

    public void forgetPreviewKey(String previewKey) {
        if (previewKey == null || previewKey.isBlank()) {
            return;
        }
        eventExpirations.keySet().removeIf(key -> previewKey.equals(key.previewKey()));
    }

    public void clear() {
        eventExpirations.clear();
    }

    private long ttlMillis() {
        Duration ttl = properties.getStatsEventDedupeTtl();
        return ttl == null ? 0 : Math.max(0, ttl.toMillis());
    }

    private void cleanupIfNeeded(long nowMillis, long ttlMillis, int maxEntries) {
        if (eventExpirations.size() <= maxEntries && nowMillis < nextCleanupAt.get()) {
            return;
        }
        long interval = Math.min(MAX_CLEANUP_INTERVAL_MILLIS, Math.max(MIN_CLEANUP_INTERVAL_MILLIS, ttlMillis));
        if (!nextCleanupAt.compareAndSet(nextCleanupAt.get(), nowMillis + interval)
                && eventExpirations.size() <= maxEntries) {
            return;
        }
        eventExpirations.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        trimIfNeeded(maxEntries);
    }

    private void trimIfNeeded(int maxEntries) {
        int overflow = eventExpirations.size() - maxEntries;
        if (overflow <= 0) {
            return;
        }
        eventExpirations.entrySet()
                .stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .limit(overflow)
                .forEach(entry -> eventExpirations.remove(entry.getKey(), entry.getValue()));
    }

    public record EventKey(
            String previewKey,
            String providerId,
            StatisticsEventType eventType,
            StatisticsClientType clientType,
            int httpStatus,
            String sourceUrl,
            String requestedStyle
    ) {
        public EventKey {
            requestedStyle = requestedStyle == null || requestedStyle.isBlank() ? null : requestedStyle;
        }
    }

    public record Claim(EventKey key, boolean acquired, boolean tracked, long expiresAt) {
        private static Claim untracked(EventKey key) {
            return new Claim(key, true, false, 0);
        }

        public Claim {
            Objects.requireNonNull(key, "key");
        }
    }
}
