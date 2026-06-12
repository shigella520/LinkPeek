package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiProviderDowngradeService {
    private static final Logger log = LoggerFactory.getLogger(AiProviderDowngradeService.class);

    public static final String PROVIDER_AI_PROVIDER = "ai_provider";
    public static final String AUTO_DOWNGRADE_ENABLED_KEY = "auto_downgrade_enabled";
    public static final String AUTO_DOWNGRADE_FAILURE_THRESHOLD_KEY = "auto_downgrade_failure_threshold";
    public static final int DEFAULT_AUTO_DOWNGRADE_FAILURE_THRESHOLD = 3;
    public static final int MIN_AUTO_DOWNGRADE_FAILURE_THRESHOLD = 1;
    public static final int MAX_AUTO_DOWNGRADE_FAILURE_THRESHOLD = 100;

    private static final int SORT_STEP = 100;

    private final ProviderConfigMapper providerConfigMapper;
    private final AiProviderMapper aiProviderMapper;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<Long, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

    @Autowired
    public AiProviderDowngradeService(
            ProviderConfigMapper providerConfigMapper,
            AiProviderMapper aiProviderMapper,
            Clock clock,
            ApplicationEventPublisher eventPublisher
    ) {
        this.providerConfigMapper = providerConfigMapper;
        this.aiProviderMapper = aiProviderMapper;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    public AiProviderDowngradeService(
            ProviderConfigMapper providerConfigMapper,
            AiProviderMapper aiProviderMapper,
            Clock clock
    ) {
        this(providerConfigMapper, aiProviderMapper, clock, null);
    }

    public ConfigResponse config() {
        ProviderConfigRecord enabled = providerConfigMapper.selectConfig(PROVIDER_AI_PROVIDER, AUTO_DOWNGRADE_ENABLED_KEY);
        ProviderConfigRecord threshold = providerConfigMapper.selectConfig(PROVIDER_AI_PROVIDER, AUTO_DOWNGRADE_FAILURE_THRESHOLD_KEY);
        Long updatedAt = updatedAt(enabled, threshold);
        return new ConfigResponse(
                parseBoolean(enabled, false),
                parseThreshold(threshold),
                DEFAULT_AUTO_DOWNGRADE_FAILURE_THRESHOLD,
                updatedAt
        );
    }

    @Transactional
    public ConfigResponse saveConfig(Boolean autoDowngradeEnabled, Integer autoDowngradeFailureThreshold) {
        if (autoDowngradeEnabled == null) {
            throw new IllegalArgumentException("Auto downgrade enabled value is required.");
        }
        int threshold = normalizeThreshold(autoDowngradeFailureThreshold);
        long updatedAt = Instant.now(clock).toEpochMilli();
        upsert(AUTO_DOWNGRADE_ENABLED_KEY, Boolean.toString(autoDowngradeEnabled), updatedAt);
        upsert(AUTO_DOWNGRADE_FAILURE_THRESHOLD_KEY, Integer.toString(threshold), updatedAt);
        if (!autoDowngradeEnabled) {
            failureCounts.clear();
        }
        return config();
    }

    public void recordSuccess(AiProviderRecord provider) {
        if (provider != null && provider.getId() != null) {
            failureCounts.remove(provider.getId());
        }
    }

    @Transactional
    public synchronized void recordFailure(AiProviderRecord provider, Throwable exception) {
        recordFailure(provider, "UNKNOWN", 0, exception);
    }

    @Transactional
    public synchronized void recordFailure(AiProviderRecord provider, String operation, long durationMs, Throwable exception) {
        if (provider == null || provider.getId() == null) {
            return;
        }
        ConfigResponse config = config();
        if (!config.autoDowngradeEnabled()) {
            publishRequestFailed(provider, operation, durationMs, exception, false, 0, config.autoDowngradeFailureThreshold(), false);
            return;
        }

        AtomicInteger counter = failureCounts.computeIfAbsent(provider.getId(), ignored -> new AtomicInteger());
        int failureCount = counter.incrementAndGet();
        boolean triggered = failureCount >= config.autoDowngradeFailureThreshold();
        publishRequestFailed(provider, operation, durationMs, exception, true, failureCount, config.autoDowngradeFailureThreshold(), triggered);
        log.warn(
                "ai_provider_auto_downgrade_failure_count providerId={} providerName={} failureCount={} threshold={} timeoutSeconds={} baseUrl={} errorType={} message={}",
                provider.getId(),
                provider.getName(),
                failureCount,
                config.autoDowngradeFailureThreshold(),
                provider.getRequestTimeoutSeconds(),
                provider.getBaseUrl(),
                exception == null ? "" : exception.getClass().getSimpleName(),
                exception == null ? "" : exception.getMessage()
        );
        if (failureCount < config.autoDowngradeFailureThreshold()) {
            return;
        }

        failureCounts.remove(provider.getId());
        moveProviderToBottom(provider, operation, durationMs, exception, failureCount, config.autoDowngradeFailureThreshold());
    }

    private void moveProviderToBottom(
            AiProviderRecord provider,
            String operation,
            long durationMs,
            Throwable exception,
            int failureCount,
            int threshold
    ) {
        List<AiProviderRecord> providers = aiProviderMapper.selectAllProviders();
        boolean exists = providers.stream()
                .anyMatch(candidate -> provider.getId().equals(candidate.getId()));
        if (!exists) {
            log.warn(
                    "AI_PROVIDER_AUTO_DOWNGRADE_SKIPPED providerId={} providerName={} reason=provider_not_found failureCount={} threshold={}",
                    provider.getId(),
                    provider.getName(),
                    failureCount,
                    threshold
            );
            return;
        }

        AiProviderRecord lastProvider = providers.stream()
                .max(Comparator.comparingInt(AiProviderRecord::getSortOrder).thenComparing(AiProviderRecord::getId))
                .orElse(null);
        boolean alreadyLowest = lastProvider != null && provider.getId().equals(lastProvider.getId());
        int oldSortOrder = provider.getSortOrder();
        List<Long> reorderedIds = new ArrayList<>(providers.stream()
                .map(AiProviderRecord::getId)
                .filter(id -> !provider.getId().equals(id))
                .toList());
        reorderedIds.add(provider.getId());

        long updatedAt = Instant.now(clock).toEpochMilli();
        int sortOrder = SORT_STEP;
        int newSortOrder = sortOrder;
        for (Long providerId : reorderedIds) {
            aiProviderMapper.updateProviderSortOrder(providerId, sortOrder, updatedAt);
            if (provider.getId().equals(providerId)) {
                newSortOrder = sortOrder;
            }
            sortOrder += SORT_STEP;
        }

        log.warn(
                "AI_PROVIDER_AUTO_DOWNGRADE_TRIGGERED providerId={} providerName={} failureCount={} threshold={} oldSortOrder={} newSortOrder={} alreadyLowest={} timeoutSeconds={} baseUrl={}",
                provider.getId(),
                provider.getName(),
                failureCount,
                threshold,
                oldSortOrder,
                newSortOrder,
                alreadyLowest,
                provider.getRequestTimeoutSeconds(),
                provider.getBaseUrl()
        );
        publishAutoDowngraded(
                provider,
                operation,
                durationMs,
                exception,
                failureCount,
                threshold,
                oldSortOrder,
                newSortOrder,
                alreadyLowest,
                providers.size()
        );
    }

    private void publishRequestFailed(
            AiProviderRecord provider,
            String operation,
            long durationMs,
            Throwable exception,
            boolean downgradeEnabled,
            int failureCount,
            int failureThreshold,
            boolean downgradeTriggered
    ) {
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishEvent(new AiProviderRequestFailedEvent(
                snapshot(provider),
                operation,
                durationMs,
                errorType(exception),
                errorMessage(exception),
                downgradeEnabled,
                failureCount,
                failureThreshold,
                downgradeTriggered
        ));
    }

    private void publishAutoDowngraded(
            AiProviderRecord provider,
            String operation,
            long durationMs,
            Throwable exception,
            int failureCount,
            int failureThreshold,
            int oldSortOrder,
            int newSortOrder,
            boolean alreadyLowest,
            int providerCount
    ) {
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishEvent(new AiProviderAutoDowngradedEvent(
                snapshot(provider),
                operation,
                durationMs,
                errorType(exception),
                errorMessage(exception),
                failureCount,
                failureThreshold,
                oldSortOrder,
                newSortOrder,
                alreadyLowest,
                providerCount
        ));
    }

    private AiProviderRecord snapshot(AiProviderRecord provider) {
        AiProviderRecord snapshot = new AiProviderRecord();
        snapshot.setId(provider.getId());
        snapshot.setName(provider.getName());
        snapshot.setEnabled(provider.isEnabled());
        snapshot.setSortOrder(provider.getSortOrder());
        snapshot.setBaseUrl(provider.getBaseUrl());
        snapshot.setApiKind(provider.getApiKind());
        snapshot.setModel(provider.getModel());
        snapshot.setEffort(provider.getEffort());
        snapshot.setRequestTimeoutSeconds(provider.getRequestTimeoutSeconds());
        snapshot.setUpdatedAt(provider.getUpdatedAt());
        return snapshot;
    }

    private String errorType(Throwable exception) {
        return exception == null ? "" : exception.getClass().getSimpleName();
    }

    private String errorMessage(Throwable exception) {
        return exception == null ? "" : exception.getMessage();
    }

    private void upsert(String configKey, String configValue, long updatedAt) {
        ProviderConfigRecord record = new ProviderConfigRecord();
        record.setProviderId(PROVIDER_AI_PROVIDER);
        record.setConfigKey(configKey);
        record.setConfigValue(configValue);
        record.setUpdatedAt(updatedAt);
        providerConfigMapper.upsertConfig(record);
    }

    private boolean parseBoolean(ProviderConfigRecord record, boolean defaultValue) {
        if (record == null || !StringUtils.hasText(record.getConfigValue())) {
            return defaultValue;
        }
        return Boolean.parseBoolean(record.getConfigValue().strip());
    }

    private int parseThreshold(ProviderConfigRecord record) {
        if (record == null || !StringUtils.hasText(record.getConfigValue())) {
            return DEFAULT_AUTO_DOWNGRADE_FAILURE_THRESHOLD;
        }
        try {
            return normalizeThreshold(Integer.parseInt(record.getConfigValue().strip()));
        } catch (NumberFormatException exception) {
            return DEFAULT_AUTO_DOWNGRADE_FAILURE_THRESHOLD;
        }
    }

    private int normalizeThreshold(Integer threshold) {
        int value = threshold == null ? DEFAULT_AUTO_DOWNGRADE_FAILURE_THRESHOLD : threshold;
        if (value < MIN_AUTO_DOWNGRADE_FAILURE_THRESHOLD || value > MAX_AUTO_DOWNGRADE_FAILURE_THRESHOLD) {
            throw new IllegalArgumentException("Auto downgrade failure threshold must be between 1 and 100.");
        }
        return value;
    }

    private Long updatedAt(ProviderConfigRecord... records) {
        long value = 0L;
        for (ProviderConfigRecord record : records) {
            value = Math.max(value, record == null ? 0L : record.getUpdatedAt());
        }
        return value == 0L ? null : value;
    }

    public record ConfigResponse(
            boolean autoDowngradeEnabled,
            int autoDowngradeFailureThreshold,
            int defaultAutoDowngradeFailureThreshold,
            Long updatedAt
    ) {
    }
}
