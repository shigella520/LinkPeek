package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminLoginThrottleTest {
    private final MutableClock clock = new MutableClock();

    @Test
    void locksAfterFiveFailuresAndDoublesUpToFifteenMinutes() {
        AdminLoginThrottle throttle = new AdminLoginThrottle(clock);
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("a");
            assertTrue(throttle.lockedFor("a").isEmpty());
        }
        throttle.recordFailure("a");
        assertEquals(Optional.of(Duration.ofMinutes(1)), throttle.lockedFor("a"));
        assertTrue(throttle.lockedFor("b").isEmpty(), "other clients are unaffected");

        long[] expectedMinutes = {2, 4, 8, 15, 15};
        for (long minutes : expectedMinutes) {
            clock.advance(throttle.lockedFor("a").orElseThrow());
            assertTrue(throttle.lockedFor("a").isEmpty());
            throttle.recordFailure("a");
            assertEquals(Optional.of(Duration.ofMinutes(minutes)), throttle.lockedFor("a"));
        }
    }

    @Test
    void successClearsAndQuietPeriodForgetsFailures() {
        AdminLoginThrottle throttle = new AdminLoginThrottle(clock);
        for (int i = 0; i < 4; i++) throttle.recordFailure("a");
        throttle.recordSuccess("a");
        for (int i = 0; i < 4; i++) throttle.recordFailure("a");
        assertTrue(throttle.lockedFor("a").isEmpty(), "success reset the count");

        clock.advance(AdminLoginThrottle.FAILURE_WINDOW);
        throttle.recordFailure("a");
        assertTrue(throttle.lockedFor("a").isEmpty(), "failures older than the window are forgotten");
    }

    @Test
    void expiredClientsArePrunedWhenTheMapIsFull() {
        AdminLoginThrottle throttle = new AdminLoginThrottle(clock);
        for (int i = 0; i < AdminLoginThrottle.MAX_TRACKED_CLIENTS; i++) throttle.recordFailure("c" + i);
        clock.advance(AdminLoginThrottle.FAILURE_WINDOW.plusSeconds(1));
        throttle.recordFailure("new");
        assertEquals(1, throttle.trackedClients());
    }

    @Test
    void forwardingHeadersAreTrustedOnlyFromProxyPeers() {
        MockHttpServletRequest direct = request("198.51.100.7");
        direct.addHeader("X-Real-IP", "1.2.3.4");
        assertEquals("198.51.100.7", AdminAuthService.clientKey(direct));

        MockHttpServletRequest viaProxy = request("192.168.31.5");
        viaProxy.addHeader("X-Real-IP", "69.63.200.84");
        viaProxy.addHeader("X-Forwarded-For", "9.9.9.9");
        assertEquals("69.63.200.84", AdminAuthService.clientKey(viaProxy));

        MockHttpServletRequest forwardedOnly = request("127.0.0.1");
        forwardedOnly.addHeader("X-Forwarded-For", "69.63.200.84, 192.168.31.5");
        assertEquals("69.63.200.84", AdminAuthService.clientKey(forwardedOnly));

        assertEquals("192.168.31.5", AdminAuthService.clientKey(request("192.168.31.5")));
        assertEquals("unknown", AdminAuthService.clientKey(null));
    }

    @Test
    void secureCookieFollowsBaseUrlUnlessOverridden() {
        assertTrue(authService("https://linkpeek-dev.jianyutan.com", "auto").secureCookie());
        assertFalse(authService("http://192.168.31.5:51593", "auto").secureCookie());
        assertTrue(authService("http://localhost:8080", "true").secureCookie());
        assertFalse(authService("https://linkpeek.example.com", "false").secureCookie());
        assertTrue(authService("https://linkpeek.example.com", null).secureCookie());
    }

    private AdminAuthService authService(String baseUrl, String secure) {
        LinkPeekProperties properties = new LinkPeekProperties();
        properties.setBaseUrl(baseUrl);
        properties.setAdminCookieSecure(secure);
        return new AdminAuthService(properties, clock);
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-26T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
