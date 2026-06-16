package io.github.shigella520.linkpeek.server.ai;

import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.server.admin.model.AdminPromptRecord;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ProviderConfigRecord;
import io.github.shigella520.linkpeek.server.admin.persistence.AdminPromptMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.AiProviderMapper;
import io.github.shigella520.linkpeek.server.admin.persistence.ProviderConfigMapper;
import io.github.shigella520.linkpeek.server.admin.service.AiTitleConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTitleServiceTest {
    @Test
    void buildPromptSeparatesTitleFormatStyleAndRawContent() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeOpenAiCompatibleTextClient(), null, null);

        AiTitlePrompt prompt = service.buildPrompt("UC 风格", " 原文内容 ");

        assertEquals(AiTitleService.DEFAULT_TITLE_FORMAT_PROMPT, prompt.titleFormatPrompt());
        assertEquals("UC 风格", prompt.stylePrompt());
        assertEquals("原文内容", prompt.rawContent());
        assertEquals("Style Prompt\nUC 风格", prompt.styleMessage());
        assertEquals("Raw Content\n原文内容", prompt.rawContentMessage());
    }

    @Test
    void buildPromptKeepsStylePromptAndRawContentSeparate() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeOpenAiCompatibleTextClient(), null, null);

        AiTitlePrompt prompt = service.buildPrompt("请参考原文语气", "帖子正文", "标题格式");

        assertEquals("请参考原文语气", prompt.stylePrompt());
        assertEquals("帖子正文", prompt.rawContent());
        assertEquals("标题格式", prompt.titleFormatPrompt());
    }

    @Test
    void buildPromptUsesConfiguredTitleFormatPrompt() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeOpenAiCompatibleTextClient(), null, null);

        AiTitlePrompt prompt = service.buildPrompt("UC 风格", "原文内容", "只输出 15 到 30 个中文字符");

        assertEquals("只输出 15 到 30 个中文字符", prompt.titleFormatPrompt());
        assertEquals("UC 风格", prompt.stylePrompt());
        assertEquals("原文内容", prompt.rawContent());
    }

    @Test
    void buildPromptCanSkipTitleFormatPromptWhenConfiguredBlank() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeOpenAiCompatibleTextClient(), null, null);

        AiTitlePrompt prompt = service.buildPrompt("UC 风格", "原文内容", " ");

        assertEquals("", prompt.titleFormatPrompt());
        assertFalse(prompt.hasTitleFormatPrompt());
    }

    @Test
    void resolveStylePromptIncludesTitleFormatPromptInPromptHash() {
        AdminPromptRecord promptRecord = new AdminPromptRecord();
        promptRecord.setStyle("FUN");
        promptRecord.setPrompt("UC 风格");
        AiTitleService defaultService = new AiTitleService(
                new FakeAdminPromptMapper(promptRecord),
                new FakeAiProviderMapper(List.of()),
                new FakeOpenAiCompatibleTextClient(),
                configService(null),
                null
        );
        AiTitleService customService = new AiTitleService(
                new FakeAdminPromptMapper(promptRecord),
                new FakeAiProviderMapper(List.of()),
                new FakeOpenAiCompatibleTextClient(),
                configService("自定义输出要求"),
                null
        );

        AiTitleService.StylePrompt defaultPrompt = defaultService.resolveStylePrompt("fun").orElseThrow();
        AiTitleService.StylePrompt customPrompt = customService.resolveStylePrompt("fun").orElseThrow();

        assertEquals("FUN", defaultPrompt.style());
        assertEquals(AiTitleService.DEFAULT_TITLE_FORMAT_PROMPT, defaultPrompt.titleFormatPrompt());
        assertEquals("自定义输出要求", customPrompt.titleFormatPrompt());
        assertNotEquals(defaultPrompt.promptHash(), customPrompt.promptHash());
    }

    @Test
    void resolveFreestylePromptSelectsConfiguredStylePrompt() {
        AdminPromptRecord promptRecord = new AdminPromptRecord();
        promptRecord.setStyle("FUN");
        promptRecord.setPrompt("UC 风格");
        AiTitleService service = new AiTitleService(
                new FakeAdminPromptMapper(promptRecord),
                new FakeAiProviderMapper(List.of()),
                new FakeOpenAiCompatibleTextClient(),
                configService(null),
                null
        );

        AiTitleService.StylePrompt freestylePrompt = service.resolveStylePrompt("freestyle").orElseThrow();

        assertEquals("FUN", freestylePrompt.style());
        assertEquals("UC 风格", freestylePrompt.prompt());
    }

    @Test
    void resolveFreestylePromptDoesNotRefreshBeforeAiTitleSucceeds() {
        AdminPromptRecord fun = new AdminPromptRecord();
        fun.setStyle("FUN");
        fun.setPrompt("UC 风格");
        AdminPromptRecord work = new AdminPromptRecord();
        work.setStyle("WORK");
        work.setPrompt("工作风格");
        MutableClock clock = new MutableClock(0);
        AiTitleService service = new AiTitleService(
                new FakeAdminPromptMapper(List.of(work, fun)),
                new FakeAiProviderMapper(List.of()),
                new FakeOpenAiCompatibleTextClient(),
                configService(null),
                null,
                clock
        );
        URI uri = URI.create("https://example.com/post/1");

        AiTitleService.StylePrompt first = service.resolveStylePrompt("freestyle", uri).orElseThrow();
        clock.setMillis(120_000L);
        AiTitleService.StylePrompt second = service.resolveStylePrompt("freestyle", uri).orElseThrow();

        assertEquals(first.style(), second.style());
        assertEquals(first.promptHash(), second.promptHash());
    }

    @Test
    void markFreestyleSelectionSucceededStartsThirtySecondWindow() {
        AdminPromptRecord fun = new AdminPromptRecord();
        fun.setStyle("FUN");
        fun.setPrompt("UC 风格");
        AdminPromptRecord work = new AdminPromptRecord();
        work.setStyle("WORK");
        work.setPrompt("工作风格");
        MutableClock clock = new MutableClock(0);
        AiTitleService service = new AiTitleService(
                new FakeAdminPromptMapper(List.of(work, fun)),
                new FakeAiProviderMapper(List.of()),
                new FakeOpenAiCompatibleTextClient(),
                configService(null),
                null,
                clock
        );
        URI uri = URI.create("https://example.com/post/1");

        AiTitleService.StylePrompt first = service.resolveStylePrompt("freestyle", uri).orElseThrow();

        assertTrue(service.markFreestyleSelectionSucceeded(uri, first));

        clock.setMillis(29_999L);
        AiTitleService.StylePrompt stillStable = service.resolveStylePrompt("freestyle", uri).orElseThrow();
        assertEquals(first.style(), stillStable.style());
        assertEquals(first.promptHash(), stillStable.promptHash());
        assertFalse(service.markFreestyleSelectionSucceeded(uri, stillStable));

        clock.setMillis(30_001L);
        AiTitleService.StylePrompt refreshed = service.resolveStylePrompt("freestyle", uri).orElseThrow();
        assertTrue(service.markFreestyleSelectionSucceeded(uri, refreshed));
    }

    @Test
    void cleanTitleKeepsOnlyOnePlainTitleLine() {
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of()), new FakeOpenAiCompatibleTextClient(), null, null);

        assertEquals("一个更有点击欲的标题", service.cleanTitle("""
                ```markdown
                标题：“一个更有点击欲的标题”
                解释：这里不应该保留
                ```
                """));
    }

    @Test
    void generateStyledMetadataFallsBackAcrossEnabledProviders() {
        AiProviderRecord first = provider(1L, 1);
        AiProviderRecord second = provider(2L, 2);
        FakeOpenAiCompatibleTextClient client = new FakeOpenAiCompatibleTextClient();
        client.failProviderIds.add(1L);
        client.title = "\"最终标题\"";
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of(first, second)), client, null, null);

        Optional<PreviewMetadata> result = service.generateStyledMetadata(
                generatedTextMetadata(),
                new AiTitleService.StylePrompt("fun", "UC 风格", AiTitleService.DEFAULT_TITLE_FORMAT_PROMPT, "hash")
        );

        assertTrue(result.isPresent());
        assertEquals("最终标题", result.get().title());
        assertEquals("原始标题", generatedTextMetadata().title());
        assertIterableEquals(List.of(1L, 2L), client.requestedProviderIds);
        assertEquals("UC 风格", client.requestedPrompts.get(0).stylePrompt());
        assertEquals("原文正文", client.requestedPrompts.get(0).rawContent());
    }

    @Test
    void generateStyledMetadataMovesFailedProviderToBottomAfterThreshold() {
        AiProviderRecord first = provider(1L, 100);
        AiProviderRecord second = provider(2L, 200);
        FakeAiProviderMapper mapper = new FakeAiProviderMapper(List.of(first, second));
        FakeOpenAiCompatibleTextClient client = new FakeOpenAiCompatibleTextClient();
        client.failProviderIds.add(1L);
        CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
        AiProviderDowngradeService downgradeService = new AiProviderDowngradeService(
                FakeProviderConfigMapper.aiProviderDowngradeConfig(true, 2),
                mapper,
                Clock.fixed(Instant.ofEpochMilli(1234L), ZoneOffset.UTC),
                eventPublisher
        );
        AiTitleService service = new AiTitleService(null, mapper, client, null, downgradeService);

        service.generateStyledMetadata(
                generatedTextMetadata(),
                new AiTitleService.StylePrompt("fun", "UC 风格", AiTitleService.DEFAULT_TITLE_FORMAT_PROMPT, "hash")
        );
        service.generateStyledMetadata(
                generatedTextMetadata(),
                new AiTitleService.StylePrompt("fun", "UC 风格", AiTitleService.DEFAULT_TITLE_FORMAT_PROMPT, "hash")
        );

        assertIterableEquals(List.of(1L, 2L, 1L, 2L), client.requestedProviderIds);
        assertEquals(200, first.getSortOrder());
        assertEquals(100, second.getSortOrder());
        assertEquals(2, eventPublisher.events.stream().filter(AiProviderRequestFailedEvent.class::isInstance).count());
        assertEquals(1, eventPublisher.events.stream().filter(AiProviderAutoDowngradedEvent.class::isInstance).count());
        AiProviderRequestFailedEvent failure = eventPublisher.events.stream()
                .filter(AiProviderRequestFailedEvent.class::isInstance)
                .map(AiProviderRequestFailedEvent.class::cast)
                .reduce((ignored, current) -> current)
                .orElseThrow();
        assertEquals("AI_TITLE", failure.operation());
        assertTrue(failure.downgradeTriggered());
        AiProviderAutoDowngradedEvent downgraded = eventPublisher.events.stream()
                .filter(AiProviderAutoDowngradedEvent.class::isInstance)
                .map(AiProviderAutoDowngradedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("AI_TITLE", downgraded.operation());
        assertEquals(2, downgraded.failureCount());
        assertEquals(2, downgraded.failureThreshold());
    }

    @Test
    void generateStyledMetadataPublishesFailureEventWhenDowngradeDisabled() {
        AiProviderRecord first = provider(1L, 100);
        FakeAiProviderMapper mapper = new FakeAiProviderMapper(List.of(first));
        FakeOpenAiCompatibleTextClient client = new FakeOpenAiCompatibleTextClient();
        client.failProviderIds.add(1L);
        CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
        AiProviderDowngradeService downgradeService = new AiProviderDowngradeService(
                FakeProviderConfigMapper.aiProviderDowngradeConfig(false, 2),
                mapper,
                Clock.fixed(Instant.ofEpochMilli(1234L), ZoneOffset.UTC),
                eventPublisher
        );
        AiTitleService service = new AiTitleService(null, mapper, client, null, downgradeService);

        service.generateStyledMetadata(
                generatedTextMetadata(),
                new AiTitleService.StylePrompt("fun", "UC 风格", AiTitleService.DEFAULT_TITLE_FORMAT_PROMPT, "hash")
        );

        AiProviderRequestFailedEvent failure = eventPublisher.events.stream()
                .filter(AiProviderRequestFailedEvent.class::isInstance)
                .map(AiProviderRequestFailedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("AI_TITLE", failure.operation());
        assertFalse(failure.downgradeEnabled());
        assertEquals(0, failure.failureCount());
        assertFalse(failure.downgradeTriggered());
        assertEquals(0, eventPublisher.events.stream().filter(AiProviderAutoDowngradedEvent.class::isInstance).count());
    }

    @Test
    void generateStyledMetadataSkipsRealImageCards() {
        FakeOpenAiCompatibleTextClient client = new FakeOpenAiCompatibleTextClient();
        AiTitleService service = new AiTitleService(null, new FakeAiProviderMapper(List.of(provider(1L, 1))), client, null, null);

        Optional<PreviewMetadata> result = service.generateStyledMetadata(
                new PreviewMetadata(
                        "https://example.com/source",
                        "https://example.com/canonical",
                        "bilibili",
                        "真实图片标题",
                        "描述",
                        "Bilibili",
                        "https://i.example.com/thumb.jpg",
                        1200,
                        630,
                        ContentType.VIDEO,
                        "正文"
                ),
                new AiTitleService.StylePrompt("fun", "UC 风格", AiTitleService.DEFAULT_TITLE_FORMAT_PROMPT, "hash")
        );

        assertTrue(result.isEmpty());
        assertTrue(client.requestedProviderIds.isEmpty());
    }

    private PreviewMetadata generatedTextMetadata() {
        return new PreviewMetadata(
                "https://example.com/source",
                "https://example.com/canonical",
                "v2ex",
                "原始标题",
                "描述",
                "V2EX",
                "generated://v2ex/title-card/1",
                1200,
                630,
                ContentType.ARTICLE,
                "原文正文"
        );
    }

    private static AiProviderRecord provider(long id, int sortOrder) {
        AiProviderRecord provider = new AiProviderRecord();
        provider.setId(id);
        provider.setName("provider-" + id);
        provider.setEnabled(true);
        provider.setSortOrder(sortOrder);
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setApiKind("CHAT_COMPLETIONS");
        provider.setModel("gpt-test");
        provider.setEffort("");
        provider.setApiKey("");
        provider.setUpdatedAt(1L);
        return provider;
    }

    private static AiTitleConfigService configService(String configuredOutputConstraint) {
        return new AiTitleConfigService(
                new FakeProviderConfigMapper(configuredOutputConstraint),
                Clock.fixed(Instant.ofEpochMilli(1234L), ZoneOffset.UTC)
        );
    }

    private static final class MutableClock extends Clock {
        private long epochMillis;

        private MutableClock(long epochMillis) {
            this.epochMillis = epochMillis;
        }

        private void setMillis(long epochMillis) {
            this.epochMillis = epochMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMillis);
        }
    }

    private static final class FakeAdminPromptMapper implements AdminPromptMapper {
        private final List<AdminPromptRecord> prompts;

        private FakeAdminPromptMapper(AdminPromptRecord prompt) {
            this(List.of(prompt));
        }

        private FakeAdminPromptMapper(List<AdminPromptRecord> prompts) {
            this.prompts = prompts;
        }

        @Override
        public List<AdminPromptRecord> selectAllPrompts() {
            return prompts;
        }

        @Override
        public List<String> selectStyles() {
            return prompts.stream().map(AdminPromptRecord::getStyle).toList();
        }

        @Override
        public AdminPromptRecord selectPrompt(String style) {
            return prompts.stream()
                    .filter(prompt -> prompt.getStyle().equals(style))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void upsertPrompt(AdminPromptRecord prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deletePrompt(String style) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeProviderConfigMapper implements ProviderConfigMapper {
        private final Map<String, ProviderConfigRecord> configs;

        private FakeProviderConfigMapper(String configuredOutputConstraint) {
            this(configuredOutputConstraint == null
                    ? Map.of()
                    : Map.of(key(AiTitleConfigService.PROVIDER_AI_TITLE, AiTitleConfigService.TITLE_FORMAT_PROMPT_KEY), configuredOutputConstraint));
        }

        private FakeProviderConfigMapper(Map<String, String> values) {
            this.configs = new HashMap<>();
            values.forEach((key, value) -> {
                String[] parts = key.split("\n", 2);
                ProviderConfigRecord record = new ProviderConfigRecord();
                record.setProviderId(parts[0]);
                record.setConfigKey(parts[1]);
                record.setConfigValue(value);
                record.setUpdatedAt(1234L);
                configs.put(key, record);
            });
        }

        private static FakeProviderConfigMapper aiProviderDowngradeConfig(boolean enabled, int threshold) {
            return new FakeProviderConfigMapper(Map.of(
                    key(AiProviderDowngradeService.PROVIDER_AI_PROVIDER, AiProviderDowngradeService.AUTO_DOWNGRADE_ENABLED_KEY),
                    Boolean.toString(enabled),
                    key(AiProviderDowngradeService.PROVIDER_AI_PROVIDER, AiProviderDowngradeService.AUTO_DOWNGRADE_FAILURE_THRESHOLD_KEY),
                    Integer.toString(threshold)
            ));
        }

        private static String key(String providerId, String configKey) {
            return providerId + "\n" + configKey;
        }

        @Override
        public List<ProviderConfigRecord> selectAllConfigs() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProviderConfigRecord> selectProviderConfigs(String providerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderConfigRecord selectConfig(String providerId, String configKey) {
            return configs.get(key(providerId, configKey));
        }

        @Override
        public void upsertConfig(ProviderConfigRecord config) {
            configs.put(key(config.getProviderId(), config.getConfigKey()), config);
        }
    }

    private static final class FakeAiProviderMapper implements AiProviderMapper {
        private final List<AiProviderRecord> providers;

        private FakeAiProviderMapper(List<AiProviderRecord> providers) {
            this.providers = providers;
        }

        @Override
        public List<AiProviderRecord> selectAllProviders() {
            return providers.stream()
                    .sorted(Comparator.comparingInt(AiProviderRecord::getSortOrder).thenComparing(AiProviderRecord::getId))
                    .toList();
        }

        @Override
        public List<AiProviderRecord> selectEnabledProviders() {
            return selectAllProviders();
        }

        @Override
        public AiProviderRecord selectProvider(long id) {
            return providers.stream()
                    .filter(provider -> provider.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void insertProvider(AiProviderRecord provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateProvider(AiProviderRecord provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateProviderEnabled(long id, boolean enabled, long updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateProviderSortOrder(long id, int sortOrder, long updatedAt) {
            AiProviderRecord provider = selectProvider(id);
            if (provider == null) {
                return 0;
            }
            provider.setSortOrder(sortOrder);
            provider.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public int deleteProvider(long id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeOpenAiCompatibleTextClient extends OpenAiCompatibleTextClient {
        private final List<Long> failProviderIds = new ArrayList<>();
        private final List<Long> requestedProviderIds = new ArrayList<>();
        private final List<AiTitlePrompt> requestedPrompts = new ArrayList<>();
        private String title = "AI 标题";

        private FakeOpenAiCompatibleTextClient() {
            super(null, null);
        }

        @Override
        public Optional<String> generateTitle(AiProviderRecord provider, AiTitlePrompt prompt) throws IOException {
            return generateTitleResult(provider, prompt).title();
        }

        @Override
        public TitleResult generateTitleResult(AiProviderRecord provider, AiTitlePrompt prompt) throws IOException {
            requestedProviderIds.add(provider.getId());
            requestedPrompts.add(prompt);
            if (failProviderIds.contains(provider.getId())) {
                throw new IOException("provider failed");
            }
            return new TitleResult(Optional.ofNullable(title), 25);
        }
    }

    private static final class CapturingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }
}
