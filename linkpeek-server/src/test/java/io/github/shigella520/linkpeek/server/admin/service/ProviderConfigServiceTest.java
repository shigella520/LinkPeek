package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigServiceTest {
    @Test
    void linuxDoCookieHeaderJoinsConfiguredCookieKeysAndFiltersBlankValues() {
        FakeProviderConfigMapper mapper = new FakeProviderConfigMapper();
        ProviderConfigService service = new ProviderConfigService(mapper, fixedClock());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("cf_clearance", " cf_clearance=clear; ");
        values.put("_forum_session", " ");
        values.put("_t", " _t=token; Path=/; HttpOnly ");
        values.put("extra_cookie", " extra ");

        service.saveProviderConfigs(ProviderConfigService.PROVIDER_LINUXDO, values);

        assertEquals("_t=token; cf_clearance=clear; extra_cookie=extra", service.linuxDoCookieHeader());
    }

    @Test
    void linuxDoCookieHeaderSkipsDisabledCookieKeys() {
        FakeProviderConfigMapper mapper = new FakeProviderConfigMapper();
        ProviderConfigService service = new ProviderConfigService(mapper, fixedClock());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("_t", "token");
        values.put("cf_clearance", "clear");
        values.put("_forum_session", "session");
        values.put("_t_enabled", "true");
        values.put("cf_clearance_enabled", "false");
        values.put("_forum_session_enabled", "false");

        service.saveProviderConfigs(ProviderConfigService.PROVIDER_LINUXDO, values);

        assertEquals("_t=token", service.linuxDoCookieHeader());
    }

    @Test
    void linuxDoCookieEnabledFlagsDefaultToEnabledInConfigResponse() {
        FakeProviderConfigMapper mapper = new FakeProviderConfigMapper();
        ProviderConfigService service = new ProviderConfigService(mapper, fixedClock());

        Map<String, String> linuxDoConfigs = service.allProviderConfigs().get(ProviderConfigService.PROVIDER_LINUXDO);

        assertEquals("true", linuxDoConfigs.get("_t_enabled"));
        assertEquals("true", linuxDoConfigs.get("cf_clearance_enabled"));
        assertEquals("true", linuxDoConfigs.get("_forum_session_enabled"));
    }

    @Test
    void ngaCredentialsAreReadFromProviderConfig() {
        FakeProviderConfigMapper mapper = new FakeProviderConfigMapper();
        ProviderConfigService service = new ProviderConfigService(mapper, fixedClock());

        service.saveProviderConfigs(ProviderConfigService.PROVIDER_NGA, Map.of(
                ProviderConfigService.NGA_PASSPORT_UID, " uid ",
                ProviderConfigService.NGA_PASSPORT_CID, " cid "
        ));

        assertEquals("uid", service.ngaPassportUid());
        assertEquals("cid", service.ngaPassportCid());
    }

    @Test
    void bilibiliAiTitleDefaultsToEnabledInConfigResponse() {
        FakeProviderConfigMapper mapper = new FakeProviderConfigMapper();
        ProviderConfigService service = new ProviderConfigService(mapper, fixedClock());

        assertTrue(service.bilibiliAiTitleEnabled());
        assertEquals(
                "true",
                service.allProviderConfigs()
                        .get(ProviderConfigService.PROVIDER_BILIBILI)
                        .get(ProviderConfigService.BILIBILI_AI_TITLE_ENABLED)
        );
    }

    @Test
    void bilibiliAiTitleCanBeDisabledFromProviderConfig() {
        FakeProviderConfigMapper mapper = new FakeProviderConfigMapper();
        ProviderConfigService service = new ProviderConfigService(mapper, fixedClock());

        service.saveProviderConfigs(ProviderConfigService.PROVIDER_BILIBILI, Map.of(
                ProviderConfigService.BILIBILI_AI_TITLE_ENABLED, " false "
        ));

        assertFalse(service.bilibiliAiTitleEnabled());
        assertEquals(
                "false",
                service.allProviderConfigs()
                        .get(ProviderConfigService.PROVIDER_BILIBILI)
                        .get(ProviderConfigService.BILIBILI_AI_TITLE_ENABLED)
        );
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(1234L), ZoneOffset.UTC);
    }

    private static final class FakeProviderConfigMapper implements ProviderConfigMapper {
        private final Map<String, ProviderConfigRecord> records = new LinkedHashMap<>();

        @Override
        public List<ProviderConfigRecord> selectAllConfigs() {
            return new ArrayList<>(records.values());
        }

        @Override
        public List<ProviderConfigRecord> selectProviderConfigs(String providerId) {
            return records.values().stream()
                    .filter(record -> record.getProviderId().equals(providerId))
                    .toList();
        }

        @Override
        public ProviderConfigRecord selectConfig(String providerId, String configKey) {
            return records.get(key(providerId, configKey));
        }

        @Override
        public void upsertConfig(ProviderConfigRecord config) {
            ProviderConfigRecord record = new ProviderConfigRecord();
            record.setProviderId(config.getProviderId());
            record.setConfigKey(config.getConfigKey());
            record.setConfigValue(config.getConfigValue());
            record.setUpdatedAt(config.getUpdatedAt());
            records.put(key(config.getProviderId(), config.getConfigKey()), record);
        }

        private String key(String providerId, String configKey) {
            return providerId + "\n" + configKey;
        }
    }
}
