package io.github.shigella520.linkpeek.server.admin.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing per client. After {@link #FREE_ATTEMPTS} consecutive failures a
 * client is locked out for one minute, doubling with every further failure up to fifteen minutes.
 * A successful login clears the client's record. Records are dropped after a quiet window that
 * starts when both the last failure and the lockout are over, and the map is capped so a flood of clients cannot grow it unbounded.
 */
final class AdminLoginThrottle {
    static final int FREE_ATTEMPTS = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration FIRST_LOCKOUT = Duration.ofMinutes(1);
    static final Duration MAX_LOCKOUT = Duration.ofMinutes(15);
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Clock clock;
    private final Map<String, Attempts> clients = new ConcurrentHashMap<>();

    AdminLoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Remaining lockout for this client, if it is currently locked out. */
    Optional<Duration> lockedFor(String client) {
        Attempts attempts = clients.get(client);
        if (attempts == null) {
            return Optional.empty();
        }
        long remaining = attempts.lockedUntil - now();
        return remaining > 0 ? Optional.of(Duration.ofMillis(remaining)) : Optional.empty();
    }

    void recordFailure(String client) {
        long now = now();
        if (clients.size() >= MAX_TRACKED_CLIENTS) {
            clients.values().removeIf(attempts -> attempts.expired(now));
        }
        clients.compute(client, (ignored, existing) -> {
            Attempts attempts = existing == null || existing.expired(now) ? new Attempts() : existing;
            attempts.failures++;
            attempts.lastFailureAt = now;
            int overLimit = attempts.failures - FREE_ATTEMPTS;
            if (overLimit >= 0) {
                long lockout = FIRST_LOCKOUT.toMillis() << Math.min(overLimit, 10);
                attempts.lockedUntil = now + Math.min(lockout, MAX_LOCKOUT.toMillis());
            }
            return attempts;
        });
    }

    void recordSuccess(String client) {
        clients.remove(client);
    }

    int trackedClients() {
        return clients.size();
    }

    private long now() {
        return clock.millis();
    }

    private static final class Attempts {
        private int failures;
        private long lastFailureAt;
        private long lockedUntil;

        // Forget a client only after a quiet window following its last failure *and* its lockout,
        // otherwise the longest lockout would end exactly when the count resets.
        private boolean expired(long now) {
            return Math.max(lastFailureAt, lockedUntil) + FAILURE_WINDOW.toMillis() <= now;
        }
    }
}
