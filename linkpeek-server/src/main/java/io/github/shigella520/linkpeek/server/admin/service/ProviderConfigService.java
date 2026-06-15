package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Service
public class ProviderConfigService {
    public static final String PROVIDER_BILIBILI = "bilibili";
    public static final String PROVIDER_LINUXDO = "linuxdo";
    public static final String PROVIDER_NGA = "nga";
    public static final String BILIBILI_AI_TITLE_ENABLED = "ai_title_enabled";
    public static final String NGA_PASSPORT_UID = "NGA_PASSPORT_UID";
    public static final String NGA_PASSPORT_CID = "NGA_PASSPORT_CID";
    public static final List<String> LINUXDO_COOKIE_KEYS = List.of("_t", "cf_clearance", "_forum_session");

    private final ProviderConfigMapper providerConfigMapper;
    private final Clock clock;

    public ProviderConfigService(ProviderConfigMapper providerConfigMapper, Clock clock) {
        this.providerConfigMapper = providerConfigMapper;
        this.clock = clock;
    }

    public Map<String, Map<String, String>> allProviderConfigs() {
        Map<String, Map<String, String>> grouped = new TreeMap<>();
        for (ProviderConfigRecord record : providerConfigMapper.selectAllConfigs()) {
            grouped.computeIfAbsent(record.getProviderId(), ignored -> new TreeMap<>())
                    .put(record.getConfigKey(), record.getConfigValue());
        }
        grouped.computeIfAbsent(PROVIDER_BILIBILI, ignored -> new TreeMap<>())
                .putIfAbsent(BILIBILI_AI_TITLE_ENABLED, Boolean.TRUE.toString());
        grouped.computeIfAbsent(PROVIDER_LINUXDO, ignored -> new TreeMap<>());
        grouped.computeIfAbsent(PROVIDER_NGA, ignored -> new TreeMap<>());
        return grouped;
    }

    public Map<String, String> providerConfigs(String providerId) {
        Map<String, String> values = new TreeMap<>();
        for (ProviderConfigRecord record : providerConfigMapper.selectProviderConfigs(providerId)) {
            values.put(record.getConfigKey(), record.getConfigValue());
        }
        return values;
    }

    public Optional<String> value(String providerId, String configKey) {
        ProviderConfigRecord record = providerConfigMapper.selectConfig(providerId, configKey);
        if (record == null || !StringUtils.hasText(record.getConfigValue())) {
            return Optional.empty();
        }
        return Optional.of(record.getConfigValue().strip());
    }

    @Transactional
    public void saveProviderConfigs(String providerId, Map<String, String> values) {
        long updatedAt = Instant.now(clock).toEpochMilli();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalizeRequired(entry.getKey(), "config key");
            ProviderConfigRecord record = new ProviderConfigRecord();
            record.setProviderId(normalizeRequired(providerId, "provider id"));
            record.setConfigKey(key);
            record.setConfigValue(entry.getValue() == null ? "" : entry.getValue().strip());
            record.setUpdatedAt(updatedAt);
            providerConfigMapper.upsertConfig(record);
        }
    }

    public String linuxDoCookieHeader() {
        Map<String, String> values = providerConfigs(PROVIDER_LINUXDO);
        if (values.isEmpty()) {
            return null;
        }

        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : LINUXDO_COOKIE_KEYS) {
            ordered.put(key, values.get(key));
        }
        values.entrySet().stream()
                .filter(entry -> !ordered.containsKey(entry.getKey()))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));

        String header = ordered.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue()))
                .map(entry -> cookieHeaderEntry(entry.getKey(), entry.getValue()))
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        return header.isBlank() ? null : header;
    }

    public String ngaPassportUid() {
        return value(PROVIDER_NGA, NGA_PASSPORT_UID).orElse(null);
    }

    public String ngaPassportCid() {
        return value(PROVIDER_NGA, NGA_PASSPORT_CID).orElse(null);
    }

    public boolean bilibiliAiTitleEnabled() {
        return value(PROVIDER_BILIBILI, BILIBILI_AI_TITLE_ENABLED)
                .map(this::enabledByTextValue)
                .orElse(true);
    }

    private String normalizeRequired(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private String cookieHeaderEntry(String rawKey, String rawValue) {
        String key = rawKey.strip();
        String value = rawValue.strip();
        int attributeStart = value.indexOf(';');
        if (attributeStart >= 0) {
            value = value.substring(0, attributeStart).strip();
        }
        String keyPrefix = key + "=";
        if (value.startsWith(keyPrefix)) {
            value = value.substring(keyPrefix.length()).strip();
        }
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return key + "=" + value;
    }

    private boolean enabledByTextValue(String value) {
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        return !("false".equals(normalized)
                || "0".equals(normalized)
                || "off".equals(normalized)
                || "no".equals(normalized));
    }
}
