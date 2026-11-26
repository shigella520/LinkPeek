package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryStatus;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Owns notification delivery records and webhook execution: queueing, sending, retry/backoff,
 * webhook URL (SSRF) validation, signing, manual retry and startup recovery of PENDING deliveries.
 */
@Service
public class NotificationDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    static final int SNAPSHOT_LIMIT = 8_000;
    static final int ERROR_LIMIT = 500;
    private static final int DATABASE_WRITE_ATTEMPTS = 5;
    static final Set<String> BLOCKED_HEADERS = Set.of(
            "host",
            "content-length",
            "connection",
            "transfer-encoding"
    );
    private static final long PENDING_REVIEW_AGE_MS = Duration.ofHours(24).toMillis();
    private static final int PENDING_RECOVERY_LIMIT = 500;
    static final String DELIVERY_ID_HEADER = "X-LinkPeek-Delivery-Id";

    private final NotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final LinkPeekProperties properties;
    private final Clock clock;
    private final Set<Long> activeDeliveries = ConcurrentHashMap.newKeySet();

    public NotificationDeliveryService(
            NotificationMapper notificationMapper,
            ObjectMapper objectMapper,
            @Qualifier("notificationWebhookHttpClient") HttpClient httpClient,
            @Qualifier("notificationWebhookExecutor") ExecutorService executor,
            LinkPeekProperties properties,
            Clock clock
    ) {
        this.notificationMapper = notificationMapper;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    public DeliveryPage deliveries(Integer page, Integer size, String eventType, Long taskId, Long channelId, String status) {
        int normalizedSize = normalizePageSize(size);
        int normalizedPage = page == null || page < 1 ? 1 : page;
        String normalizedEventType = normalizeEventTypeFilter(eventType);
        String normalizedStatus = normalizeDeliveryStatusFilter(status);
        long total = notificationMapper.countDeliveries(normalizedEventType, taskId, channelId, normalizedStatus);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / normalizedSize);
        int offset = (normalizedPage - 1) * normalizedSize;
        List<NotificationDeliveryRecord> items = notificationMapper.selectDeliveries(
                normalizedEventType,
                taskId,
                channelId,
                normalizedStatus,
                normalizedSize,
                offset
        );
        items.forEach(this::annotatePendingDelivery);
        return new DeliveryPage(items, normalizedPage, normalizedSize, total, totalPages);
    }

    public NotificationDeliveryRecord delivery(long deliveryId) {
        NotificationDeliveryRecord delivery = notificationMapper.selectDelivery(deliveryId);
        if (delivery == null) {
            throw new IllegalArgumentException("Notification delivery was not found.");
        }
        annotatePendingDelivery(delivery);
        return delivery;
    }

    private void annotatePendingDelivery(NotificationDeliveryRecord delivery) {
        delivery.setPendingExpired("PENDING".equals(delivery.getStatus())
                && !activeDeliveries.contains(delivery.getId())
                && delivery.getCreatedAt() < now() - PENDING_REVIEW_AGE_MS);
    }

    public NotificationDeleteResponse deleteDelivery(long deliveryId) {
        if (notificationMapper.selectDelivery(deliveryId) == null) {
            throw new IllegalArgumentException("Notification delivery was not found.");
        }
        return new NotificationDeleteResponse(notificationMapper.deleteDelivery(deliveryId));
    }

    /**
     * Re-submits deliveries left PENDING by a restart (the send queue lives in memory only).
     * Deliveries older than the review age are left for manual confirmation in the admin UI,
     * so a long outage never silently replays stale messages. Receivers can de-duplicate a
     * delivery that was sent but not recorded by the {@value #DELIVERY_ID_HEADER} header.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized int recoverPendingDeliveries() {
        List<NotificationDeliveryRecord> pending;
        try {
            pending = notificationMapper.selectPendingDeliveriesCreatedSince(now() - PENDING_REVIEW_AGE_MS, PENDING_RECOVERY_LIMIT);
        } catch (RuntimeException exception) {
            log.warn("notification_pending_recovery_query_failed message={}", exception.getMessage(), exception);
            return 0;
        }
        int resubmitted = 0;
        for (NotificationDeliveryRecord delivery : pending) {
            if (activeDeliveries.contains(delivery.getId())) {
                continue;
            }
            try {
                NotificationChannelRecord channel = notificationMapper.selectChannel(delivery.getChannelId());
                if (channel == null || !channel.isEnabled()) {
                    delivery.setStatus(NotificationDeliveryStatus.FAILED.name());
                    delivery.setErrorMessage("NOTIFICATION_CHANNEL_UNAVAILABLE");
                    delivery.setFinishedAt(now());
                    databaseWrite(() -> notificationMapper.updateDelivery(delivery));
                    continue;
                }
                submitDelivery(delivery.getId(), channel, delivery.getEventType(), now(), retryRequestBody(delivery));
                resubmitted++;
            } catch (RuntimeException exception) {
                log.warn("notification_pending_recovery_failed deliveryId={} message={}", delivery.getId(), exception.getMessage());
            }
        }
        if (!pending.isEmpty()) {
            log.info("notification_pending_recovered found={} resubmitted={}", pending.size(), resubmitted);
        }
        return resubmitted;
    }

    public synchronized NotificationDeliveryRecord retryDelivery(long deliveryId) {
        if (activeDeliveries.contains(deliveryId)) {
            throw new IllegalStateException("Notification delivery is already queued or sending.");
        }
        NotificationDeliveryRecord delivery = delivery(deliveryId);
        NotificationChannelRecord channel = existingChannel(delivery.getChannelId());
        String body = retryRequestBody(delivery);
        delivery.setStatus(NotificationDeliveryStatus.PENDING.name());
        delivery.setPendingExpired(false);
        delivery.setAttemptCount(0);
        delivery.setRequestUrl(channel.getUrl());
        delivery.setRequestBody(body);
        delivery.setRequestBodySnapshot(limit(body, SNAPSHOT_LIMIT));
        delivery.setResponseStatus(null);
        delivery.setResponseBodySnapshot(null);
        delivery.setErrorMessage(null);
        delivery.setDurationMs(0);
        delivery.setFinishedAt(null);
        if (notificationMapper.resetDeliveryForRetry(delivery) == 0) {
            throw new IllegalStateException("Notification delivery could not be reset for retry.");
        }
        submitDelivery(delivery.getId(), channel, delivery.getEventType(), now(), body);
        return delivery;
    }

    /** Inserts a new PENDING delivery record, retrying while SQLite reports the database as busy. */
    void insertDelivery(NotificationDeliveryRecord delivery) {
        databaseWrite(() -> {
            notificationMapper.insertDelivery(delivery);
            return null;
        });
    }

    void submitDelivery(long deliveryId, NotificationChannelRecord channel, String eventType, long occurredAt, String body) {
        if (!activeDeliveries.add(deliveryId)) return;
        try {
            executor.execute(() -> {
                try { deliver(deliveryId, channel, eventType, occurredAt, body); }
                finally { activeDeliveries.remove(deliveryId); }
            });
        } catch (RejectedExecutionException exception) {
            activeDeliveries.remove(deliveryId);
            NotificationDeliveryRecord delivery = notificationMapper.selectDelivery(deliveryId);
            if (delivery != null) {
                delivery.setStatus(NotificationDeliveryStatus.FAILED.name());
                delivery.setErrorMessage("NOTIFICATION_QUEUE_FULL");
                delivery.setFinishedAt(now());
                databaseWrite(() -> notificationMapper.updateDelivery(delivery));
            }
        }
    }

    private void deliver(long deliveryId, NotificationChannelRecord channel, String eventType, long occurredAt, String body) {
        NotificationDeliveryRecord delivery = notificationMapper.selectDelivery(deliveryId);
        if (delivery == null) {
            return;
        }
        int attempts = 0;
        SendResult result = null;
        do {
            attempts++;
            result = sendWebhook(deliveryId, channel, eventType, occurredAt, body);
            if (result.success() || !result.retryable()) {
                break;
            }
            if (attempts < 3) sleepBeforeRetry(attempts);
        } while (attempts < 3);

        delivery.setAttemptCount(attempts);
        delivery.setStatus(result != null && result.success() ? NotificationDeliveryStatus.SUCCESS.name() : NotificationDeliveryStatus.FAILED.name());
        delivery.setResponseStatus(result == null ? null : result.statusCode());
        delivery.setResponseBodySnapshot(result == null ? null : limit(result.responseBody(), SNAPSHOT_LIMIT));
        delivery.setErrorMessage(result == null ? "Webhook delivery failed." : limit(result.errorMessage(), ERROR_LIMIT));
        delivery.setDurationMs(result == null ? 0 : result.durationMs());
        delivery.setFinishedAt(now());
        databaseWrite(() -> notificationMapper.updateDelivery(delivery));
    }

    private <T> T databaseWrite(Supplier<T> writeOperation) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= DATABASE_WRITE_ATTEMPTS; attempt++) {
            try {
                return writeOperation.get();
            } catch (RuntimeException exception) {
                if (!isDatabaseBusy(exception) || attempt == DATABASE_WRITE_ATTEMPTS) {
                    throw exception;
                }
                lastException = exception;
                sleepBeforeDatabaseRetry(attempt);
            }
        }
        throw lastException == null ? new IllegalStateException("Database write failed.") : lastException;
    }

    private boolean isDatabaseBusy(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY") || message.contains("database is locked"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Sends one webhook request without retrying. {@code deliveryId} is null for channel tests,
     * which therefore carry no {@value #DELIVERY_ID_HEADER} header.
     */
    SendResult sendWebhook(Long deliveryId, NotificationChannelRecord channel, String eventType, long occurredAt, String body) {
        long startedAt = System.nanoTime();
        try {
            URI uri = URI.create(channel.getUrl());
            validateWebhookUri(uri);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(channel.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LinkPeek-Webhook/1.0")
                    .header("X-LinkPeek-Event", eventType)
                    .header("X-LinkPeek-Timestamp", String.valueOf(occurredAt));
            if (deliveryId != null) {
                builder.header(DELIVERY_ID_HEADER, String.valueOf(deliveryId));
            }
            applyHeaders(builder, channel.getHeadersJson());
            if (StringUtils.hasText(channel.getSecret())) {
                builder.header("X-LinkPeek-Signature", signature(channel.getSecret(), occurredAt, body));
            }
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            boolean retryable = isRetryableStatus(response.statusCode());
            return new SendResult(success, retryable, response.statusCode(), response.body(), success ? null : "Webhook returned HTTP " + response.statusCode(), elapsedMillis(startedAt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SendResult(false, true, null, null, "Webhook delivery was interrupted.", elapsedMillis(startedAt));
        } catch (JsonProcessingException exception) {
            // Malformed channel header configuration: retrying cannot fix it.
            return new SendResult(false, false, null, null, "Webhook headers JSON is invalid.", elapsedMillis(startedAt));
        } catch (IOException exception) {
            return new SendResult(false, true, null, null, exception.getMessage(), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            return new SendResult(false, false, null, null, exception.getMessage(), elapsedMillis(startedAt));
        }
    }

    /** Server errors, timeouts and rate limiting are transient; other 4xx mean the request itself is wrong. */
    static boolean isRetryableStatus(int statusCode) {
        return statusCode >= 500 || statusCode == 408 || statusCode == 425 || statusCode == 429;
    }

    private void applyHeaders(HttpRequest.Builder builder, String headersJson) throws JsonProcessingException {
        if (!StringUtils.hasText(headersJson)) {
            return;
        }
        JsonNode headers = objectMapper.readTree(headersJson);
        if (!headers.isObject()) {
            throw new IllegalArgumentException("Webhook headers must be a JSON object.");
        }
        headers.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if (BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Webhook header is not allowed: " + name);
            }
            JsonNode value = entry.getValue();
            if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) {
                throw new IllegalArgumentException("Webhook header value must be scalar: " + name);
            }
            builder.header(name, value.asText());
        });
    }

    /** Rejects non-http(s) URLs and hosts resolving to special or (unless allow-listed) private addresses. */
    void validateWebhookUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Webhook URL must use http or https.");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("Webhook URL host is required.");
        }
        for (InetAddress address : resolveWebhookHost(host)) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Webhook URL host is not allowed.");
            }
            if (address.isSiteLocalAddress() && properties.getWebhookAllowedPrivateHosts().stream()
                    .filter(StringUtils::hasText)
                    .noneMatch(allowedHost -> host.equalsIgnoreCase(allowedHost.strip()))) {
                throw new IllegalArgumentException("Webhook URL host is private and is not allowed. "
                        + "Configure WEBHOOK_ALLOWED_PRIVATE_HOSTS to allow this host.");
            }
        }
    }

    InetAddress[] resolveWebhookHost(String host) throws IOException {
        return InetAddress.getAllByName(host);
    }

    private String signature(String secret, long occurredAt, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((occurredAt + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (RuntimeException | java.security.GeneralSecurityException exception) {
            throw new IllegalArgumentException("Webhook signature could not be generated.", exception);
        }
    }

    private NotificationChannelRecord existingChannel(long channelId) {
        NotificationChannelRecord channel = notificationMapper.selectChannel(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Notification channel was not found.");
        }
        return channel;
    }

    private String retryRequestBody(NotificationDeliveryRecord delivery) {
        if (StringUtils.hasText(delivery.getRequestBody())) {
            return delivery.getRequestBody();
        }
        String snapshot = delivery.getRequestBodySnapshot();
        if (!StringUtils.hasText(snapshot)) {
            throw new IllegalStateException("Notification delivery request body is not available.");
        }
        if (snapshot.length() >= SNAPSHOT_LIMIT) {
            throw new IllegalStateException("Notification delivery request body snapshot may be truncated and cannot be retried.");
        }
        return snapshot;
    }

    private String normalizeEventTypeFilter(String eventType) {
        if (!StringUtils.hasText(eventType)) {
            return null;
        }
        return NotificationEventType.fromValue(eventType).name();
    }

    private String normalizeDeliveryStatusFilter(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        return NotificationDeliveryStatus.valueOf(status.strip().toUpperCase(Locale.ROOT)).name();
    }

    private int normalizePageSize(Integer size) {
        int value = size == null || size < 1 ? DEFAULT_PAGE_SIZE : size;
        return Math.min(value, MAX_PAGE_SIZE);
    }

    static String limit(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String stripped = value.strip();
        return stripped.length() <= limit ? stripped : stripped.substring(0, limit);
    }

    static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private long now() {
        return Instant.now(clock).toEpochMilli();
    }

    void sleepBeforeRetry(int attempts) {
        try {
            Thread.sleep(switch (attempts) {
                case 1 -> 1_000L;
                case 2 -> 5_000L;
                default -> 30_000L;
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleepBeforeDatabaseRetry(int attempt) {
        try {
            Thread.sleep(25L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying database write.", exception);
        }
    }

    public record DeliveryPage(
            List<NotificationDeliveryRecord> items,
            int page,
            int size,
            long total,
            int totalPages
    ) {
    }

    record SendResult(
            boolean success,
            boolean retryable,
            Integer statusCode,
            String responseBody,
            String errorMessage,
            long durationMs
    ) {
    }
}
