package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryStatus;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import io.github.shigella520.linkpeek.server.admin.model.NotificationTaskRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";
    private static final String METHOD_POST = "POST";
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int SNAPSHOT_LIMIT = 8_000;
    private static final int ERROR_LIMIT = 500;
    private static final String DEFAULT_CHANNEL_BODY_TEMPLATE = "{{message.bodyJson}}";
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "host",
            "content-length",
            "connection",
            "transfer-encoding"
    );
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final NotificationMapper notificationMapper;
    private final NotificationTemplateService templateService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final LinkPeekProperties properties;
    private final Clock clock;

    public NotificationService(
            NotificationMapper notificationMapper,
            NotificationTemplateService templateService,
            ObjectMapper objectMapper,
            @Qualifier("notificationWebhookHttpClient") HttpClient httpClient,
            @Qualifier("notificationWebhookExecutor") ExecutorService executor,
            LinkPeekProperties properties,
            Clock clock
    ) {
        this.notificationMapper = notificationMapper;
        this.templateService = templateService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    public List<NotificationTemplateService.EventSchema> events() {
        return templateService.events();
    }

    public NotificationTemplateService.EventSchema eventSchema(String eventType) {
        return templateService.schema(NotificationEventType.fromValue(eventType));
    }

    public List<ChannelResponse> channels() {
        return notificationMapper.selectChannels().stream()
                .map(ChannelResponse::fromRecord)
                .toList();
    }

    @Transactional
    public ChannelResponse createChannel(ChannelRequest request) {
        NotificationChannelRecord channel = normalizeChannel(null, request);
        long now = now();
        channel.setCreatedAt(now);
        channel.setUpdatedAt(now);
        notificationMapper.insertChannel(channel);
        return ChannelResponse.fromRecord(notificationMapper.selectChannel(channel.getId()));
    }

    @Transactional
    public ChannelResponse updateChannel(long channelId, ChannelRequest request) {
        NotificationChannelRecord existing = existingChannel(channelId);
        NotificationChannelRecord channel = normalizeChannel(existing, request);
        channel.setId(channelId);
        channel.setCreatedAt(existing.getCreatedAt());
        channel.setUpdatedAt(now());
        if (notificationMapper.updateChannel(channel) == 0) {
            throw new IllegalArgumentException("Notification channel was not found.");
        }
        return ChannelResponse.fromRecord(notificationMapper.selectChannel(channelId));
    }

    public DeleteResponse deleteChannel(long channelId) {
        existingChannel(channelId);
        if (notificationMapper.countTaskChannelsForChannel(channelId) > 0) {
            throw new IllegalStateException("Notification channel is still used by notification tasks.");
        }
        return new DeleteResponse(notificationMapper.deleteChannel(channelId));
    }

    public TestResponse testChannel(long channelId) {
        NotificationChannelRecord channel = existingChannel(channelId);
        String messageBody = """
                {"event":"TEST","message":"LinkPeek webhook test"}
                """.strip();
        long startedAt = System.nanoTime();
        try {
            String body = templateService.renderChannelBody(
                    channel.getBodyTemplate(),
                    messageBody
            );
            SendResult result = sendWebhook(channel, NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS.name(), now(), body);
            return new TestResponse(
                    result.success(),
                    result.success() ? "测试成功。" : "测试失败。",
                    result.statusCode(),
                    result.errorMessage(),
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException exception) {
            return new TestResponse(false, "测试失败。", null, limit(exception.getMessage(), ERROR_LIMIT), elapsedMillis(startedAt));
        }
    }

    public List<TaskResponse> tasks() {
        return notificationMapper.selectTasks().stream()
                .map(this::taskResponse)
                .toList();
    }

    @Transactional
    public TaskResponse createTask(TaskRequest request) {
        NormalizedTask normalized = normalizeTask(null, request);
        long now = now();
        normalized.task().setCreatedAt(now);
        normalized.task().setUpdatedAt(now);
        notificationMapper.insertTask(normalized.task());
        replaceTaskChannels(normalized.task().getId(), normalized.channelIds());
        return taskResponse(notificationMapper.selectTask(normalized.task().getId()));
    }

    @Transactional
    public TaskResponse updateTask(long taskId, TaskRequest request) {
        NotificationTaskRecord existing = existingTask(taskId);
        NormalizedTask normalized = normalizeTask(existing, request);
        normalized.task().setId(taskId);
        normalized.task().setCreatedAt(existing.getCreatedAt());
        normalized.task().setUpdatedAt(now());
        if (notificationMapper.updateTask(normalized.task()) == 0) {
            throw new IllegalArgumentException("Notification task was not found.");
        }
        replaceTaskChannels(taskId, normalized.channelIds());
        return taskResponse(notificationMapper.selectTask(taskId));
    }

    @Transactional
    public DeleteResponse deleteTask(long taskId) {
        existingTask(taskId);
        notificationMapper.deleteTaskChannels(taskId);
        return new DeleteResponse(notificationMapper.deleteTask(taskId));
    }

    public NotificationTemplateService.TemplateValidationResult validateTemplate(ValidateTemplateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Template validation payload is required.");
        }
        return templateService.validate(request.eventType(), request.templateJson());
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
        return new DeliveryPage(items, normalizedPage, normalizedSize, total, totalPages);
    }

    public NotificationDeliveryRecord delivery(long deliveryId) {
        NotificationDeliveryRecord delivery = notificationMapper.selectDelivery(deliveryId);
        if (delivery == null) {
            throw new IllegalArgumentException("Notification delivery was not found.");
        }
        return delivery;
    }

    public DeleteResponse deleteDelivery(long deliveryId) {
        if (notificationMapper.selectDelivery(deliveryId) == null) {
            throw new IllegalArgumentException("Notification delivery was not found.");
        }
        return new DeleteResponse(notificationMapper.deleteDelivery(deliveryId));
    }

    public NotificationDeliveryRecord retryDelivery(long deliveryId) {
        NotificationDeliveryRecord delivery = delivery(deliveryId);
        NotificationChannelRecord channel = existingChannel(delivery.getChannelId());
        String body = retryRequestBody(delivery);
        delivery.setStatus(NotificationDeliveryStatus.PENDING.name());
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

    public void publishShareSummaryImageSuccess(ShareSummaryRunRecord run, ShareSummaryImageRecord image) {
        if (run == null || image == null || image.getId() == null) {
            return;
        }
        NotificationEventType eventType = NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS;
        long occurredAt = now();
        String eventKey = eventType.name() + ":" + image.getId();
        Map<String, Object> values = shareSummaryImageValues(eventType, eventKey, occurredAt, run, image);
        for (NotificationTaskRecord task : notificationMapper.selectEnabledTasksByEventType(eventType.name())) {
            try {
                if (!matches(task, run)) {
                    continue;
                }
                publishTask(eventType, eventKey, occurredAt, values, task);
            } catch (RuntimeException exception) {
                log.warn("notification_task_publish_failed taskId={} eventKey={} message={}", task.getId(), eventKey, exception.getMessage(), exception);
            }
        }
    }

    private void publishTask(
            NotificationEventType eventType,
            String eventKey,
            long occurredAt,
            Map<String, Object> values,
            NotificationTaskRecord task
    ) {
        String messageBody = templateService.render(eventType, task.getTemplateJson(), values);
        for (NotificationChannelRecord channel : notificationMapper.selectEnabledChannelsForTask(task.getId())) {
            String body = templateService.renderChannelBody(channel.getBodyTemplate(), messageBody);
            NotificationDeliveryRecord delivery = new NotificationDeliveryRecord();
            delivery.setEventType(eventType.name());
            delivery.setEventKey(eventKey);
            delivery.setNotificationTaskId(task.getId());
            delivery.setNotificationTaskName(task.getName());
            delivery.setChannelId(channel.getId());
            delivery.setChannelName(channel.getName());
            delivery.setStatus(NotificationDeliveryStatus.PENDING.name());
            delivery.setAttemptCount(0);
            delivery.setRequestUrl(channel.getUrl());
            delivery.setRequestBody(body);
            delivery.setRequestBodySnapshot(limit(body, SNAPSHOT_LIMIT));
            delivery.setDurationMs(0);
            delivery.setCreatedAt(now());
            notificationMapper.insertDelivery(delivery);
            submitDelivery(delivery.getId(), channel, eventType.name(), occurredAt, body);
        }
    }

    private void submitDelivery(long deliveryId, NotificationChannelRecord channel, String eventType, long occurredAt, String body) {
        try {
            executor.execute(() -> deliver(deliveryId, channel, eventType, occurredAt, body));
        } catch (RejectedExecutionException exception) {
            NotificationDeliveryRecord delivery = notificationMapper.selectDelivery(deliveryId);
            if (delivery != null) {
                delivery.setStatus(NotificationDeliveryStatus.FAILED.name());
                delivery.setErrorMessage("NOTIFICATION_QUEUE_FULL");
                delivery.setFinishedAt(now());
                notificationMapper.updateDelivery(delivery);
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
            result = sendWebhook(channel, eventType, occurredAt, body);
            if (result.success() || !result.retryable()) {
                break;
            }
            sleepBeforeRetry(attempts);
        } while (attempts < 3);

        delivery.setAttemptCount(attempts);
        delivery.setStatus(result != null && result.success() ? NotificationDeliveryStatus.SUCCESS.name() : NotificationDeliveryStatus.FAILED.name());
        delivery.setResponseStatus(result == null ? null : result.statusCode());
        delivery.setResponseBodySnapshot(result == null ? null : limit(result.responseBody(), SNAPSHOT_LIMIT));
        delivery.setErrorMessage(result == null ? "Webhook delivery failed." : limit(result.errorMessage(), ERROR_LIMIT));
        delivery.setDurationMs(result == null ? 0 : result.durationMs());
        delivery.setFinishedAt(now());
        notificationMapper.updateDelivery(delivery);
    }

    private SendResult sendWebhook(NotificationChannelRecord channel, String eventType, long occurredAt, String body) {
        long startedAt = System.nanoTime();
        try {
            URI uri = URI.create(channel.getUrl());
            try {
                validateWebhookUri(uri);
            } catch (IOException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(channel.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LinkPeek-Webhook/1.0")
                    .header("X-LinkPeek-Event", eventType)
                    .header("X-LinkPeek-Timestamp", String.valueOf(occurredAt));
            applyHeaders(builder, channel.getHeadersJson());
            if (StringUtils.hasText(channel.getSecret())) {
                builder.header("X-LinkPeek-Signature", signature(channel.getSecret(), occurredAt, body));
            }
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            boolean retryable = response.statusCode() >= 500;
            return new SendResult(success, retryable, response.statusCode(), response.body(), success ? null : "Webhook returned HTTP " + response.statusCode(), elapsedMillis(startedAt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SendResult(false, true, null, null, "Webhook delivery was interrupted.", elapsedMillis(startedAt));
        } catch (IOException exception) {
            return new SendResult(false, true, null, null, exception.getMessage(), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            return new SendResult(false, false, null, null, exception.getMessage(), elapsedMillis(startedAt));
        }
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

    private void validateWebhookUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Webhook URL must use http or https.");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IOException("Webhook URL host is required.");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("Webhook URL host is not allowed.");
            }
        }
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

    private boolean matches(NotificationTaskRecord task, ShareSummaryRunRecord run) {
        Filters filters = filters(task.getFiltersJson());
        return matchesLong(filters.shareSummaryTaskIds(), run.getTaskId())
                && matchesString(filters.periodTypes(), run.getPeriodType())
                && matchesString(filters.triggerTypes(), run.getTriggerType());
    }

    private boolean matchesLong(List<Long> allowed, long value) {
        return allowed == null || allowed.isEmpty() || allowed.contains(value);
    }

    private boolean matchesString(List<String> allowed, String value) {
        return allowed == null || allowed.isEmpty() || allowed.contains(value);
    }

    private Map<String, Object> shareSummaryImageValues(
            NotificationEventType eventType,
            String eventKey,
            long occurredAt,
            ShareSummaryRunRecord run,
            ShareSummaryImageRecord image
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("event.type", eventType.name());
        values.put("event.key", eventKey);
        values.put("event.occurredAt", occurredAt);
        values.put("event.occurredAtIso", Instant.ofEpochMilli(occurredAt).toString());
        values.put("run.id", run.getId());
        values.put("run.taskId", run.getTaskId());
        values.put("run.taskName", run.getTaskName());
        values.put("run.triggerType", run.getTriggerType());
        values.put("run.periodType", run.getPeriodType());
        values.put("run.windowStart", run.getWindowStart());
        values.put("run.windowEnd", run.getWindowEnd());
        values.put("run.windowStartLabel", dateLabel(run.getWindowStart()));
        values.put("run.windowEndLabel", dateLabel(run.getWindowEnd()));
        values.put("run.status", run.getStatus());
        values.put("run.linkCount", run.getLinkCount());
        values.put("run.uniqueLinkCount", run.getUniqueLinkCount());
        values.put("run.inputLinkCount", run.getInputLinkCount());
        values.put("run.aiProviderNames", run.getAiProviderNames());
        values.put("run.aiDurationMs", run.getAiDurationMs());
        values.put("run.report", run.getReport());
        values.put("image.id", image.getId());
        values.put("image.runId", image.getRunId());
        values.put("image.attemptNo", image.getAttemptNo());
        values.put("image.status", image.getStatus());
        values.put("image.providerType", image.getProviderType());
        values.put("image.model", image.getModel());
        values.put("image.imageSize", image.getImageSize());
        values.put("image.outputFormat", image.getOutputFormat());
        values.put("image.quality", image.getQuality());
        values.put("image.imageUrl", image.getImageUrl());
        values.put("image.ogImageUrl", image.getOgImageUrl());
        values.put("image.ogPageUrl", image.getOgPageUrl());
        values.put("image.ogShareUrl", image.getOgPageUrl());
        values.put("image.ogTitle", image.getOgTitle());
        values.put("image.ogDescription", image.getOgDescription());
        values.put("image.durationMs", image.getDurationMs());
        values.put("image.createdAt", image.getCreatedAt());
        values.put("image.startedAt", image.getStartedAt());
        values.put("image.finishedAt", image.getFinishedAt());
        values.put("system.baseUrl", baseUrl());
        values.put("system.appName", "LinkPeek");
        return values;
    }

    private NotificationChannelRecord normalizeChannel(NotificationChannelRecord existing, ChannelRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Notification channel payload is required.");
        }
        NotificationChannelRecord channel = new NotificationChannelRecord();
        channel.setName(required(request.name(), "Channel name"));
        channel.setEnabled(request.enabled() == null ? existing == null || existing.isEnabled() : request.enabled());
        channel.setType(CHANNEL_TYPE_WEBHOOK);
        channel.setMethod(METHOD_POST);
        channel.setUrl(normalizeUrl(request.url()));
        channel.setHeadersJson(normalizeHeaders(request.headersJson()));
        channel.setBodyTemplate(normalizeChannelBodyTemplate(request.bodyTemplate()));
        String secret = optionalStrip(request.secret());
        if (!StringUtils.hasText(secret) && existing != null && StringUtils.hasText(existing.getSecret())) {
            secret = existing.getSecret();
        }
        channel.setSecret(secret);
        channel.setTimeoutSeconds(normalizeTimeout(request.timeoutSeconds()));
        return channel;
    }

    private NormalizedTask normalizeTask(NotificationTaskRecord existing, TaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Notification task payload is required.");
        }
        NotificationEventType eventType = NotificationEventType.fromValue(request.eventType());
        List<Long> channelIds = normalizeChannelIds(request.channelIds());
        NotificationTaskRecord task = new NotificationTaskRecord();
        task.setName(required(request.name(), "Task name"));
        task.setEnabled(request.enabled() == null ? existing == null || existing.isEnabled() : request.enabled());
        task.setEventType(eventType.name());
        task.setFiltersJson(normalizeFilters(request.filters()));
        task.setTemplateJson(templateService.normalizeTemplate(eventType, request.templateJson()));
        return new NormalizedTask(task, channelIds);
    }

    private void replaceTaskChannels(long taskId, List<Long> channelIds) {
        notificationMapper.deleteTaskChannels(taskId);
        for (Long channelId : channelIds) {
            existingChannel(channelId);
            notificationMapper.insertTaskChannel(taskId, channelId);
        }
    }

    private NotificationChannelRecord existingChannel(long channelId) {
        NotificationChannelRecord channel = notificationMapper.selectChannel(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Notification channel was not found.");
        }
        return channel;
    }

    private NotificationTaskRecord existingTask(long taskId) {
        NotificationTaskRecord task = notificationMapper.selectTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Notification task was not found.");
        }
        return task;
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

    private TaskResponse taskResponse(NotificationTaskRecord task) {
        return TaskResponse.fromRecord(task, notificationMapper.selectChannelIdsForTask(task.getId()));
    }

    private String normalizeUrl(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Webhook URL must not be blank.");
        }
        String stripped = value.strip();
        URI uri = URI.create(stripped);
        try {
            validateWebhookUri(uri);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        return stripped;
    }

    private String normalizeHeaders(JsonNode headers) {
        if (headers == null || headers.isNull()) {
            return null;
        }
        if (!headers.isObject()) {
            throw new IllegalArgumentException("Webhook headers must be a JSON object.");
        }
        headers.fields().forEachRemaining(entry -> {
            if (BLOCKED_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Webhook header is not allowed: " + entry.getKey());
            }
            if (!entry.getValue().isTextual() && !entry.getValue().isNumber() && !entry.getValue().isBoolean()) {
                throw new IllegalArgumentException("Webhook header value must be scalar: " + entry.getKey());
            }
        });
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Webhook headers must be valid JSON.", exception);
        }
    }

    private String normalizeChannelBodyTemplate(String bodyTemplate) {
        String normalized = StringUtils.hasText(bodyTemplate) ? bodyTemplate.strip() : DEFAULT_CHANNEL_BODY_TEMPLATE;
        templateService.validateChannelBodyTemplate(normalized);
        return normalized;
    }

    private String normalizeFilters(JsonNode filters) {
        Filters normalized = filters(filters);
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Notification filters must be valid JSON.", exception);
        }
    }

    private Filters filters(String filtersJson) {
        if (!StringUtils.hasText(filtersJson)) {
            return new Filters(List.of(), List.of(), List.of());
        }
        try {
            return filters(objectMapper.readTree(filtersJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Notification filters must be valid JSON.", exception);
        }
    }

    private Filters filters(JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return new Filters(List.of(), List.of(), List.of());
        }
        if (!filters.isObject()) {
            throw new IllegalArgumentException("Notification filters must be a JSON object.");
        }
        return new Filters(
                longList(filters.path("shareSummaryTaskIds"), "shareSummaryTaskIds"),
                stringList(filters.path("periodTypes"), "periodTypes").stream().map(value -> value.toUpperCase(Locale.ROOT)).toList(),
                stringList(filters.path("triggerTypes"), "triggerTypes").stream().map(value -> value.toUpperCase(Locale.ROOT)).toList()
        );
    }

    private List<Long> longList(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be an array.");
        }
        List<Long> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.canConvertToLong()) {
                throw new IllegalArgumentException(fieldName + " must only contain numbers.");
            }
            values.add(item.asLong());
        });
        return values.stream().distinct().toList();
    }

    private List<String> stringList(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be an array.");
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
                throw new IllegalArgumentException(fieldName + " must only contain strings.");
            }
            values.add(item.asText().strip());
        });
        return values.stream().distinct().toList();
    }

    private List<Long> normalizeChannelIds(List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            throw new IllegalArgumentException("Notification task must include at least one channel.");
        }
        List<Long> normalized = channelIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Notification task must include at least one channel.");
        }
        return normalized;
    }

    private int normalizeTimeout(Integer timeoutSeconds) {
        int value = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        if (value < MIN_TIMEOUT_SECONDS || value > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("Webhook timeout must be between 1 and 60 seconds.");
        }
        return value;
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

    private String required(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return value.strip();
    }

    private String optionalStrip(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private String dateLabel(long millis) {
        ZoneId zone = clock.getZone();
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DATE_FORMATTER);
    }

    private String baseUrl() {
        String value = properties.getBaseUrl();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String limit(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String stripped = value.strip();
        return stripped.length() <= limit ? stripped : stripped.substring(0, limit);
    }

    private long now() {
        return Instant.now(clock).toEpochMilli();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void sleepBeforeRetry(int attempts) {
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

    public record ChannelRequest(
            String name,
            Boolean enabled,
            String url,
            JsonNode headersJson,
            String bodyTemplate,
            String secret,
            Integer timeoutSeconds
    ) {
    }

    public record ChannelResponse(
            Long id,
            String name,
            boolean enabled,
            String type,
            String url,
            String method,
            JsonNode headersJson,
            String bodyTemplate,
            boolean secretConfigured,
            int timeoutSeconds,
            long createdAt,
            long updatedAt
    ) {
        static ChannelResponse fromRecord(NotificationChannelRecord record) {
            return new ChannelResponse(
                    record.getId(),
                    record.getName(),
                    record.isEnabled(),
                    record.getType(),
                    record.getUrl(),
                    record.getMethod(),
                    parseJson(record.getHeadersJson()),
                    StringUtils.hasText(record.getBodyTemplate()) ? record.getBodyTemplate() : DEFAULT_CHANNEL_BODY_TEMPLATE,
                    StringUtils.hasText(record.getSecret()),
                    record.getTimeoutSeconds(),
                    record.getCreatedAt(),
                    record.getUpdatedAt()
            );
        }
    }

    public record TaskRequest(
            String name,
            Boolean enabled,
            String eventType,
            JsonNode filters,
            String templateJson,
            List<Long> channelIds
    ) {
    }

    public record TaskResponse(
            Long id,
            String name,
            boolean enabled,
            String eventType,
            JsonNode filters,
            String templateJson,
            List<Long> channelIds,
            long createdAt,
            long updatedAt
    ) {
        static TaskResponse fromRecord(NotificationTaskRecord record, List<Long> channelIds) {
            return new TaskResponse(
                    record.getId(),
                    record.getName(),
                    record.isEnabled(),
                    record.getEventType(),
                    parseJson(record.getFiltersJson()),
                    record.getTemplateJson(),
                    channelIds == null ? List.of() : channelIds,
                    record.getCreatedAt(),
                    record.getUpdatedAt()
            );
        }
    }

    public record ValidateTemplateRequest(String eventType, String templateJson) {
    }

    public record DeliveryPage(
            List<NotificationDeliveryRecord> items,
            int page,
            int size,
            long total,
            int totalPages
    ) {
    }

    public record DeleteResponse(int deleted) {
    }

    public record TestResponse(
            boolean success,
            String message,
            Integer responseStatus,
            String errorMessage,
            long durationMs
    ) {
    }

    private record NormalizedTask(NotificationTaskRecord task, List<Long> channelIds) {
    }

    private record Filters(List<Long> shareSummaryTaskIds, List<String> periodTypes, List<String> triggerTypes) {
    }

    private record SendResult(
            boolean success,
            boolean retryable,
            Integer statusCode,
            String responseBody,
            String errorMessage,
            long durationMs
    ) {
    }

    private static JsonNode parseJson(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(value);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
