package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import io.github.shigella520.linkpeek.server.admin.model.NotificationTaskRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Admin configuration of notifications: event schemas, webhook channels (including the channel
 * test send), notification tasks and template validation.
 */
@Service
public class NotificationConfigService {
    private static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";
    private static final String METHOD_POST = "POST";
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final String DEFAULT_CHANNEL_BODY_TEMPLATE = "{{message.bodyJson}}";

    private final NotificationMapper notificationMapper;
    private final NotificationTemplateService templateService;
    private final ObjectMapper objectMapper;
    private final NotificationDeliveryService deliveryService;
    private final Clock clock;

    public NotificationConfigService(
            NotificationMapper notificationMapper,
            NotificationTemplateService templateService,
            ObjectMapper objectMapper,
            NotificationDeliveryService deliveryService,
            Clock clock
    ) {
        this.notificationMapper = notificationMapper;
        this.templateService = templateService;
        this.objectMapper = objectMapper;
        this.deliveryService = deliveryService;
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

    public NotificationDeleteResponse deleteChannel(long channelId) {
        existingChannel(channelId);
        if (notificationMapper.countTaskChannelsForChannel(channelId) > 0) {
            throw new IllegalStateException("Notification channel is still used by notification tasks.");
        }
        return new NotificationDeleteResponse(notificationMapper.deleteChannel(channelId));
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
            NotificationDeliveryService.SendResult result = deliveryService.sendWebhook(
                    null, channel, NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS.name(), now(), body);
            return new TestResponse(
                    result.success(),
                    result.success() ? "测试成功。" : "测试失败。",
                    result.statusCode(),
                    result.errorMessage(),
                    NotificationDeliveryService.elapsedMillis(startedAt)
            );
        } catch (RuntimeException exception) {
            return new TestResponse(false, "测试失败。", null,
                    NotificationDeliveryService.limit(exception.getMessage(), NotificationDeliveryService.ERROR_LIMIT),
                    NotificationDeliveryService.elapsedMillis(startedAt));
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
    public NotificationDeleteResponse deleteTask(long taskId) {
        existingTask(taskId);
        notificationMapper.deleteTaskChannels(taskId);
        return new NotificationDeleteResponse(notificationMapper.deleteTask(taskId));
    }

    public NotificationTemplateService.TemplateValidationResult validateTemplate(ValidateTemplateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Template validation payload is required.");
        }
        return templateService.validate(request.eventType(), request.templateJson());
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
        task.setFiltersJson(normalizeFilters(eventType, request.filters()));
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
            deliveryService.validateWebhookUri(uri);
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
            if (NotificationDeliveryService.BLOCKED_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
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

    private String normalizeFilters(NotificationEventType eventType, JsonNode filters) {
        NotificationTaskFilters normalized = NotificationTaskFilters.parse(
                eventType == NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS ? filters : null);
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Notification filters must be valid JSON.", exception);
        }
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

    private String required(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return value.strip();
    }

    private String optionalStrip(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private long now() {
        return Instant.now(clock).toEpochMilli();
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
