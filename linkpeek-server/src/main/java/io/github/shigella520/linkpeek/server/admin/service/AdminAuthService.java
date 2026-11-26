package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminAuthService {
    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    public static final String COOKIE_NAME = "LINKPEEK_ADMIN_SESSION";

    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int TOKEN_BYTES = 32;

    private final LinkPeekProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final AdminLoginThrottle throttle;

    public AdminAuthService(LinkPeekProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.throttle = new AdminLoginThrottle(clock);
    }

    public boolean isEnabled() {
        return StringUtils.hasText(properties.getStatsAdminPassword());
    }

    public LoginSession login(String password, HttpServletRequest request) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin backend is not enabled.");
        }
        String client = clientKey(request);
        Optional<Duration> locked = throttle.lockedFor(client);
        if (locked.isPresent()) {
            throw new LoginThrottledException(locked.get());
        }
        if (!passwordMatches(password)) {
            throttle.recordFailure(client);
            log.warn("admin_login_failed client={} locked={}", client, throttle.lockedFor(client).isPresent());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid management password.");
        }
        throttle.recordSuccess(client);

        cleanupExpiredSessions();
        String token = newToken();
        sessions.put(token, expiresAt());
        return new LoginSession(token, loginCookie(token).toString());
    }

    public void logout(HttpServletRequest request) {
        sessionToken(request).ifPresent(sessions::remove);
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        Optional<String> token = sessionToken(request);
        if (token.isEmpty()) {
            return false;
        }
        Long expiresAt = sessions.get(token.get());
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= Instant.now(clock).toEpochMilli()) {
            sessions.remove(token.get());
            return false;
        }
        return true;
    }

    public void requireAuthenticated(HttpServletRequest request) {
        if (!isAuthenticated(request)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin login is required.");
        }
    }

    public String logoutCookieHeader() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secureCookie())
                .path("/")
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    private ResponseCookie loginCookie(String token) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secureCookie())
                .path("/")
                .sameSite("Lax")
                .maxAge(SESSION_TTL)
                .build();
    }

    private boolean passwordMatches(String password) {
        if (password == null) {
            return false;
        }
        return MessageDigest.isEqual(
                properties.getStatsAdminPassword().getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code auto} marks the cookie Secure when the public base URL is https. The app usually sits
     * behind a TLS-terminating proxy, so the incoming request itself looks like plain http.
     */
    boolean secureCookie() {
        String mode = properties.getAdminCookieSecure() == null ? "auto" : properties.getAdminCookieSecure().strip().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "true" -> true;
            case "false" -> false;
            default -> properties.getBaseUrl() != null && properties.getBaseUrl().strip().toLowerCase(Locale.ROOT).startsWith("https://");
        };
    }

    /**
     * Forwarding headers are trusted only from a loopback or private-network peer (the reverse
     * proxy); a client talking to the app directly cannot pick its own throttle key.
     */
    static String clientKey(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = StringUtils.hasText(request.getRemoteAddr()) ? request.getRemoteAddr().strip() : "unknown";
        if (!isProxyAddress(remote)) {
            return remote;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.strip();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].strip();
        }
        return remote;
    }

    private static boolean isProxyAddress(String address) {
        // Only parse IP literals; never trigger a DNS lookup from a request value.
        if (!address.matches("[0-9.]+|[0-9A-Fa-f:]+")) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || parsed.isLinkLocalAddress()
                    || (parsed.getAddress().length == 16 && (parsed.getAddress()[0] & 0xfe) == 0xfc);
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    public static final class LoginThrottledException extends ResponseStatusException {
        private final Duration retryAfter;

        LoginThrottledException(Duration retryAfter) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later.");
            this.retryAfter = retryAfter;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
            return headers;
        }
    }

    private Optional<String> sessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (var cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long expiresAt() {
        return Instant.now(clock).plus(SESSION_TTL).toEpochMilli();
    }

    private void cleanupExpiredSessions() {
        long now = Instant.now(clock).toEpochMilli();
        Iterator<Map.Entry<String, Long>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    public record LoginSession(String token, String cookieHeader) {
    }
}
