package io.github.shigella520.linkpeek.server.controller;

import io.github.shigella520.linkpeek.core.error.UpstreamFetchException;
import io.github.shigella520.linkpeek.core.model.ContentType;
import io.github.shigella520.linkpeek.core.model.PreviewKey;
import io.github.shigella520.linkpeek.core.model.PreviewMetadata;
import io.github.shigella520.linkpeek.core.provider.PreviewProvider;
import io.github.shigella520.linkpeek.server.admin.model.AiProviderRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageRecord;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import io.github.shigella520.linkpeek.server.admin.service.NotificationService;
import io.github.shigella520.linkpeek.server.admin.service.AiTitleConfigService;
import io.github.shigella520.linkpeek.server.admin.service.ProviderConfigService;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryAudioClient;
import io.github.shigella520.linkpeek.server.admin.service.ShareSummaryImageClient;
import io.github.shigella520.linkpeek.server.ai.AiTextPrompt;
import io.github.shigella520.linkpeek.server.ai.AiProviderDowngradeService;
import io.github.shigella520.linkpeek.server.ai.AiTitleClient;
import io.github.shigella520.linkpeek.server.ai.AiTitlePrompt;
import io.github.shigella520.linkpeek.server.service.PreviewService;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsClientType;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsEventDeduplicator;
import io.github.shigella520.linkpeek.server.stats.service.StatisticsRecorder;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.URI;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PreviewControllerTest {
    private static final Path TEST_CACHE_DIR;
    private static final Path TEST_STATS_DIR;
    private static final Path TEST_STATS_DB;
    private static final Path TEST_WEB_ICON;
    private static final Path TEST_SERVICE_LOG;

    static {
        try {
            TEST_CACHE_DIR = Files.createTempDirectory("linkpeek-server-cache");
            TEST_STATS_DIR = Files.createTempDirectory("linkpeek-server-stats");
            TEST_STATS_DB = TEST_STATS_DIR.resolve("linkpeek-test.db");
            TEST_WEB_ICON = TEST_STATS_DIR.resolve("favicon.svg");
            TEST_SERVICE_LOG = TEST_STATS_DIR.resolve("service.log");
            writeTestWebIcon();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("linkpeek.cache-dir", () -> TEST_CACHE_DIR.toString());
        registry.add("linkpeek.stats-db-path", () -> TEST_STATS_DB.toString());
        registry.add("linkpeek.base-url", () -> "https://preview.example.com");
        registry.add("linkpeek.web-icon-path", () -> TEST_WEB_ICON.toString());
        registry.add("linkpeek.stats-admin-password", () -> "test-admin-password");
        registry.add("linkpeek.service-log-path", () -> TEST_SERVICE_LOG.toString());
        registry.add("logging.file.name", () -> TEST_STATS_DIR.resolve("spring-test.log").toString());
        registry.add("management.endpoints.web.exposure.include", () -> "health");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProviderConfigService providerConfigService;

    @Autowired
    private AiProviderDowngradeService aiProviderDowngradeService;

    @Autowired
    private TestPreviewProvider testPreviewProvider;

    @Autowired
    private TestAiTitleClient testAiTitleClient;

    @Autowired
    private TestShareSummaryImageClient testShareSummaryImageClient;

    @Autowired
    private TestShareSummaryAudioClient testShareSummaryAudioClient;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    @Qualifier("notificationWebhookExecutor")
    private ExecutorService notificationWebhookExecutor;

    @Autowired
    private StatisticsEventDeduplicator statisticsEventDeduplicator;

    @Autowired
    private StatisticsRecorder statisticsRecorder;

    @BeforeEach
    void setUp() throws IOException {
        waitForNotificationQueueToDrain();
        Files.walk(TEST_CACHE_DIR)
                .filter(path -> !path.equals(TEST_CACHE_DIR))
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        Files.createDirectories(TEST_CACHE_DIR);
        writeTestWebIcon();
        Files.deleteIfExists(TEST_SERVICE_LOG);
        jdbcTemplate.execute("DELETE FROM stats_event");
        jdbcTemplate.execute("DELETE FROM stats_link");
        jdbcTemplate.execute("DELETE FROM share_summary_image");
        jdbcTemplate.execute("DELETE FROM share_summary_image_config");
        jdbcTemplate.execute("DELETE FROM share_summary_audio");
        jdbcTemplate.execute("DELETE FROM share_summary_audio_config");
        jdbcTemplate.execute("DELETE FROM share_summary_run");
        jdbcTemplate.execute("DELETE FROM share_summary_task");
        jdbcTemplate.execute("DELETE FROM notification_delivery");
        jdbcTemplate.execute("DELETE FROM notification_task_channel");
        jdbcTemplate.execute("DELETE FROM notification_task");
        jdbcTemplate.execute("DELETE FROM notification_channel");
        jdbcTemplate.execute("DELETE FROM admin_prompt");
        jdbcTemplate.execute("DELETE FROM provider_config");
        jdbcTemplate.execute("DELETE FROM ai_provider");

        testPreviewProvider.reset();
        testAiTitleClient.reset();
        testShareSummaryImageClient.reset();
        testShareSummaryAudioClient.reset();
        statisticsEventDeduplicator.clear();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        Files.walk(TEST_CACHE_DIR)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        Files.walk(TEST_STATS_DIR)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
    }

    @Test
    void rootRedirectsToDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/dashboard"));
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void actuatorHealthEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiJsonEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"title\":\"LinkPeek API\"")))
                .andExpect(content().string(containsString("\"/preview\"")))
                .andExpect(content().string(containsString("\"/api/preview/support\"")))
                .andExpect(content().string(containsString("\"/api/preview/styles\"")));
    }

    @Test
    void docHtmlEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/doc.html"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            status == 200 || status == 302,
                            "Expected 200 or 302 for /doc.html but got " + status
                    );
                });
    }

    @Test
    void dashboardPageAndAssetsAreExposed() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("LinkPeek Dashboard")))
                .andExpect(content().string(containsString("Copy LinkPeek URL")))
                .andExpect(content().string(containsString("link-builder-input")))
                .andExpect(content().string(containsString("link-builder-style")))
                .andExpect(content().string(containsString("/dashboard/styles.css?v=20260612-dashboard-mobile-compact-2")))
                .andExpect(content().string(not(containsString(">Default<"))))
                .andExpect(content().string(containsString("ai-render-rate-inline")))
                .andExpect(content().string(containsString("ai-success-rate-inline")))
                .andExpect(content().string(containsString("/favicon.ico")))
                .andExpect(content().string(containsString("https://github.com/shigella520/LinkPeek")));

        mockMvc.perform(get("/dashboard/styles.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("gap: 14px")))
                .andExpect(content().string(containsString("font-size: 24px")));

        mockMvc.perform(get("/dashboard/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("application/javascript")))
                .andExpect(content().string(containsString("/api/preview/styles")))
                .andExpect(content().string(containsString("FREESTYLE")))
                .andExpect(content().string(not(containsString("textContent = \"Default\""))))
                .andExpect(content().string(containsString("styleSelect.addEventListener")));

        mockMvc.perform(get("/admin"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/admin/login?next=/admin"));

        Cookie adminCookie = adminCookie();

        mockMvc.perform(get("/admin")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("LinkPeek Admin")))
                .andExpect(content().string(containsString("/admin/styles.css?v=20260612-admin-mobile-polish-2")))
                .andExpect(content().string(containsString("https://github.com/shigella520/LinkPeek")))
                .andExpect(content().string(containsString("brand-text")))
                .andExpect(content().string(containsString("/admin/app.js?v=20260612-admin-mobile-polish-2")))
                .andExpect(content().string(not(containsString("brand-copy"))))
                .andExpect(content().string(containsString("provider-config")))
                .andExpect(content().string(containsString("service-logs")))
                .andExpect(content().string(containsString("ai-providers")))
                .andExpect(content().string(containsString("preview-events")))
                .andExpect(content().string(containsString("share-summary")))
                .andExpect(content().string(containsString("notifications")))
                .andExpect(content().string(containsString("notification-filter-share-task-options")))
                .andExpect(content().string(containsString("notification-filter-period-options")))
                .andExpect(content().string(containsString("notification-filter-trigger-options")))
                .andExpect(content().string(containsString("preview-event-form")))
                .andExpect(content().string(containsString("preview-event-table")))
                .andExpect(content().string(containsString("ai-new-button")))
                .andExpect(content().string(containsString("ai-api-kind")))
                .andExpect(content().string(containsString("https://api.openai.com/v1")))
                .andExpect(content().string(containsString("prompt-modal")))
                .andExpect(content().string(containsString("ai-title-config-form")))
                .andExpect(content().string(containsString("ai-title-format-prompt")))
                .andExpect(content().string(containsString("Title Format Prompt")))
                .andExpect(content().string(containsString("Style Prompt")))
                .andExpect(content().string(not(containsString("{raw_content}"))))
                .andExpect(content().string(containsString("ai-modal")))
                .andExpect(content().string(not(containsString("side-nav"))))
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    int promptIndex = html.indexOf("id=\"prompts\"");
                    int aiIndex = html.indexOf("id=\"ai-providers\"");
                    int previewEventsIndex = html.indexOf("id=\"preview-events\"");
                    int shareSummaryIndex = html.indexOf("id=\"share-summary\"");
                    int notificationIndex = html.indexOf("id=\"notifications\"");
                    int providerIndex = html.indexOf("id=\"provider-config\"");
                    int logsIndex = html.indexOf("id=\"service-logs\"");
                    int purgeIndex = html.indexOf("id=\"purge\"");
                    org.junit.jupiter.api.Assertions.assertTrue(
                            promptIndex >= 0
                                    && promptIndex < aiIndex
                                    && aiIndex < previewEventsIndex
                                    && previewEventsIndex < shareSummaryIndex
                                    && shareSummaryIndex < notificationIndex
                                    && notificationIndex < providerIndex
                                    && providerIndex < logsIndex
                                    && logsIndex < purgeIndex,
                            "Expected admin module order: prompts, AI providers, preview events, share summary, notifications, provider config, service logs, purge."
                    );
                });

        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("login-form")))
                .andExpect(content().string(containsString("/admin/styles.css?v=20260612-admin-mobile-polish-2")))
                .andExpect(content().string(containsString("class=\"login-head brand-mark\"")))
                .andExpect(content().string(containsString("class=\"brand-text\"")))
                .andExpect(content().string(not(containsString(">Management<"))))
                .andExpect(content().string(containsString("/admin/login.js")));

        mockMvc.perform(get("/admin/styles.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("--shadow-soft")))
                .andExpect(content().string(containsString("backdrop-filter: blur(26px)")))
                .andExpect(content().string(containsString("linear-gradient(180deg, #f8f7f3 0%")))
                .andExpect(content().string(containsString(".brand-text")))
                .andExpect(content().string(containsString(".icon-link svg")))
                .andExpect(content().string(containsString(".share-summary-task-row")))
                .andExpect(content().string(containsString("gap: 14px")))
                .andExpect(content().string(containsString("font-size: 24px")))
                .andExpect(content().string(not(containsString(".brand-copy"))))
                .andExpect(content().string(containsString("body.admin-sidebar-pinned.admin-nav-open .admin-sidebar")))
                .andExpect(content().string(containsString("background: #ffffff")))
                .andExpect(content().string(containsString("body.admin-nav-hover-open .admin-drawer-backdrop")))
                .andExpect(content().string(containsString("backdrop-filter: none")))
                .andExpect(content().string(containsString(".checkbox-row > input[type=\"checkbox\"] + span::before")))
                .andExpect(content().string(containsString(".inline-threshold-input:focus")));

        mockMvc.perform(get("/admin/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("application/javascript")))
                .andExpect(content().string(containsString("/api/admin/logs")))
                .andExpect(content().string(containsString("openAdminNavigationFromHover")))
                .andExpect(content().string(containsString("adminNavHoverOpen")))
                .andExpect(content().string(containsString("isAdminNavigationPointerInside")))
                .andExpect(content().string(containsString("admin-nav-hover-open")))
                .andExpect(content().string(containsString("/api/admin/ai-title-config")))
                .andExpect(content().string(containsString("/api/admin/preview-events")))
                .andExpect(content().string(containsString("/api/admin/share-summary")))
                .andExpect(content().string(containsString("/api/admin/notifications")))
                .andExpect(content().string(containsString("renderNotificationDeliveryEvent")))
                .andExpect(content().string(containsString("eventKeyTargetId")));

        mockMvc.perform(get("/admin/login.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("application/javascript")));

        mockMvc.perform(get("/webjars/echarts/5.5.1/dist/echarts.min.js"))
                .andExpect(status().isOk());
    }

    @Test
    void faviconEndpointReturnsConfiguredIcon() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("image/svg+xml")))
                .andExpect(content().string(containsString("fill=\"#0a84ff\"")));
    }

    @Test
    void faviconEndpointFallsBackToBundledDefaultWhenConfiguredIconIsMissing() throws Exception {
        Files.deleteIfExists(TEST_WEB_ICON);

        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("image/svg+xml")))
                .andExpect(content().string(containsString("Background")));
    }

    @Test
    void crawlerRequestReturnsOgHtmlAndCachesMetadata() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("og:image")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/media/thumb/" + key().value() + ".jpg")));
    }

    @Test
    void duplicateCrawlerPreviewCreatedEventsAreSkippedWithinDedupeTtl() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk());
        long firstLastSeenAt = jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM stats_link WHERE preview_key = ?",
                Long.class,
                key().value()
        );

        Thread.sleep(25);
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Applebot/0.1"))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stats_event WHERE event_type = 'PREVIEW_CREATED' AND client_type = 'CRAWLER'",
                        Integer.class
                )
        );
        org.junit.jupiter.api.Assertions.assertTrue(
                jdbcTemplate.queryForObject(
                        "SELECT last_seen_at FROM stats_link WHERE preview_key = ?",
                        Long.class,
                        key().value()
                ) > firstLastSeenAt
        );
    }

    @Test
    void firstCrawlerRequestKeepsPreviewCreatedClaimWhenDuplicateFinishesFirst() throws Exception {
        PreviewService.ResolvedPreview resolvedPreview = new PreviewService.ResolvedPreview(
                URI.create("https://video.example.com/watch/abc"),
                URI.create("https://video.example.com/watch/abc"),
                key(),
                testPreviewProvider
        );
        PreviewMetadata metadata = testPreviewProvider.resolve(resolvedPreview.sourceUrl());
        StatisticsEventDeduplicator.Claim firstClaim = statisticsRecorder.claimPreviewCreated(
                resolvedPreview,
                StatisticsClientType.CRAWLER,
                200,
                null
        );
        StatisticsEventDeduplicator.Claim duplicateClaim = statisticsRecorder.claimPreviewCreated(
                resolvedPreview,
                StatisticsClientType.CRAWLER,
                200,
                null
        );

        statisticsRecorder.recordPreviewCreated(
                new PreviewService.PreviewLoadResult(resolvedPreview, metadata, true),
                StatisticsClientType.CRAWLER,
                200,
                7_000,
                duplicateClaim
        );
        statisticsRecorder.recordPreviewCreated(
                new PreviewService.PreviewLoadResult(resolvedPreview, metadata, false).withCrawlDuration(123),
                StatisticsClientType.CRAWLER,
                200,
                456,
                firstClaim
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stats_event WHERE event_type = 'PREVIEW_CREATED' AND client_type = 'CRAWLER'",
                        Integer.class
                )
        );
        org.junit.jupiter.api.Assertions.assertFalse(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT cache_hit FROM stats_event WHERE event_type = 'PREVIEW_CREATED'",
                Boolean.class
        )));
        org.junit.jupiter.api.Assertions.assertEquals(123L, jdbcTemplate.queryForObject(
                "SELECT crawl_duration_ms FROM stats_event WHERE event_type = 'PREVIEW_CREATED'",
                Long.class
        ));
    }

    @Test
    void browserPreviewOpenedEventsAreNotDeduped() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isFound());

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isFound());

        org.junit.jupiter.api.Assertions.assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stats_event WHERE event_type = 'PREVIEW_OPENED' AND client_type = 'BROWSER'",
                        Integer.class
                )
        );
    }

    @Test
    void browserRequestRedirectsToOriginalUrl() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://video.example.com/watch/abc"));

        awaitLinkTitle("Stub title");
    }

    @Test
    void renderModeHeaderCanForceCrawlerHtmlForSwaggerUi() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
                        .header("X-LinkPeek-Render-Mode", "crawler"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("og:title")));
    }

    @Test
    void previewStyleUsesAiTitleForGeneratedTextCardsAndCachesResult() throws Exception {
        testPreviewProvider.generatedTextCard.set(true);
        testAiTitleClient.generatedTitle.set("\"AI 生成标题\"");
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "FUN", "UC 风格", now
        );
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1/chat/completions", "test-model", "low", "test-key", now
        );

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .param("style", "fun")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("AI 生成标题")
                ))
                .andExpect(content().string(not(containsString("/media/thumb/" + key().value() + ".jpg"))));

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .param("style", "fun")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("AI 生成标题")
                ));

        org.junit.jupiter.api.Assertions.assertEquals(1, testAiTitleClient.requests.get());
        org.junit.jupiter.api.Assertions.assertEquals("UC 风格", testAiTitleClient.prompt.get().stylePrompt());
        org.junit.jupiter.api.Assertions.assertEquals("原始帖子正文，包含需要被 AI 总结的信息。", testAiTitleClient.prompt.get().rawContent());
        org.junit.jupiter.api.Assertions.assertTrue(testAiTitleClient.prompt.get().titleFormatPrompt().contains("只返回一行中文标题文本"));
        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stats_event WHERE event_type = 'PREVIEW_CREATED' AND ai_requested = 1 AND ai_succeeded = 1",
                        Integer.class
                )
        );
        Map<String, Object> firstCreatedEvent = jdbcTemplate.queryForMap(
                """
                        SELECT source_url, requested_style, actual_style, ai_provider_names, ai_duration_ms, crawl_duration_ms, duration_ms, cache_hit
                        FROM stats_event
                        WHERE event_type = 'PREVIEW_CREATED'
                        ORDER BY id ASC
                        LIMIT 1
                        """
        );
        org.junit.jupiter.api.Assertions.assertEquals("https://video.example.com/watch/abc", firstCreatedEvent.get("source_url"));
        org.junit.jupiter.api.Assertions.assertEquals("FUN", firstCreatedEvent.get("requested_style"));
        org.junit.jupiter.api.Assertions.assertEquals("FUN", firstCreatedEvent.get("actual_style"));
        org.junit.jupiter.api.Assertions.assertEquals("local", firstCreatedEvent.get("ai_provider_names"));
        org.junit.jupiter.api.Assertions.assertEquals(12, ((Number) firstCreatedEvent.get("ai_duration_ms")).intValue());
        org.junit.jupiter.api.Assertions.assertTrue(((Number) firstCreatedEvent.get("crawl_duration_ms")).longValue() >= 0);
        org.junit.jupiter.api.Assertions.assertTrue(((Number) firstCreatedEvent.get("duration_ms")).longValue() >= 0);
        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) firstCreatedEvent.get("cache_hit")).intValue());
    }

    @Test
    void styledGeneratedCardImageUrlChangesWhenAiTitleChangesAfterCacheClear() throws Exception {
        testPreviewProvider.generatedTextCard.set(true);
        testAiTitleClient.generatedTitle.set("\"AI 第一标题\"");
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "FUN", "UC 风格", now
        );
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1/chat/completions", "test-model", "low", "test-key", now
        );

        MvcResult first = mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .param("style", "fun")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andReturn();
        String firstHtml = first.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(firstHtml.contains("AI 第一标题"));
        String firstImageUrl = ogImageUrl(firstHtml);
        String styledPreviewKey = jdbcTemplate.queryForObject(
                "SELECT preview_key FROM stats_event WHERE event_type = 'PREVIEW_CREATED' ORDER BY id DESC LIMIT 1",
                String.class
        );

        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", styledPreviewKey)
                        .param("v", imageVersion(firstImageUrl)))
                .andExpect(status().isOk());

        Cookie cookie = adminCookie();
        mockMvc.perform(delete("/api/admin/preview-events/{previewKey}/cache", styledPreviewKey)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewKey").value(styledPreviewKey))
                .andExpect(jsonPath("$.deletedFiles").value(2));

        testAiTitleClient.generatedTitle.set("\"AI 第二标题\"");
        MvcResult second = mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .param("style", "fun")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andReturn();
        String secondHtml = second.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(secondHtml.contains("AI 第二标题"));
        String secondImageUrl = ogImageUrl(secondHtml);

        org.junit.jupiter.api.Assertions.assertEquals(styledPreviewKey, jdbcTemplate.queryForObject(
                "SELECT preview_key FROM stats_event WHERE event_type = 'PREVIEW_CREATED' ORDER BY id DESC LIMIT 1",
                String.class
        ));
        org.junit.jupiter.api.Assertions.assertNotEquals(firstImageUrl, secondImageUrl);
        org.junit.jupiter.api.Assertions.assertEquals(2, testAiTitleClient.requests.get());

        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", styledPreviewKey)
                        .param("v", imageVersion(secondImageUrl)))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(2, testPreviewProvider.thumbnailDownloads.get());
    }

    @Test
    void previewFreestyleUsesRandomConfiguredStylePrompt() throws Exception {
        testPreviewProvider.generatedTextCard.set(true);
        testAiTitleClient.generatedTitle.set("\"AI freestyle 标题\"");
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "FUN", "UC 风格", now
        );
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1/chat/completions", "test-model", "low", "test-key", now
        );

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .param("style", "freestyle")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("AI freestyle 标题")
                ));

        org.junit.jupiter.api.Assertions.assertEquals(1, testAiTitleClient.requests.get());
        org.junit.jupiter.api.Assertions.assertEquals("UC 风格", testAiTitleClient.prompt.get().stylePrompt());
    }

    @Test
    void concurrentFreestylePreviewUsesSameStableStyleAndWaitsForCachedAiResult() throws Exception {
        testPreviewProvider.generatedTextCard.set(true);
        testAiTitleClient.generatedTitle.set("\"AI 并发标题\"");
        testAiTitleClient.blockNextRequest();
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "WORK", "工作风格", now
        );
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "TB", "淘宝风格", now
        );
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1/chat/completions", "test-model", "low", "test-key", now
        );

        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> performFreestylePreview());
        org.junit.jupiter.api.Assertions.assertTrue(testAiTitleClient.awaitBlockedRequest());
        CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(() -> performFreestylePreview());
        Thread.sleep(50);
        org.junit.jupiter.api.Assertions.assertEquals(1, testAiTitleClient.requests.get());
        testAiTitleClient.releaseBlockedRequest();

        org.junit.jupiter.api.Assertions.assertTrue(
                first.get(2, TimeUnit.SECONDS).getResponse().getContentAsString(StandardCharsets.UTF_8).contains("AI 并发标题")
        );
        org.junit.jupiter.api.Assertions.assertTrue(
                second.get(2, TimeUnit.SECONDS).getResponse().getContentAsString(StandardCharsets.UTF_8).contains("AI 并发标题")
        );
        org.junit.jupiter.api.Assertions.assertEquals(1, testAiTitleClient.requests.get());
        org.junit.jupiter.api.Assertions.assertEquals(1, testPreviewProvider.resolutions.get());
        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT preview_key) FROM stats_event WHERE event_type = 'PREVIEW_CREATED'",
                        Integer.class
                )
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM stats_event WHERE event_type = 'PREVIEW_CREATED'",
                        Integer.class
                )
        );
    }

    @Test
    void previewStyleDoesNotUseAiForRealImageCards() throws Exception {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "FUN", "UC 风格", now
        );
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1/chat/completions", "test-model", "", "test-key", now
        );

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .param("style", "fun")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stub title")));

        org.junit.jupiter.api.Assertions.assertEquals(0, testAiTitleClient.requests.get());
    }

    @Test
    void invalidRenderModeHeaderReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header("X-LinkPeek-Render-Mode", "invalid-mode"))
                .andExpect(status().isBadRequest());
    }

    private MvcResult performFreestylePreview() {
        try {
            return mockMvc.perform(get("/preview")
                            .param("url", "https://video.example.com/watch/abc")
                            .param("style", "freestyle")
                            .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                    .andExpect(status().isOk())
                    .andReturn();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    @Test
    void invalidUrlReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "notaurl")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedUrlReturnsUnprocessableEntity() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://unsupported.example.com/post/1")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void upstreamCrawlFailurePublishesDataCrawlNotification() throws Exception {
        long channelId = insertLoopbackNotificationChannel("Crawl");
        insertNotificationTask(
                "爬取失败通知",
                "DATA_CRAWL_REQUEST_FAILED",
                "爬取失败 {{preview.providerId}} {{preview.previewKey}} {{request.httpStatus}} {{error.code}} {{error.type}}",
                channelId
        );
        testPreviewProvider.resolveFails.set(true);

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isBadGateway());

        waitForNotificationDeliveryByEventType("DATA_CRAWL_REQUEST_FAILED");
        String body = jdbcTemplate.queryForObject(
                "SELECT request_body_snapshot FROM notification_delivery WHERE event_type = 'DATA_CRAWL_REQUEST_FAILED' ORDER BY id DESC LIMIT 1",
                String.class
        );
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("爬取失败 stub"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("502 UPSTREAM_ERROR UpstreamFetchException"));
    }

    @Test
    void invalidAndUnsupportedPreviewDoNotPublishDataCrawlNotification() throws Exception {
        long channelId = insertLoopbackNotificationChannel("Crawl");
        insertNotificationTask(
                "爬取失败通知",
                "DATA_CRAWL_REQUEST_FAILED",
                "爬取失败 {{preview.providerId}}",
                channelId
        );

        mockMvc.perform(get("/preview")
                        .param("url", "notaurl")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/preview")
                        .param("url", "https://unsupported.example.com/post/1")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isUnprocessableEntity());

        org.junit.jupiter.api.Assertions.assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM notification_delivery WHERE event_type = 'DATA_CRAWL_REQUEST_FAILED'",
                        Integer.class
                )
        );
    }

    @Test
    void aiProviderDowngradeCreatesFailureDeliveryBeforeAutoDowngradeDelivery() throws Exception {
        long channelId = insertLoopbackNotificationChannel("AI Provider");
        insertNotificationTask(
                "AI Provider 失败通知",
                "AI_PROVIDER_REQUEST_FAILED",
                "失败 {{provider.name}} {{request.operation}} {{downgrade.failureCount}}/{{downgrade.failureThreshold}} triggered={{downgrade.triggered}}",
                channelId
        );
        insertNotificationTask(
                "AI Provider 自动降级通知",
                "AI_PROVIDER_AUTO_DOWNGRADED",
                "降级 {{provider.name}} {{request.operation}} {{downgrade.failureCount}}/{{downgrade.failureThreshold}} {{downgrade.oldSortOrder}} -> {{downgrade.newSortOrder}}",
                channelId
        );
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, api_kind, model, effort, request_timeout_seconds, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "primary", 1, 100, "https://primary.example.com/v1", "CHAT_COMPLETIONS", "gpt-primary", "low", 45, "test-key", now
        );
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, api_kind, model, effort, request_timeout_seconds, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "backup", 1, 200, "https://backup.example.com/v1", "CHAT_COMPLETIONS", "gpt-backup", "low", 45, "test-key", now
        );
        Long providerId = jdbcTemplate.queryForObject("SELECT id FROM ai_provider WHERE name = ?", Long.class, "primary");
        AiProviderRecord provider = new AiProviderRecord();
        provider.setId(providerId);
        provider.setName("primary");
        provider.setEnabled(true);
        provider.setSortOrder(100);
        provider.setBaseUrl("https://primary.example.com/v1");
        provider.setApiKind("CHAT_COMPLETIONS");
        provider.setModel("gpt-primary");
        provider.setEffort("low");
        provider.setRequestTimeoutSeconds(45);

        aiProviderDowngradeService.saveConfig(false, 2);
        aiProviderDowngradeService.saveConfig(true, 2);
        aiProviderDowngradeService.recordFailure(provider, "AI_TITLE", 123, new ConnectException("connection refused"));
        aiProviderDowngradeService.recordFailure(provider, "AI_TITLE", 456, new ConnectException("connection refused"));

        waitForNotificationDeliveryCount("AI_PROVIDER_REQUEST_FAILED", 2);
        waitForNotificationDeliveryCount("AI_PROVIDER_AUTO_DOWNGRADED", 1);
        List<String> failureBodies = jdbcTemplate.queryForList(
                "SELECT request_body_snapshot FROM notification_delivery WHERE event_type = 'AI_PROVIDER_REQUEST_FAILED' ORDER BY id ASC",
                String.class
        );
        org.junit.jupiter.api.Assertions.assertEquals(2, failureBodies.size());
        org.junit.jupiter.api.Assertions.assertTrue(failureBodies.get(0).contains("失败 primary AI_TITLE 1/2 triggered=false"));
        org.junit.jupiter.api.Assertions.assertTrue(failureBodies.get(1).contains("失败 primary AI_TITLE 2/2 triggered=true"));
        String downgradedBody = jdbcTemplate.queryForObject(
                "SELECT request_body_snapshot FROM notification_delivery WHERE event_type = 'AI_PROVIDER_AUTO_DOWNGRADED' ORDER BY id ASC LIMIT 1",
                String.class
        );
        org.junit.jupiter.api.Assertions.assertTrue(downgradedBody.contains("降级 primary AI_TITLE 2/2 100 -> 200"));
    }

    @Test
    void previewSupportEndpointReturnsTrueForSupportedUrlWithoutPreparingPreview() throws Exception {
        mockMvc.perform(get("/api/preview/support")
                        .param("url", "https://video.example.com/watch/abc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());

        org.junit.jupiter.api.Assertions.assertEquals(0, testPreviewProvider.canonicalizations.get());
        org.junit.jupiter.api.Assertions.assertEquals(0, testPreviewProvider.resolutions.get());
    }

    @Test
    void previewSupportEndpointReturnsFalseForUnsupportedUrl() throws Exception {
        mockMvc.perform(get("/api/preview/support")
                        .param("url", "https://unsupported.example.com/post/1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.supported").value(false))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void previewSupportEndpointRejectsMissingUrl() throws Exception {
        mockMvc.perform(get("/api/preview/support"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.supported").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_URL"))
                .andExpect(jsonPath("$.message").value("The url parameter is required."));
    }

    @Test
    void previewSupportEndpointRejectsInvalidUrl() throws Exception {
        mockMvc.perform(get("/api/preview/support")
                        .param("url", "notaurl"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.supported").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_URL"));
    }

    @Test
    void previewSupportEndpointRejectsNonHttpUrl() throws Exception {
        mockMvc.perform(get("/api/preview/support")
                        .param("url", "ftp://example.com/file"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.supported").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_URL"))
                .andExpect(jsonPath("$.message").value("Only http and https URLs are supported."));
    }

    @Test
    void previewStylesEndpointReturnsPublicStyleNamesWithoutPrompts() throws Exception {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "VIRAL", "secret viral prompt", now
        );
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "DAILY", "secret daily prompt", now
        );

        mockMvc.perform(get("/api/preview/styles"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.styles.length()").value(3))
                .andExpect(jsonPath("$.styles[0]").value("FREESTYLE"))
                .andExpect(jsonPath("$.styles[1]").value("DAILY"))
                .andExpect(jsonPath("$.styles[2]").value("VIRAL"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void dashboardStatsEndpointAggregatesPreviewEvents() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isFound());

        mockMvc.perform(get("/preview")
                        .param("url", "notaurl")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/stats/dashboard")
                        .param("range", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.createCount.value").value(1))
                .andExpect(jsonPath("$.overview.openCount.value").value(1))
                .andExpect(jsonPath("$.funnel.aiRequestedCount").value(0))
                .andExpect(jsonPath("$.funnel.aiSucceededCount").value(0))
                .andExpect(jsonPath("$.funnel.aiRenderRate").value(0.0))
                .andExpect(jsonPath("$.funnel.aiSuccessRate").value(0.0))
                .andExpect(jsonPath("$.failureBreakdown.invalid").value(1))
                .andExpect(jsonPath("$.topLinks[0].canonicalUrl").value("https://video.example.com/watch/abc"));
    }

    @Test
    void browserRedirectPreloadsMetadataForDashboardTitles() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isFound());

        awaitLinkTitle("Stub title");

        mockMvc.perform(get("/api/stats/dashboard")
                        .param("range", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.openCount.value").value(1))
                .andExpect(jsonPath("$.topLinks[0].title").value("Stub title"))
                .andExpect(jsonPath("$.topLinks[0].canonicalUrl").value("https://video.example.com/watch/abc"));
    }

    @Test
    void dashboardStatsEndpointRejectsInvalidRange() throws Exception {
        mockMvc.perform(get("/api/stats/dashboard")
                        .param("range", "12h"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminSessionLoginAndPurgeDeletesAllStats() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO stats_link (preview_key, provider_id, canonical_url, title, site_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "preview-1", "stub", "https://video.example.com/watch/abc", "Stub title", "Stub site", 1000L, 1000L
        );
        jdbcTemplate.update(
                "INSERT INTO stats_event (occurred_at, event_type, preview_key, provider_id, http_status, cache_hit, duration_ms, client_type, error_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1000L, "PREVIEW_CREATED", "preview-1", "stub", 200, 0, 10, "CRAWLER", null
        );

        mockMvc.perform(get("/api/admin/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.authenticated").value(false));

        Cookie cookie = adminCookie();

        mockMvc.perform(get("/api/admin/session")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));

        mockMvc.perform(post("/api/admin/stats/purge-all")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedEvents").value(1))
                .andExpect(jsonPath("$.deletedLinks").value(1));

        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_event", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_link", Integer.class));
    }

    @Test
    void adminPreviewEventsEndpointListsCreatedLinksAndClearsCache() throws Exception {
        testPreviewProvider.generatedTextCard.set(true);
        testAiTitleClient.generatedTitle.set("\"AI 管理后台标题\"");
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO admin_prompt (style, prompt, updated_at) VALUES (?, ?, ?)",
                "FUN", "UC 风格", now
        );
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1/chat/completions", "test-model", "low", "test-key", now
        );

        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .param("style", "fun")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk());

        String previewKey = jdbcTemplate.queryForObject(
                "SELECT preview_key FROM stats_event WHERE event_type = 'PREVIEW_CREATED' ORDER BY id DESC LIMIT 1",
                String.class
        );
        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", previewKey))
                .andExpect(status().isOk());

        Cookie cookie = adminCookie();
        mockMvc.perform(get("/api/admin/preview-events")
                        .cookie(cookie)
                        .param("page", "1")
                        .param("size", "10")
                        .param("q", "FUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].previewKey").value(previewKey))
                .andExpect(jsonPath("$.items[0].sourceUrl").value("https://video.example.com/watch/abc"))
                .andExpect(jsonPath("$.items[0].canonicalUrl").value("https://video.example.com/watch/abc"))
                .andExpect(jsonPath("$.items[0].metadataTitle").value("AI 管理后台标题"))
                .andExpect(jsonPath("$.items[0].providerId").value("stub"))
                .andExpect(jsonPath("$.items[0].aiRequested").value(true))
                .andExpect(jsonPath("$.items[0].aiSucceeded").value(true))
                .andExpect(jsonPath("$.items[0].requestedStyle").value("FUN"))
                .andExpect(jsonPath("$.items[0].actualStyle").value("FUN"))
                .andExpect(jsonPath("$.items[0].aiProviderNames").value("local"))
                .andExpect(jsonPath("$.items[0].aiDurationMs").value(12))
                .andExpect(jsonPath("$.items[0].metadataCached").value(true))
                .andExpect(jsonPath("$.items[0].thumbnailCached").value(true));

        mockMvc.perform(delete("/api/admin/preview-events/{previewKey}/cache", previewKey)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewKey").value(previewKey))
                .andExpect(jsonPath("$.deletedFiles").value(2));

        mockMvc.perform(get("/api/admin/preview-events")
                        .cookie(cookie)
                        .param("q", "FUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].metadataTitle").value("AI 管理后台标题"))
                .andExpect(jsonPath("$.items[0].metadataCached").value(false))
                .andExpect(jsonPath("$.items[0].thumbnailCached").value(false));

        mockMvc.perform(get("/api/admin/preview-events")
                        .cookie(cookie)
                        .param("q", "AI 管理后台标题"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].previewKey").value(previewKey));
    }

    @Test
    void adminShareSummaryCrudManualRunAndHistoryUseDatabaseTitles() throws Exception {
        Cookie cookie = adminCookie();
        long now = System.currentTimeMillis();
        testAiTitleClient.generatedText.set("分享总结报告");
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, api_kind, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1", "RESPONSES", "test-model", "low", "test-key", now
        );

        mockMvc.perform(post("/api/admin/share-summary/tasks")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" 每日总结 ","enabled":true,"periodType":"DAILY","periodSelectionMode":"CURRENT","runTime":"09:00","prompt":" 总结重点 ","maxLinks":2000,"minLinks":2000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("每日总结"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.periodType").value("DAILY"))
                .andExpect(jsonPath("$.periodSelectionMode").value("CURRENT"))
                .andExpect(jsonPath("$.runTime").value("09:00"))
                .andExpect(jsonPath("$.dayOfWeek").doesNotExist())
                .andExpect(jsonPath("$.dayOfMonth").doesNotExist())
                .andExpect(jsonPath("$.prompt").value("总结重点"))
                .andExpect(jsonPath("$.maxLinks").value(2000))
                .andExpect(jsonPath("$.minLinks").value(2000));

        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_task WHERE name = ?", Long.class, "每日总结");
        mockMvc.perform(put("/api/admin/share-summary/tasks/{taskId}", taskId)
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"周总结","enabled":true,"periodType":"WEEKLY","periodSelectionMode":"PREVIOUS","runTime":"10:30","dayOfWeek":3,"prompt":"按主题聚合","maxLinks":2,"minLinks":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodType").value("WEEKLY"))
                .andExpect(jsonPath("$.periodSelectionMode").value("PREVIOUS"))
                .andExpect(jsonPath("$.dayOfWeek").value(3))
                .andExpect(jsonPath("$.dayOfMonth").doesNotExist());

        mockMvc.perform(put("/api/admin/share-summary/tasks/{taskId}", taskId)
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"周总结","enabled":true,"periodType":"DAILY","runTime":"00:00","prompt":"按主题聚合","maxLinks":2,"minLinks":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodType").value("DAILY"))
                .andExpect(jsonPath("$.runTime").value("00:00"))
                .andExpect(jsonPath("$.dayOfWeek").doesNotExist())
                .andExpect(jsonPath("$.dayOfMonth").doesNotExist());

        mockMvc.perform(put("/api/admin/share-summary/tasks/{taskId}", taskId)
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"周总结","enabled":true,"periodType":"DAILY","runTime":"00:00","prompt":"按主题聚合","maxLinks":2001,"minLinks":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Max links must be between 1 and 2000.")));

        mockMvc.perform(get("/api/admin/share-summary/tasks")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(taskId))
                .andExpect(jsonPath("$[0].name").value("周总结"));

        ExpectedWindow window = currentDailyManualWindow();
        long windowStart = window.start();
        long beforeRun = System.currentTimeMillis();
        insertStatsLink("key-a", "https://example.com/a", "数据库标题 A", windowStart + 1_000L);
        insertStatsLink("key-a-duplicate", "https://example.com/a", "数据库标题 A 晚到", windowStart + 1_500L);
        insertStatsLink("key-b", "https://example.com/b", "数据库标题 B", windowStart + 2_000L);
        insertStatsLink("key-c", "https://example.com/c", "数据库标题 C", windowStart + 3_000L);
        insertStatsLink("key-empty", "https://example.com/empty", "", windowStart + 4_000L);
        insertPreviewCreatedEvent("key-a", "https://source.example.com/a1", windowStart + 1_000L, true, true);
        insertPreviewCreatedEvent("key-a-duplicate", "https://source.example.com/a-duplicate", windowStart + 1_500L, true, true);
        insertPreviewCreatedEvent("key-b", "https://source.example.com/b", windowStart + 2_000L, false, false);
        insertPreviewCreatedEvent("key-a", "https://source.example.com/a2", windowStart + 3_000L, true, true);
        insertPreviewCreatedEvent("key-c", "https://source.example.com/c", windowStart + 4_000L, false, false);
        insertPreviewCreatedEvent("key-empty", "https://source.example.com/empty", windowStart + 5_000L, false, false);
        insertPreviewCreatedEvent("key-a", "https://source.example.com/outside", beforeRun + 60_000L, true, true);

        MvcResult runResult = mockMvc.perform(post("/api/admin/share-summary/tasks/{taskId}/run", taskId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.taskName").value("周总结"))
                .andExpect(jsonPath("$.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.periodType").value("DAILY"))
                .andExpect(jsonPath("$.windowStart").value(windowStart))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.linkCount").value(6))
                .andExpect(jsonPath("$.uniqueLinkCount").value(3))
                .andExpect(jsonPath("$.inputLinkCount").value(2))
                .andExpect(jsonPath("$.promptSnapshot").value("按主题聚合"))
                .andExpect(jsonPath("$.aiProviderNames").value("local"))
                .andExpect(jsonPath("$.aiDurationMs").value(34))
                .andExpect(jsonPath("$.report").value("分享总结报告"))
                .andReturn();
        long afterRun = System.currentTimeMillis();
        Long actualWindowEnd = jdbcTemplate.queryForObject("SELECT window_end FROM share_summary_run WHERE task_id = ?", Long.class, taskId);
        org.junit.jupiter.api.Assertions.assertNotNull(actualWindowEnd);
        org.junit.jupiter.api.Assertions.assertTrue(actualWindowEnd >= beforeRun);
        org.junit.jupiter.api.Assertions.assertTrue(actualWindowEnd <= afterRun);
        org.junit.jupiter.api.Assertions.assertTrue(runResult.getResponse().getContentAsString().contains("\"windowEnd\":" + actualWindowEnd));

        org.junit.jupiter.api.Assertions.assertEquals(1, testAiTitleClient.textRequests.get());
        AiTextPrompt prompt = testAiTitleClient.textPrompt.get();
        org.junit.jupiter.api.Assertions.assertTrue(prompt.prompt().contains("按主题聚合"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.content().contains("链接分享列表"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.content().contains("1.标题：数据库标题 A"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.content().contains("   链接：https://example.com/a"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.content().contains("   分享时间：" + expectedShareTime(windowStart + 1_000L)));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.content().contains("2.标题：数据库标题 B"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.content().contains("   链接：https://example.com/b"));
        org.junit.jupiter.api.Assertions.assertFalse(prompt.content().contains("[2次]"));
        org.junit.jupiter.api.Assertions.assertFalse(prompt.content().contains("数据库标题 A 晚到"));
        org.junit.jupiter.api.Assertions.assertFalse(prompt.content().contains("数据库标题 C"));
        org.junit.jupiter.api.Assertions.assertFalse(prompt.content().contains("source.example.com"));

        mockMvc.perform(get("/api/admin/share-summary/runs")
                        .cookie(cookie)
                        .param("taskId", String.valueOf(taskId))
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].report").value("分享总结报告"));

        Long runId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_run WHERE task_id = ?", Long.class, taskId);
        mockMvc.perform(get("/api/admin/share-summary/runs/{runId}", runId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.report").value("分享总结报告"));

        mockMvc.perform(delete("/api/admin/share-summary/tasks/{taskId}", taskId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        mockMvc.perform(get("/api/admin/share-summary/tasks")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM share_summary_run WHERE task_id = ?", Integer.class, taskId));
    }

    @Test
    void adminShareSummaryManualRunWithNoTitlesIsEmptyAndDoesNotCallAi() throws Exception {
        Cookie cookie = adminCookie();
        mockMvc.perform(post("/api/admin/share-summary/tasks")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"空数据总结","enabled":false,"periodType":"DAILY","runTime":"09:00","prompt":"总结","maxLinks":100}
                                """))
                .andExpect(status().isOk());
        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_task WHERE name = ?", Long.class, "空数据总结");

        mockMvc.perform(post("/api/admin/share-summary/tasks/{taskId}/run", taskId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.linkCount").value(0))
                .andExpect(jsonPath("$.uniqueLinkCount").value(0))
                .andExpect(jsonPath("$.inputLinkCount").value(0))
                .andExpect(jsonPath("$.errorMessage").value("No link titles were found in the summary window."))
                .andExpect(jsonPath("$.report").value(""));

        mockMvc.perform(post("/api/admin/share-summary/tasks/{taskId}/run", taskId)
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"windowStart\":1000,\"windowEnd\":2000}"))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, testAiTitleClient.textRequests.get());
    }

    @Test
    void adminShareSummaryManualRunBelowMinimumLinksRecordsReasonAndSkipsAi() throws Exception {
        Cookie cookie = adminCookie();
        mockMvc.perform(post("/api/admin/share-summary/tasks")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"门槛总结","enabled":false,"periodType":"DAILY","runTime":"09:00","prompt":"总结","maxLinks":100,"minLinks":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minLinks").value(2));
        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_task WHERE name = ?", Long.class, "门槛总结");
        ExpectedWindow window = currentDailyManualWindow();
        insertStatsLink("min-key", "https://example.com/min", "门槛标题", window.start() + 1_000L);
        insertStatsLink("min-key-duplicate", "https://example.com/min", "门槛标题重复", window.start() + 2_000L);
        insertPreviewCreatedEvent("min-key", "https://source.example.com/min", window.start() + 1_000L, true, true);
        insertPreviewCreatedEvent("min-key-duplicate", "https://source.example.com/min-duplicate", window.start() + 2_000L, true, true);

        mockMvc.perform(post("/api/admin/share-summary/tasks/{taskId}/run", taskId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.linkCount").value(2))
                .andExpect(jsonPath("$.uniqueLinkCount").value(1))
                .andExpect(jsonPath("$.inputLinkCount").value(1))
                .andExpect(jsonPath("$.errorMessage").value("Link title count 1 is below the configured minimum 2."));

        mockMvc.perform(get("/api/admin/share-summary/runs")
                        .cookie(cookie)
                        .param("taskId", String.valueOf(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].errorMessage").value("Link title count 1 is below the configured minimum 2."));
        org.junit.jupiter.api.Assertions.assertEquals(0, testAiTitleClient.textRequests.get());
    }

    @Test
    void adminShareSummaryImageConfigGenerationAndPublicOgEndpoints() throws Exception {
        Cookie cookie = adminCookie();
        long now = System.currentTimeMillis();
        testAiTitleClient.generatedText.set("""
                # 分享总结报告正文

                ## 关键洞察

                - 链接分享增长
                - **内容洞察**稳定
                - [图片测试标题](https://example.com/image)
                - 裸链接 https://example.com/plain
                """);
        jdbcTemplate.update(
                "INSERT INTO ai_provider (name, enabled, sort_order, base_url, api_kind, model, effort, api_key, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "local", 1, 1, "https://api.openai.com/v1", "RESPONSES", "test-model", "low", "test-key", now
        );

        mockMvc.perform(get("/api/admin/share-summary/image-config"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/share-summary/audio-config")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("https://tts.wangwangit.com"))
                .andExpect(jsonPath("$.endpointPath").value("/v1/audio/speech"))
                .andExpect(jsonPath("$.model").value(""))
                .andExpect(jsonPath("$.voice").value("zh-CN-YunhaoNeural"))
                .andExpect(jsonPath("$.speed").value(1.2))
                .andExpect(jsonPath("$.pitch").value(0))
                .andExpect(jsonPath("$.style").value("newscast"));

        mockMvc.perform(put("/api/admin/share-summary/image-config")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"autoGenerate":false,"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://api.example.com","endpointPath":"/v1/images/generations","apiKey":"sk-image","model":"image-model","imageSize":"auto","quality":"auto","outputFormat":"png","stylePrompt":"科技感数据报告","requestTimeoutSeconds":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.model").value("image-model"));

        mockMvc.perform(put("/api/admin/share-summary/image-config")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"autoGenerate":false,"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://api.example.com","endpointPath":"/v1/images/generations","apiKey":"","model":"image-model-2","imageSize":"auto","quality":"auto","outputFormat":"png","stylePrompt":"科技感数据报告","requestTimeoutSeconds":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.model").value("image-model-2"));
        org.junit.jupiter.api.Assertions.assertEquals("sk-image", jdbcTemplate.queryForObject("SELECT api_key FROM share_summary_image_config WHERE id = 1", String.class));

        mockMvc.perform(put("/api/admin/share-summary/audio-config")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"autoGenerate":false,"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://tts.wangwangit.com","endpointPath":"/v1/audio/speech","apiKey":"","model":"","voice":"zh-CN-YunhaoNeural","speed":1.2,"pitch":0,"style":"newscast","outputFormat":"mp3","requestTimeoutSeconds":120}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.model").value(""))
                .andExpect(jsonPath("$.pitch").value(0));

        mockMvc.perform(post("/api/admin/share-summary/tasks")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"图片总结","enabled":false,"periodType":"MONTHLY","runTime":"09:00","prompt":"总结","maxLinks":5,"minLinks":1}
                                """))
                .andExpect(status().isOk());
        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_task WHERE name = ?", Long.class, "图片总结");
        ExpectedWindow window = currentMonthlyManualWindow();
        insertStatsLink("image-key", "https://example.com/image", "图片测试标题", window.start() + 1_000L);
        insertPreviewCreatedEvent("image-key", "https://source.example.com/image", window.start() + 1_000L, true, true);

        mockMvc.perform(post("/api/admin/share-summary/tasks/{taskId}/run", taskId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.imageStatus").value("NOT_GENERATED"));
        Long runId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_run WHERE task_id = ?", Long.class, taskId);

        mockMvc.perform(post("/api/admin/share-summary/runs/{runId}/image", runId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(isIn(List.of("PENDING", "GENERATING"))));

        waitForImageSuccess(runId);
        org.junit.jupiter.api.Assertions.assertEquals(1, testShareSummaryImageClient.requests.get());
        org.junit.jupiter.api.Assertions.assertTrue(testShareSummaryImageClient.prompt.get().contains("LinkPeek - "));
        org.junit.jupiter.api.Assertions.assertTrue(testShareSummaryImageClient.prompt.get().contains("科技感数据报告"));

        mockMvc.perform(get("/api/admin/share-summary/runs/{runId}", runId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.audioStatus").value("NOT_GENERATED"))
                .andExpect(jsonPath("$.ogImageUrl").value(containsString("/share-summary/og-images/")))
                .andExpect(jsonPath("$.ogPageUrl").value(containsString("/share-summary/reports/")))
                .andExpect(jsonPath("$.ogTitle").value(containsString("LinkPeek - ")))
                .andExpect(jsonPath("$.ogDescription").value(containsString("链接分享与内容洞察")));

        mockMvc.perform(get("/api/admin/share-summary/runs")
                        .cookie(cookie)
                        .param("taskId", String.valueOf(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].imageStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].ogImageUrl").value(containsString("/share-summary/og-images/")));

        String publicToken = jdbcTemplate.queryForObject("SELECT public_token FROM share_summary_image WHERE run_id = ?", String.class, runId);
        mockMvc.perform(get("/share-summary/og-images/{publicToken}.png", publicToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"));

        mockMvc.perform(get("/share-summary/audios/{publicToken}.mp3", publicToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/admin/share-summary/runs/{runId}/audio", runId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(isIn(List.of("PENDING", "GENERATING"))));

        waitForAudioSuccess(runId);
        org.junit.jupiter.api.Assertions.assertEquals(1, testShareSummaryAudioClient.requests.get());
        org.junit.jupiter.api.Assertions.assertEquals("", testShareSummaryAudioClient.config.get().getModel());
        org.junit.jupiter.api.Assertions.assertEquals(0, testShareSummaryAudioClient.config.get().getPitch());
        org.junit.jupiter.api.Assertions.assertTrue(testShareSummaryAudioClient.input.get().contains("LinkPeek - "));
        org.junit.jupiter.api.Assertions.assertTrue(testShareSummaryAudioClient.input.get().contains("分享总结报告正文"));
        org.junit.jupiter.api.Assertions.assertTrue(testShareSummaryAudioClient.input.get().contains("内容洞察稳定"));
        org.junit.jupiter.api.Assertions.assertTrue(testShareSummaryAudioClient.input.get().contains("图片测试标题"));
        org.junit.jupiter.api.Assertions.assertFalse(testShareSummaryAudioClient.input.get().contains("# 分享总结报告正文"));
        org.junit.jupiter.api.Assertions.assertFalse(testShareSummaryAudioClient.input.get().contains("**内容洞察**"));
        org.junit.jupiter.api.Assertions.assertFalse(testShareSummaryAudioClient.input.get().contains("[图片测试标题](https://example.com/image)"));

        mockMvc.perform(get("/api/admin/share-summary/runs/{runId}", runId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.audioUrl").value(containsString("/share-summary/audios/")));

        mockMvc.perform(get("/share-summary/audios/{publicToken}.mp3", publicToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"));

        mockMvc.perform(get("/share-summary/reports/{publicToken}", publicToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("og:title")))
                .andExpect(content().string(containsString("og:image")))
                .andExpect(content().string(containsString("og:audio")))
                .andExpect(content().string(containsString("og:audio:secure_url")))
                .andExpect(content().string(containsString("og:audio:type")))
                .andExpect(content().string(containsString("audio/mpeg")))
                .andExpect(content().string(containsString("<link rel=\"stylesheet\" href=\"/dashboard/styles.css?v=20260612-mobile-scale\">")))
                .andExpect(content().string(containsString("LinkPeek Share Report")))
                .andExpect(content().string(containsString("class=\"icon-link project-link\"")))
                .andExpect(content().string(containsString("href=\"https://github.com/shigella520/LinkPeek\"")))
                .andExpect(content().string(containsString("aria-label=\"打开 LinkPeek 项目\"")))
                .andExpect(content().string(containsString("data-audio-reader")))
                .andExpect(content().string(containsString("data-audio-element")))
                .andExpect(content().string(containsString("/share-summary/audios/")))
                .andExpect(content().string(containsString("data-audio-status>准备播放</div>")))
                .andExpect(content().string(containsString("setAudioStatus(\"正在播放\", true)")))
                .andExpect(content().string(not(containsString("服务端语音"))))
                .andExpect(content().string(containsString("data-reader")))
                .andExpect(content().string(containsString("speechSynthesis")))
                .andExpect(content().string(containsString("data-reader-voice")))
                .andExpect(content().string(containsString("synth.getVoices()")))
                .andExpect(content().string(containsString("linkpeek.shareSummary.readerVoice")))
                .andExpect(content().string(containsString("<option value=\"1.0\">1.0x</option>")))
                .andExpect(content().string(containsString("<option value=\"1.1\">1.1x</option>")))
                .andExpect(content().string(containsString("<option value=\"1.2\">1.2x</option>")))
                .andExpect(content().string(containsString("<option value=\"1.3\">1.3x</option>")))
                .andExpect(content().string(containsString("<option value=\"1.4\">1.4x</option>")))
                .andExpect(content().string(containsString("<option value=\"1.5\">1.5x</option>")))
                .andExpect(content().string(containsString("<option value=\"1.8\">1.8x</option>")))
                .andExpect(content().string(containsString("<option value=\"2.0\">2.0x</option>")))
                .andExpect(content().string(containsString("function defaultRate()")))
                .andExpect(content().string(containsString("return viewportWidth <= 520 ? 1.1 : 1.4;")))
                .andExpect(content().string(containsString("setRate(defaultRate())")))
                .andExpect(content().string(containsString("createUtterance")))
                .andExpect(content().string(containsString("准备播放")))
                .andExpect(content().string(not(containsString("data-reader-action=\"stop\""))))
                .andExpect(content().string(containsString("<h2>分享总结报告正文</h2>")))
                .andExpect(content().string(containsString("<li>链接分享增长</li>")))
                .andExpect(content().string(containsString("<strong>内容洞察</strong>")))
                .andExpect(content().string(containsString("<a href=\"https://example.com/image\" target=\"_blank\" rel=\"noreferrer\">图片测试标题</a>")))
                .andExpect(content().string(containsString("<a href=\"https://example.com/plain\" target=\"_blank\" rel=\"noreferrer\">https://example.com/plain</a>")));
    }

    @Test
    void notificationAdminApisValidateTemplatesAndDeliverOnShareSummaryImageSuccess() throws Exception {
        Cookie cookie = adminCookie();

        mockMvc.perform(get("/api/admin/notifications/events")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("SHARE_SUMMARY_IMAGE_SUCCESS"))
                .andExpect(jsonPath("$[0].placeholders[?(@.name == 'image.ogPageUrl')]").exists());

        mockMvc.perform(post("/api/admin/notifications/tasks/validate-template")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"SHARE_SUMMARY_IMAGE_SUCCESS","templateJson":"标题：{{image.ogTitle}}\\n链接：{{image.ogShareUrl}}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/admin/notifications/tasks/validate-template")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"SHARE_SUMMARY_IMAGE_SUCCESS","templateJson":"{\\"bad\\":\\"{{run.notExists}}\\"}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.invalidPlaceholders[0]").value("run.notExists"));

        mockMvc.perform(post("/api/admin/notifications/channels")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Example","enabled":true,"url":"http://93.184.216.34/linkpeek","headersJson":{"X-Test":"yes"},"bodyTemplate":"{\\"text\\":\\"{{message.body}}\\"}","secret":"secret","timeoutSeconds":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headersJson.X-Test").value("yes"))
                .andExpect(jsonPath("$.bodyTemplate").value(containsString("message.body")))
                .andExpect(jsonPath("$.secretConfigured").value(true));

        mockMvc.perform(post("/api/admin/notifications/channels")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Invalid","enabled":true,"url":"http://93.184.216.34/linkpeek","headersJson":{},"bodyTemplate":"{\\"title\\":\\"{{image.ogTitle}}\\"}","timeoutSeconds":10}
                                """))
                .andExpect(status().isBadRequest());
        Long channelId = jdbcTemplate.queryForObject("SELECT id FROM notification_channel WHERE name = ?", Long.class, "Example");
        jdbcTemplate.update("UPDATE notification_channel SET url = ? WHERE id = ?", "http://127.0.0.1/linkpeek", channelId);

        mockMvc.perform(post("/api/admin/notifications/tasks")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"分享图通知",
                                  "enabled":true,
                                  "eventType":"SHARE_SUMMARY_IMAGE_SUCCESS",
                                  "filters":{"periodTypes":["MONTHLY"],"triggerTypes":["MANUAL"]},
                                  "templateJson":"{{run.taskName}} 已生成分享图：{{image.ogShareUrl}}，共 {{run.linkCount}} 条链接",
                                  "channelIds":[%d]
                                }
                                """.formatted(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelIds[0]").value(channelId));

        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO share_summary_run (task_id, task_name, trigger_type, period_type, window_start, window_end, status, link_count, unique_link_count, input_link_count, prompt_snapshot, ai_provider_names, ai_duration_ms, report, error_message, started_at, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                44L,
                "月报",
                "MANUAL",
                "MONTHLY",
                now - 86_400_000L,
                now,
                "SUCCESS",
                7,
                6,
                6,
                "prompt",
                "local",
                34L,
                "报告正文",
                null,
                now - 1_000L,
                now
        );
        Long runId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_run WHERE task_id = 44", Long.class);
        jdbcTemplate.update(
                """
                        INSERT INTO share_summary_image (
                            run_id,
                            attempt_no,
                            status,
                            provider_type,
                            model,
                            image_size,
                            output_format,
                            quality,
                            style_prompt_snapshot,
                            prompt_snapshot,
                            storage_key,
                            public_token,
                            image_url,
                            og_image_url,
                            og_page_url,
                            og_title,
                            og_description,
                            raw_response_snapshot,
                            error_message,
                            duration_ms,
                            created_at,
                            started_at,
                            finished_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                runId,
                1,
                "SUCCESS",
                "OPENAI_COMPATIBLE",
                "image-model",
                "auto",
                "png",
                "auto",
                "style",
                "prompt",
                "share-summary/images/1/1.png",
                "token",
                "https://preview.example.com/share-summary/og-images/token.png",
                "https://preview.example.com/share-summary/og-images/token.png",
                "https://preview.example.com/share-summary/reports/token",
                "LinkPeek - 2026年5月月报",
                "本报告汇总了链接分享与内容洞察。",
                "{}",
                null,
                23L,
                now,
                now,
                now
        );
        Long imageId = jdbcTemplate.queryForObject("SELECT id FROM share_summary_image WHERE run_id = ?", Long.class, runId);
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setId(runId);
        run.setTaskId(44L);
        run.setTaskName("月报");
        run.setTriggerType("MANUAL");
        run.setPeriodType("MONTHLY");
        run.setWindowStart(now - 86_400_000L);
        run.setWindowEnd(now);
        run.setStatus("SUCCESS");
        run.setLinkCount(7);
        run.setUniqueLinkCount(6);
        run.setInputLinkCount(6);
        run.setAiProviderNames("local");
        run.setAiDurationMs(34L);
        run.setReport("报告正文");
        run.setStartedAt(now - 1_000L);
        run.setFinishedAt(now);

        ShareSummaryImageRecord image = new ShareSummaryImageRecord();
        image.setId(imageId);
        image.setRunId(runId);
        image.setAttemptNo(1);
        image.setStatus("SUCCESS");
        image.setProviderType("OPENAI_COMPATIBLE");
        image.setModel("image-model");
        image.setImageSize("auto");
        image.setOutputFormat("png");
        image.setQuality("auto");
        image.setImageUrl("https://preview.example.com/share-summary/og-images/token.png");
        image.setOgImageUrl("https://preview.example.com/share-summary/og-images/token.png");
        image.setOgPageUrl("https://preview.example.com/share-summary/reports/token");
        image.setOgTitle("LinkPeek - 2026年5月月报");
        image.setOgDescription("本报告汇总了链接分享与内容洞察。");
        image.setDurationMs(23L);
        image.setCreatedAt(now);
        image.setStartedAt(now);
        image.setFinishedAt(now);

        notificationService.publishShareSummaryImageSuccess(run, image);
        waitForNotificationDelivery(imageId);

        mockMvc.perform(get("/api/admin/notifications/deliveries")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("SHARE_SUMMARY_IMAGE_SUCCESS"))
                .andExpect(jsonPath("$.items[0].eventKey").value("SHARE_SUMMARY_IMAGE_SUCCESS:" + imageId))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].requestBodySnapshot").value(containsString("https://preview.example.com/share-summary/reports/token")))
                .andExpect(jsonPath("$.items[0].requestBodySnapshot").value(containsString("月报 已生成分享图")))
                .andExpect(jsonPath("$.items[0].requestBodySnapshot").value(containsString("共 7 条链接")));
    }

    @Test
    void adminEndpointsRejectUnauthenticatedRequestsAndInvalidLogin() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO stats_link (preview_key, provider_id, canonical_url, title, site_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "preview-1", "stub", "https://video.example.com/watch/abc", "Stub title", "Stub site", 1000L, 1000L
        );
        jdbcTemplate.update(
                "INSERT INTO stats_event (occurred_at, event_type, preview_key, provider_id, http_status, cache_hit, duration_ms, client_type, error_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1000L, "PREVIEW_CREATED", "preview-1", "stub", 200, 0, 10, "CRAWLER", null
        );

        mockMvc.perform(post("/api/admin/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/stats/purge-all"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/ai-providers/1/test"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/logs"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/ai-title-config"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/ai-provider-downgrade-config"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/preview-events"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/share-summary/tasks"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/share-summary/runs"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/notifications/events"))
                .andExpect(status().isUnauthorized());

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_event", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_link", Integer.class));
    }

    @Test
    void adminLogsEndpointReadsConfiguredServiceLog() throws Exception {
        Cookie cookie = adminCookie();
        Files.writeString(
                TEST_SERVICE_LOG,
                """
                        2026-04-30T14:00:00 INFO application started
                        2026-04-30T14:01:00 WARN previewKey=abc cache miss
                        2026-04-30T14:02:00 ERROR upstream failed
                        """,
                StandardCharsets.UTF_8
        );

        mockMvc.perform(get("/api/admin/logs")
                        .cookie(cookie)
                        .param("lines", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(TEST_SERVICE_LOG.toAbsolutePath().normalize().toString()))
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.sizeBytes").isNumber())
                .andExpect(jsonPath("$.modifiedAt").isNumber())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0]").value("2026-04-30T14:01:00 WARN previewKey=abc cache miss"))
                .andExpect(jsonPath("$.lines[1]").value("2026-04-30T14:02:00 ERROR upstream failed"));

        mockMvc.perform(get("/api/admin/logs")
                        .cookie(cookie)
                        .param("level", "warn")
                        .param("q", "PREVIEWKEY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0]").value("2026-04-30T14:01:00 WARN previewKey=abc cache miss"));

        mockMvc.perform(get("/api/admin/logs")
                        .cookie(cookie)
                        .param("level", "NOTICE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminPromptProviderConfigAndAiProviderCrudUseAuthenticatedSession() throws Exception {
        Cookie cookie = adminCookie();

        mockMvc.perform(put("/api/admin/prompts/fun")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"UC 风格\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style").value("FUN"))
                .andExpect(jsonPath("$.prompt").value("UC 风格"));

        mockMvc.perform(get("/api/admin/prompts")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].style").value("FUN"));

        mockMvc.perform(put("/api/admin/prompts/freestyle")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"保留 key\"}"))
                .andExpect(status().isBadRequest());

        jdbcTemplate.update(
                "INSERT INTO admin_prompt(style, prompt, updated_at) VALUES (?, ?, ?)",
                "FREESTYLE",
                "保留 key",
                System.currentTimeMillis()
        );
        mockMvc.perform(delete("/api/admin/prompts/freestyle")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        mockMvc.perform(get("/api/admin/ai-title-config")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleFormatPrompt").value(AiTitleConfigService.DEFAULT_TITLE_FORMAT_PROMPT))
                .andExpect(jsonPath("$.defaultTitleFormatPrompt").value(AiTitleConfigService.DEFAULT_TITLE_FORMAT_PROMPT));

        mockMvc.perform(put("/api/admin/ai-title-config")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"titleFormatPrompt\":\"以此为标准，生成一段大于15中文字符，小于30个中文字符，客观，辩证的标题。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleFormatPrompt").value("以此为标准，生成一段大于15中文字符，小于30个中文字符，客观，辩证的标题。"));

        mockMvc.perform(get("/api/admin/ai-provider-downgrade-config")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoDowngradeEnabled").value(false))
                .andExpect(jsonPath("$.autoDowngradeFailureThreshold").value(3))
                .andExpect(jsonPath("$.defaultAutoDowngradeFailureThreshold").value(3));

        mockMvc.perform(put("/api/admin/ai-provider-downgrade-config")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"autoDowngradeEnabled\":true,\"autoDowngradeFailureThreshold\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoDowngradeEnabled").value(true))
                .andExpect(jsonPath("$.autoDowngradeFailureThreshold").value(2));

        mockMvc.perform(put("/api/admin/provider-config/linuxdo")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"_t\":\"token\",\"cf_clearance\":\"clear\",\"_forum_session\":\"session\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs.linuxdo._t").value("token"));
        org.junit.jupiter.api.Assertions.assertEquals("_t=token; cf_clearance=clear; _forum_session=session", providerConfigService.linuxDoCookieHeader());

        mockMvc.perform(put("/api/admin/provider-config/nga")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"NGA_PASSPORT_UID\":\"uid\",\"NGA_PASSPORT_CID\":\"cid\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs.nga.NGA_PASSPORT_UID").value("uid"));
        org.junit.jupiter.api.Assertions.assertEquals("uid", providerConfigService.ngaPassportUid());
        org.junit.jupiter.api.Assertions.assertEquals("cid", providerConfigService.ngaPassportCid());

        mockMvc.perform(post("/api/admin/ai-providers")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Invalid","enabled":true,"sortOrder":10,"baseUrl":"https://www.packyapi.com/v2","apiKind":"CHAT_COMPLETIONS","model":"gpt-test","effort":"","apiKey":"plain-key"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/ai-providers")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"OpenAI","enabled":true,"sortOrder":10,"baseUrl":"https://api.openai.com/v1","apiKind":"RESPONSES","model":"gpt-test","effort":"low","requestTimeoutSeconds":90,"apiKey":"plain-key"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.baseUrl").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.apiKind").value("RESPONSES"))
                .andExpect(jsonPath("$.requestTimeoutSeconds").value(90))
                .andExpect(jsonPath("$.apiKey").value("plain-key"));

        mockMvc.perform(get("/api/admin/ai-providers")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apiKind").value("RESPONSES"))
                .andExpect(jsonPath("$[0].requestTimeoutSeconds").value(90))
                .andExpect(jsonPath("$[0].apiKey").value("plain-key"));

        Long providerId = jdbcTemplate.queryForObject("SELECT id FROM ai_provider WHERE name = ?", Long.class, "OpenAI");
        mockMvc.perform(put("/api/admin/ai-providers/{id}", providerId)
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"OpenAI Updated","baseUrl":"https://api.openai.com/v1","apiKind":"RESPONSES","model":"gpt-test-updated","effort":"medium","requestTimeoutSeconds":120,"apiKey":"updated-key"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("OpenAI Updated"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.sortOrder").value(10))
                .andExpect(jsonPath("$.requestTimeoutSeconds").value(120));

        mockMvc.perform(put("/api/admin/ai-providers/{id}/enabled", providerId)
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.requestTimeoutSeconds").value(120));

        mockMvc.perform(post("/api/admin/ai-providers")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Backup","baseUrl":"https://backup.example.com/v1","apiKind":"CHAT_COMPLETIONS","model":"gpt-backup","effort":"","requestTimeoutSeconds":30,"apiKey":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.requestTimeoutSeconds").value(30));

        Long backupProviderId = jdbcTemplate.queryForObject("SELECT id FROM ai_provider WHERE name = ?", Long.class, "Backup");
        mockMvc.perform(put("/api/admin/ai-providers/reorder")
                        .cookie(cookie)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + backupProviderId + "," + providerId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(backupProviderId))
                .andExpect(jsonPath("$[0].sortOrder").value(100))
                .andExpect(jsonPath("$[1].id").value(providerId))
                .andExpect(jsonPath("$[1].sortOrder").value(200));

        mockMvc.perform(post("/api/admin/ai-providers/{id}/test", providerId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.output").value("AI title"));

        testAiTitleClient.generatedTitle.set(null);
        mockMvc.perform(post("/api/admin/ai-providers/{id}/test", providerId)
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("AI 服务返回空内容。"));

        mockMvc.perform(delete("/api/admin/prompts/fun")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
    }

    @Test
    void thumbnailEndpointDownloadsAndCachesThumbnail() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", key().value()))
                .andExpect(status().isOk())
                .andExpect(content().bytes("thumb-data".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", key().value()))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(1, testPreviewProvider.thumbnailDownloads.get());
    }

    @Test
    void thumbnailEndpointReturnsNotFoundWhenMetadataIsMissing() throws Exception {
        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", key().value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void thumbnailEndpointReturnsBadGatewayWhenProviderFails() throws Exception {
        mockMvc.perform(get("/preview")
                        .param("url", "https://video.example.com/watch/abc")
                        .header(HttpHeaders.USER_AGENT, "facebookexternalhit/1.1"))
                .andExpect(status().isOk());

        testPreviewProvider.thumbnailFails.set(true);

        mockMvc.perform(get("/media/thumb/{previewKey}.jpg", key().value()))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(containsString("Thumbnail failed")));
    }

    @Test
    void videoEndpointReturnsNotImplemented() throws Exception {
        mockMvc.perform(get("/media/video/{previewKey}.mp4", key().value()))
                .andExpect(status().isNotImplemented());
    }

    private static PreviewKey key() {
        return PreviewKey.fromCanonicalUrl("https://video.example.com/watch/abc");
    }

    private static String ogImageUrl(String html) {
        String marker = "property=\"og:image\" content=\"";
        int start = html.indexOf(marker);
        org.junit.jupiter.api.Assertions.assertTrue(start >= 0, "Expected og:image meta tag.");
        start += marker.length();
        int end = html.indexOf('"', start);
        org.junit.jupiter.api.Assertions.assertTrue(end > start, "Expected og:image content value.");
        return html.substring(start, end);
    }

    private static String imageVersion(String imageUrl) {
        int versionIndex = imageUrl.indexOf("?v=");
        org.junit.jupiter.api.Assertions.assertTrue(versionIndex >= 0, "Expected image URL version query.");
        return imageUrl.substring(versionIndex + 3);
    }

    private Cookie adminCookie() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/admin/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"test-admin-password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();
        return login.getResponse().getCookie("LINKPEEK_ADMIN_SESSION");
    }

    private void insertStatsLink(String previewKey, String canonicalUrl, String title, long seenAt) {
        jdbcTemplate.update(
                "INSERT INTO stats_link (preview_key, provider_id, canonical_url, title, site_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                previewKey, "stub", canonicalUrl, title, "Example", seenAt, seenAt
        );
    }

    private void insertPreviewCreatedEvent(
            String previewKey,
            String sourceUrl,
            long occurredAt,
            boolean aiRequested,
            boolean aiSucceeded
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO stats_event (
                            occurred_at,
                            event_type,
                            preview_key,
                            provider_id,
                            http_status,
                            cache_hit,
                            ai_requested,
                            ai_succeeded,
                            source_url,
                            requested_style,
                            actual_style,
                            ai_provider_names,
                            ai_duration_ms,
                            crawl_duration_ms,
                            duration_ms,
                            client_type,
                            error_code
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                occurredAt,
                "PREVIEW_CREATED",
                previewKey,
                "stub",
                200,
                0,
                aiRequested ? 1 : 0,
                aiSucceeded ? 1 : 0,
                sourceUrl,
                "FUN",
                "FUN",
                aiRequested ? "local" : null,
                aiRequested ? 12 : 0,
                7,
                10,
                "CRAWLER",
                null
        );
    }

    private void awaitLinkTitle(String expectedTitle) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<String> titles = jdbcTemplate.queryForList(
                    "SELECT title FROM stats_link WHERE preview_key = ?",
                    String.class,
                    key().value()
            );
            if (!titles.isEmpty() && expectedTitle.equals(titles.get(0))) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("Expected async warmup to store title: " + expectedTitle);
    }

    private void waitForImageSuccess(long runId) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<String> statuses = jdbcTemplate.queryForList(
                    "SELECT status FROM share_summary_image WHERE run_id = ? ORDER BY id DESC",
                    String.class,
                    runId
            );
            if (!statuses.isEmpty() && "SUCCESS".equals(statuses.get(0))) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("Expected share summary image generation to succeed.");
    }

    private void waitForAudioSuccess(long runId) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<String> statuses = jdbcTemplate.queryForList(
                    "SELECT status FROM share_summary_audio WHERE run_id = ? ORDER BY id DESC",
                    String.class,
                    runId
            );
            if (!statuses.isEmpty() && "SUCCESS".equals(statuses.get(0))) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("Expected share summary audio generation to succeed.");
    }

    private void waitForNotificationDelivery(long imageId) throws InterruptedException {
        String eventKey = "SHARE_SUMMARY_IMAGE_SUCCESS:" + imageId;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_delivery WHERE event_key = ? AND status != 'PENDING'",
                    Integer.class,
                    eventKey
            );
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("Expected notification delivery to finish.");
    }

    private void waitForNotificationDeliveryByEventType(String eventType) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_delivery WHERE event_type = ?",
                    Integer.class,
                    eventType
            );
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("Expected notification delivery for event type: " + eventType);
    }

    private void waitForNotificationDeliveryCount(String eventType, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_delivery WHERE event_type = ?",
                    Integer.class,
                    eventType
            );
            if (count != null && count >= expectedCount) {
                return;
            }
            Thread.sleep(25);
        }
        org.junit.jupiter.api.Assertions.fail("Expected " + expectedCount + " notification deliveries for event type: " + eventType);
    }

    private void waitForNotificationQueueToDrain() {
        CompletableFuture<Void> drained = new CompletableFuture<>();
        notificationWebhookExecutor.execute(() -> drained.complete(null));
        drained.orTimeout(3, TimeUnit.SECONDS).join();
    }

    private long insertLoopbackNotificationChannel(String name) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                """
                        INSERT INTO notification_channel (
                            name,
                            enabled,
                            type,
                            method,
                            url,
                            headers_json,
                            body_template,
                            secret,
                            timeout_seconds,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                name,
                1,
                "WEBHOOK",
                "POST",
                "http://127.0.0.1/linkpeek",
                null,
                "{{message.body}}",
                "",
                1,
                now,
                now
        );
        return jdbcTemplate.queryForObject("SELECT id FROM notification_channel WHERE name = ?", Long.class, name);
    }

    private void insertNotificationTask(String name, String eventType, String templateJson, long channelId) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                """
                        INSERT INTO notification_task (
                            name,
                            enabled,
                            event_type,
                            filters_json,
                            template_json,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                name,
                1,
                eventType,
                "{\"shareSummaryTaskIds\":[],\"periodTypes\":[],\"triggerTypes\":[]}",
                templateJson,
                now,
                now
        );
        Long taskId = jdbcTemplate.queryForObject("SELECT id FROM notification_task WHERE name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO notification_task_channel (task_id, channel_id) VALUES (?, ?)", taskId, channelId);
    }

    private static ExpectedWindow currentDailyManualWindow() {
        ZoneId zone = ZoneId.systemDefault();
        return new ExpectedWindow(
                LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli(),
                System.currentTimeMillis()
        );
    }

    private static String expectedShareTime(long millis) {
        return Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static ExpectedWindow currentMonthlyManualWindow() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        return new ExpectedWindow(
                now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                now.toInstant().toEpochMilli()
        );
    }

    private record ExpectedWindow(long start, long end) {
    }

    private static void writeTestWebIcon() throws IOException {
        Files.writeString(
                TEST_WEB_ICON,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><circle cx=\"16\" cy=\"16\" r=\"16\" fill=\"#0a84ff\"/></svg>"
        );
    }

    @TestConfiguration
    static class TestProviderConfiguration {
        @Bean
        TestPreviewProvider testPreviewProvider() {
            return new TestPreviewProvider();
        }

        @Bean
        @Primary
        TestAiTitleClient testAiTitleClient() {
            return new TestAiTitleClient();
        }

        @Bean
        @Primary
        TestShareSummaryImageClient testShareSummaryImageClient() {
            return new TestShareSummaryImageClient();
        }

        @Bean
        @Primary
        TestShareSummaryAudioClient testShareSummaryAudioClient() {
            return new TestShareSummaryAudioClient();
        }
    }

    static final class TestAiTitleClient extends AiTitleClient {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger textRequests = new AtomicInteger();
        private final AtomicReference<AiTitlePrompt> prompt = new AtomicReference<>(new AiTitlePrompt("", "", ""));
        private final AtomicReference<AiTextPrompt> textPrompt = new AtomicReference<>(new AiTextPrompt("", "", ""));
        private final AtomicReference<String> generatedTitle = new AtomicReference<>("AI title");
        private final AtomicReference<String> generatedText = new AtomicReference<>("AI summary");
        private final AtomicReference<CountDownLatch> blockedRequestStarted = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> blockedRequestRelease = new AtomicReference<>();

        TestAiTitleClient() {
            super(null, null);
        }

        @Override
        public Optional<String> generateTitle(AiProviderRecord provider, AiTitlePrompt prompt) {
            requests.incrementAndGet();
            this.prompt.set(prompt);
            return Optional.ofNullable(generatedTitle.get());
        }

        @Override
        public AiTitleResult generateTitleResult(AiProviderRecord provider, AiTitlePrompt prompt) {
            requests.incrementAndGet();
            this.prompt.set(prompt);
            CountDownLatch started = blockedRequestStarted.get();
            CountDownLatch release = blockedRequestRelease.get();
            if (started != null && release != null) {
                started.countDown();
                try {
                    org.junit.jupiter.api.Assertions.assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                } finally {
                    blockedRequestStarted.set(null);
                    blockedRequestRelease.set(null);
                }
            }
            return new AiTitleResult(Optional.ofNullable(generatedTitle.get()), 12);
        }

        @Override
        public AiTextResult generateTextResult(AiProviderRecord provider, AiTextPrompt prompt) {
            textRequests.incrementAndGet();
            this.textPrompt.set(prompt);
            return new AiTextResult(Optional.ofNullable(generatedText.get()), 34);
        }

        void blockNextRequest() {
            blockedRequestStarted.set(new CountDownLatch(1));
            blockedRequestRelease.set(new CountDownLatch(1));
        }

        boolean awaitBlockedRequest() throws InterruptedException {
            CountDownLatch started = blockedRequestStarted.get();
            return started != null && started.await(2, TimeUnit.SECONDS);
        }

        void releaseBlockedRequest() {
            CountDownLatch release = blockedRequestRelease.get();
            if (release != null) {
                release.countDown();
            }
        }

        void reset() {
            requests.set(0);
            textRequests.set(0);
            prompt.set(new AiTitlePrompt("", "", ""));
            textPrompt.set(new AiTextPrompt("", "", ""));
            generatedTitle.set("AI title");
            generatedText.set("AI summary");
            releaseBlockedRequest();
            blockedRequestStarted.set(null);
            blockedRequestRelease.set(null);
        }
    }

    static final class TestPreviewProvider implements PreviewProvider {
        private final AtomicInteger thumbnailDownloads = new AtomicInteger();
        private final AtomicInteger canonicalizations = new AtomicInteger();
        private final AtomicInteger resolutions = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean generatedTextCard = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean resolveFails = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean thumbnailFails = new java.util.concurrent.atomic.AtomicBoolean();

        void reset() {
            thumbnailDownloads.set(0);
            canonicalizations.set(0);
            resolutions.set(0);
            generatedTextCard.set(false);
            resolveFails.set(false);
            thumbnailFails.set(false);
        }

        @Override
        public String getId() {
            return "stub";
        }

        @Override
        public boolean supports(URI sourceUrl) {
            return "video.example.com".equals(sourceUrl.getHost());
        }

        @Override
        public URI canonicalize(URI sourceUrl) {
            canonicalizations.incrementAndGet();
            return URI.create("https://video.example.com/watch/abc");
        }

        @Override
        public PreviewMetadata resolve(URI sourceUrl) {
            resolutions.incrementAndGet();
            if (resolveFails.get()) {
                throw new UpstreamFetchException("Upstream crawl failed");
            }
            boolean generated = generatedTextCard.get();
            return new PreviewMetadata(
                    sourceUrl.toString(),
                    canonicalize(sourceUrl).toString(),
                    getId(),
                    "Stub title",
                    "Stub description",
                    "Stub site",
                    generated ? "generated://stub/title-card/abc" : "https://img.example/thumb.jpg",
                    1200,
                    630,
                    generated ? ContentType.ARTICLE : ContentType.VIDEO,
                    generated ? "原始帖子正文，包含需要被 AI 总结的信息。" : ""
            );
        }

        @Override
        public Path downloadThumbnail(PreviewMetadata metadata, Path targetPath) throws IOException {
            thumbnailDownloads.incrementAndGet();
            if (thumbnailFails.get()) {
                throw new UpstreamFetchException("Thumbnail failed");
            }
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, "thumb-data");
            return targetPath;
        }
    }

    static final class TestShareSummaryImageClient extends ShareSummaryImageClient {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> prompt = new AtomicReference<>("");
        private final AtomicReference<String> base64 = new AtomicReference<>(testPngBase64());

        TestShareSummaryImageClient() {
            super(null, null);
        }

        @Override
        public ImageGenerationResult generate(io.github.shigella520.linkpeek.server.admin.model.ShareSummaryImageConfigRecord config, String prompt) {
            requests.incrementAndGet();
            this.prompt.set(prompt);
            return new ImageGenerationResult(base64.get(), null, "{\"test\":true}", 23);
        }

        void reset() {
            requests.set(0);
            prompt.set("");
            base64.set(testPngBase64());
        }
    }

    static final class TestShareSummaryAudioClient extends ShareSummaryAudioClient {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord> config = new AtomicReference<>();
        private final AtomicReference<String> input = new AtomicReference<>("");
        private final AtomicReference<byte[]> bytes = new AtomicReference<>(testMp3Bytes());

        TestShareSummaryAudioClient() {
            super(null, null);
        }

        @Override
        public AudioGenerationResult generate(io.github.shigella520.linkpeek.server.admin.model.ShareSummaryAudioConfigRecord config, String input) {
            requests.incrementAndGet();
            this.config.set(config);
            this.input.set(input);
            return new AudioGenerationResult(bytes.get(), "{\"test\":true}", 19);
        }

        void reset() {
            requests.set(0);
            config.set(null);
            input.set("");
            bytes.set(testMp3Bytes());
        }
    }

    private static byte[] testMp3Bytes() {
        return new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 0};
    }

    private static String testPngBase64() {
        return "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR4XmP4z8DwHwAFAAH/e+m+7wAAAABJRU5ErkJggg==";
    }
}
