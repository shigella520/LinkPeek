package io.github.shigella520.linkpeek.server.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationChannelRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryRecord;
import io.github.shigella520.linkpeek.server.admin.model.NotificationDeliveryStatus;
import io.github.shigella520.linkpeek.server.admin.model.NotificationEventType;
import io.github.shigella520.linkpeek.server.admin.model.NotificationTaskRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.NotificationMapper;
import io.github.shigella520.linkpeek.server.ai.AiProviderAutoDowngradedEvent;
import io.github.shigella520.linkpeek.server.ai.AiProviderRequestFailedEvent;
import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.notification.DataCrawlRequestFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * Turns domain events into notification deliveries: matches enabled tasks, renders the task
 * template and channel body, records a PENDING delivery per channel and hands it to
 * {@link NotificationDeliveryService} for sending.
 */
@Service
public class NotificationEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(NotificationEventPublisher.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final NotificationMapper notificationMapper;
    private final NotificationTemplateService templateService;
    private final ObjectMapper objectMapper;
    private final NotificationDeliveryService deliveryService;
    private final ExecutorService executor;
    private final LinkPeekProperties properties;
    private final Clock clock;

    public NotificationEventPublisher(
            NotificationMapper notificationMapper,
            NotificationTemplateService templateService,
            ObjectMapper objectMapper,
            NotificationDeliveryService deliveryService,
            @Qualifier("notificationWebhookExecutor") ExecutorService executor,
            LinkPeekProperties properties,
            Clock clock
    ) {
        this.notificationMapper = notificationMapper;
        this.templateService = templateService;
        this.objectMapper = objectMapper;
        this.deliveryService = deliveryService;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    public void publishShareSummaryImageSuccess(ShareSummaryRunRecord run, ShareSummaryImageRecord image) {
        if (run == null || image == null || image.getId() == null) {
            return;
        }
        NotificationEventType eventType = NotificationEventType.SHARE_SUMMARY_IMAGE_SUCCESS;
        long occurredAt = now();
        String eventKey = eventType.name() + ":" + image.getId();
        Map<String, Object> values = shareSummaryImageValues(run, image);
        publishEvent(eventType, eventKey, occurredAt, values, task -> matches(task, run));
    }

    public void publishShareSummaryImageFailed(ShareSummaryRunRecord run, ShareSummaryImageRecord image) {
        publishShareSummaryImageFailed(run, image, "", image == null ? "" : image.getErrorMessage());
    }

    public void publishShareSummaryImageFailed(ShareSummaryRunRecord run, ShareSummaryImageRecord image, String errorType, String errorMessage) {
        if (run == null || image == null || image.getId() == null) {
            return;
        }
        NotificationEventType eventType = NotificationEventType.SHARE_SUMMARY_IMAGE_FAILED;
        long occurredAt = now();
        String eventKey = eventType.name() + ":" + image.getId();
        Map<String, Object> values = shareSummaryImageValues(run, image);
        values.put("error.type", errorValue(errorType, "ImageGenerationException"));
        values.put("error.message", errorValue(errorMessage, "Image generation failed."));
        publishEvent(eventType, eventKey, occurredAt, values);
    }

    public void publishShareSummaryAudioFailed(ShareSummaryRunRecord run, ShareSummaryAudioRecord audio) {
        publishShareSummaryAudioFailed(run, audio, "", audio == null ? "" : audio.getErrorMessage());
    }

    public void publishShareSummaryAudioFailed(ShareSummaryRunRecord run, ShareSummaryAudioRecord audio, String errorType, String errorMessage) {
        if (run == null || audio == null || audio.getId() == null) {
            return;
        }
        NotificationEventType eventType = NotificationEventType.SHARE_SUMMARY_AUDIO_FAILED;
        long occurredAt = now();
        String eventKey = eventType.name() + ":" + audio.getId();
        Map<String, Object> values = shareSummaryRunValues(run);
        values.putAll(shareSummaryAudioValues(audio));
        values.put("error.type", errorValue(errorType, "AudioGenerationException"));
        values.put("error.message", errorValue(errorMessage, "Audio generation failed."));
        publishEvent(eventType, eventKey, occurredAt, values);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishAiProviderRequestFailed(AiProviderRequestFailedEvent event) {
        if (event == null || event.provider() == null || event.provider().getId() == null) {
            return;
        }
        NotificationEventType eventType = NotificationEventType.AI_PROVIDER_REQUEST_FAILED;
        long occurredAt = now();
        String eventKey = eventType.name() + ":" + event.provider().getId() + ":" + occurredAt;
        submitEventPublish(eventType, eventKey, () -> publishEvent(eventType, eventKey, occurredAt, aiProviderRequestFailedValues(event)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishAiProviderAutoDowngraded(AiProviderAutoDowngradedEvent event) {
        if (event == null || event.provider() == null || event.provider().getId() == null) {
            return;
        }
        NotificationEventType eventType = NotificationEventType.AI_PROVIDER_AUTO_DOWNGRADED;
        long occurredAt = now();
        String eventKey = eventType.name() + ":" + event.provider().getId() + ":" + occurredAt;
        submitEventPublish(eventType, eventKey, () -> publishEvent(eventType, eventKey, occurredAt, aiProviderAutoDowngradedValues(event)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishDataCrawlRequestFailed(DataCrawlRequestFailedEvent event) {
        if (event == null || !StringUtils.hasText(event.previewKey())) {
            return;
        }
        NotificationEventType eventType = NotificationEventType.DATA_CRAWL_REQUEST_FAILED;
        long occurredAt = now();
        String eventKey = eventType.name() + ":" + event.previewKey() + ":" + occurredAt;
        submitEventPublish(eventType, eventKey, () -> publishEvent(eventType, eventKey, occurredAt, dataCrawlRequestFailedValues(event)));
    }

    public void publishEvent(NotificationEventType eventType, String eventKey, Map<String, Object> values) {
        publishEvent(eventType, eventKey, now(), values);
    }

    private void publishEvent(
            NotificationEventType eventType,
            String eventKey,
            long occurredAt,
            Map<String, Object> values
    ) {
        publishEvent(eventType, eventKey, occurredAt, values, ignored -> true);
    }

    private void publishEvent(
            NotificationEventType eventType,
            String eventKey,
            long occurredAt,
            Map<String, Object> eventValues,
            Predicate<NotificationTaskRecord> taskFilter
    ) {
        Map<String, Object> values = eventValues(eventType, eventKey, occurredAt);
        if (eventValues != null) {
            values.putAll(eventValues);
        }
        List<NotificationTaskRecord> tasks = notificationMapper.selectEnabledTasksByEventType(eventType.name());
        if (tasks.isEmpty()) {
            log.info("notification_event_skipped_no_enabled_tasks eventType={} eventKey={}", eventType.name(), eventKey);
            return;
        }
        log.info("notification_event_publish eventType={} eventKey={} taskCount={}", eventType.name(), eventKey, tasks.size());
        for (NotificationTaskRecord task : tasks) {
            try {
                if (!taskFilter.test(task)) {
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
        List<NotificationChannelRecord> channels = notificationMapper.selectEnabledChannelsForTask(task.getId());
        if (channels.isEmpty()) {
            log.info("notification_task_skipped_no_enabled_channels taskId={} eventType={} eventKey={}", task.getId(), eventType.name(), eventKey);
            return;
        }
        for (NotificationChannelRecord channel : channels) {
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
            delivery.setRequestBodySnapshot(NotificationDeliveryService.limit(body, NotificationDeliveryService.SNAPSHOT_LIMIT));
            delivery.setDurationMs(0);
            delivery.setCreatedAt(now());
            deliveryService.insertDelivery(delivery);
            log.info(
                    "notification_delivery_created deliveryId={} eventType={} eventKey={} taskId={} channelId={}",
                    delivery.getId(),
                    eventType.name(),
                    eventKey,
                    task.getId(),
                    channel.getId()
            );
            deliveryService.submitDelivery(delivery.getId(), channel, eventType.name(), occurredAt, body);
        }
    }

    private void submitEventPublish(NotificationEventType eventType, String eventKey, Runnable publishTask) {
        try {
            executor.execute(publishTask);
        } catch (RejectedExecutionException exception) {
            log.warn("notification_event_publish_rejected eventType={} eventKey={} message={}", eventType.name(), eventKey, exception.getMessage(), exception);
        }
    }

    private boolean matches(NotificationTaskRecord task, ShareSummaryRunRecord run) {
        NotificationTaskFilters filters = NotificationTaskFilters.parse(objectMapper, task.getFiltersJson());
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

    private Map<String, Object> eventValues(NotificationEventType eventType, String eventKey, long occurredAt) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("event.type", eventType.name());
        values.put("event.key", eventKey);
        values.put("event.occurredAt", occurredAt);
        values.put("event.occurredAtIso", Instant.ofEpochMilli(occurredAt).toString());
        values.put("system.baseUrl", baseUrl());
        values.put("system.appName", "LinkPeek");
        return values;
    }

    private Map<String, Object> shareSummaryImageValues(ShareSummaryRunRecord run, ShareSummaryImageRecord image) {
        Map<String, Object> values = shareSummaryRunValues(run);
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
        values.put("image.errorMessage", optionalStrip(image.getErrorMessage()));
        return values;
    }

    private Map<String, Object> shareSummaryRunValues(ShareSummaryRunRecord run) {
        Map<String, Object> values = new LinkedHashMap<>();
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
        return values;
    }

    private Map<String, Object> shareSummaryAudioValues(ShareSummaryAudioRecord audio) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("audio.id", audio.getId());
        values.put("audio.runId", audio.getRunId());
        values.put("audio.attemptNo", audio.getAttemptNo());
        values.put("audio.status", audio.getStatus());
        values.put("audio.providerType", audio.getProviderType());
        values.put("audio.model", audio.getModel());
        values.put("audio.voice", audio.getVoice());
        values.put("audio.speed", audio.getSpeed());
        values.put("audio.pitch", audio.getPitch());
        values.put("audio.style", audio.getStyle());
        values.put("audio.outputFormat", audio.getOutputFormat());
        values.put("audio.audioUrl", audio.getAudioUrl());
        values.put("audio.durationMs", audio.getDurationMs());
        values.put("audio.createdAt", audio.getCreatedAt());
        values.put("audio.startedAt", audio.getStartedAt());
        values.put("audio.finishedAt", audio.getFinishedAt());
        values.put("audio.errorMessage", optionalStrip(audio.getErrorMessage()));
        return values;
    }

    private Map<String, Object> aiProviderRequestFailedValues(AiProviderRequestFailedEvent event) {
        Map<String, Object> values = aiProviderValues(event.provider());
        values.put("request.operation", optionalStrip(event.operation()));
        values.put("request.durationMs", event.durationMs());
        values.put("error.type", errorValue(event.errorType(), "AiProviderException"));
        values.put("error.message", errorValue(event.errorMessage(), "AI Provider request failed."));
        values.put("downgrade.enabled", event.downgradeEnabled());
        values.put("downgrade.failureCount", event.failureCount());
        values.put("downgrade.failureThreshold", event.failureThreshold());
        values.put("downgrade.triggered", event.downgradeTriggered());
        return values;
    }

    private Map<String, Object> aiProviderAutoDowngradedValues(AiProviderAutoDowngradedEvent event) {
        Map<String, Object> values = aiProviderValues(event.provider());
        values.put("request.operation", optionalStrip(event.operation()));
        values.put("request.durationMs", event.durationMs());
        values.put("error.type", errorValue(event.errorType(), "AiProviderException"));
        values.put("error.message", errorValue(event.errorMessage(), "AI Provider request failed."));
        values.put("downgrade.failureCount", event.failureCount());
        values.put("downgrade.failureThreshold", event.failureThreshold());
        values.put("downgrade.oldSortOrder", event.oldSortOrder());
        values.put("downgrade.newSortOrder", event.newSortOrder());
        values.put("downgrade.alreadyLowest", event.alreadyLowest());
        values.put("downgrade.providerCount", event.providerCount());
        return values;
    }

    private Map<String, Object> aiProviderValues(AiProviderRecord provider) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("provider.id", provider.getId());
        values.put("provider.name", provider.getName());
        values.put("provider.enabled", provider.isEnabled());
        values.put("provider.sortOrder", provider.getSortOrder());
        values.put("provider.baseUrl", provider.getBaseUrl());
        values.put("provider.apiKind", provider.getApiKind());
        values.put("provider.model", provider.getModel());
        values.put("provider.requestTimeoutSeconds", provider.getRequestTimeoutSeconds());
        return values;
    }

    private Map<String, Object> dataCrawlRequestFailedValues(DataCrawlRequestFailedEvent event) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("preview.previewKey", event.previewKey());
        values.put("preview.providerId", event.providerId());
        values.put("preview.sourceUrl", event.sourceUrl());
        values.put("preview.canonicalUrl", event.canonicalUrl());
        values.put("request.clientType", event.clientType());
        values.put("request.httpStatus", event.httpStatus());
        values.put("request.durationMs", event.durationMs());
        values.put("request.requestedStyle", event.requestedStyle());
        values.put("error.code", event.errorCode());
        values.put("error.type", errorValue(event.errorType(), "DataCrawlException"));
        values.put("error.message", errorValue(event.errorMessage(), "Data crawl request failed."));
        return values;
    }

    private String optionalStrip(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private String errorValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
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

    private long now() {
        return Instant.now(clock).toEpochMilli();
    }
}
