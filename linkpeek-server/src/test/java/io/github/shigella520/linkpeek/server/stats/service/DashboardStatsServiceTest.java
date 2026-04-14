package io.github.shigella520.linkpeek.server.stats.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.stats.model.DashboardRange;
import io.github.shigella520.linkpeek.server.stats.model.DashboardStatsResponse;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsClientType;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsErrorCode;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsEventRecord;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsEventType;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsLinkRecord;
import io.github.shigella520.linkpeek.server.stats.persistence.StatsEventMapper;
import io.github.shigella520.linkpeek.server.stats.persistence.StatsLinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DashboardStatsServiceTest {
    private static final Path TEST_CACHE_DIR;
    private static final Path TEST_STATS_DIR;
    private static final Path TEST_STATS_DB;

    static {
        try {
            TEST_CACHE_DIR = Files.createTempDirectory("linkpeek-stats-cache");
            TEST_STATS_DIR = Files.createTempDirectory("linkpeek-stats-db");
            TEST_STATS_DB = TEST_STATS_DIR.resolve("stats-test.db");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("linkpeek.cache-dir", () -> TEST_CACHE_DIR.toString());
        registry.add("linkpeek.stats-db-path", () -> TEST_STATS_DB.toString());
    }

    @Autowired
    private DashboardStatsService dashboardStatsService;

    @Autowired
    private StatisticsMaintenanceService statisticsMaintenanceService;

    @Autowired
    private StatsEventMapper statsEventMapper;

    @Autowired
    private StatsLinkMapper statsLinkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkPeekProperties properties;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM stats_event");
        jdbcTemplate.execute("DELETE FROM stats_link");
        properties.setStatsRetentionDays(180);
    }

    @Test
    void dashboardStatsAggregatesCurrentWindow() {
        long now = Instant.now().toEpochMilli();
        upsertLink("preview-1", "bilibili", "https://www.bilibili.com/video/BV1", "测试视频", "Bilibili", now);
        upsertLink("preview-2", "bilibili", "https://www.bilibili.com/video/BV2", "另一个视频", "Bilibili", now);

        insertEvent(now, "preview-1", "bilibili", StatisticsEventType.PREVIEW_CREATED, StatisticsClientType.CRAWLER, 200, false, 15, null);
        insertEvent(now, "preview-1", "bilibili", StatisticsEventType.PREVIEW_OPENED, StatisticsClientType.BROWSER, 302, false, 8, null);
        insertEvent(now, null, null, StatisticsEventType.PREVIEW_FAILED, StatisticsClientType.CRAWLER, 400, false, 2, StatisticsErrorCode.INVALID_URL);

        DashboardStatsResponse response = dashboardStatsService.getDashboardStats(DashboardRange.DAYS_30);

        assertEquals(1, response.overview().createCount().value());
        assertEquals(1, response.overview().openCount().value());
        assertEquals(2, response.overview().newLinkCount().value());
        assertEquals(1, response.failureBreakdown().invalid());
        assertFalse(response.topLinks().isEmpty());
        assertEquals("https://www.bilibili.com/video/BV1", response.topLinks().get(0).canonicalUrl());
        assertTrue(response.activityHeatmap().stream().anyMatch(cell -> cell.count() > 0));
    }

    @Test
    void purgeExpiredDataRemovesOldEventsAndOrphanLinks() {
        long recent = Instant.now().toEpochMilli();
        long old = Instant.now().minus(45, ChronoUnit.DAYS).toEpochMilli();
        properties.setStatsRetentionDays(30);

        upsertLink("recent-key", "bilibili", "https://www.bilibili.com/video/BV3", "近期视频", "Bilibili", recent);
        upsertLink("old-key", "bilibili", "https://www.bilibili.com/video/BV4", "过期视频", "Bilibili", old);

        insertEvent(recent, "recent-key", "bilibili", StatisticsEventType.PREVIEW_CREATED, StatisticsClientType.CRAWLER, 200, false, 10, null);
        insertEvent(old, "old-key", "bilibili", StatisticsEventType.PREVIEW_CREATED, StatisticsClientType.CRAWLER, 200, false, 10, null);

        StatisticsMaintenanceService.PurgeResult result = statisticsMaintenanceService.purgeExpiredData();

        assertEquals(1, result.deletedEvents());
        assertEquals(1, result.deletedLinks());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_event", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_link", Integer.class));
    }

    private void upsertLink(
            String previewKey,
            String providerId,
            String canonicalUrl,
            String title,
            String siteName,
            long occurredAt
    ) {
        StatisticsLinkRecord record = new StatisticsLinkRecord();
        record.setPreviewKey(previewKey);
        record.setProviderId(providerId);
        record.setCanonicalUrl(canonicalUrl);
        record.setTitle(title);
        record.setSiteName(siteName);
        record.setFirstSeenAt(occurredAt);
        record.setLastSeenAt(occurredAt);
        statsLinkMapper.upsertLink(record);
    }

    private void insertEvent(
            long occurredAt,
            String previewKey,
            String providerId,
            StatisticsEventType eventType,
            StatisticsClientType clientType,
            int httpStatus,
            boolean cacheHit,
            long durationMs,
            StatisticsErrorCode errorCode
    ) {
        StatisticsEventRecord record = new StatisticsEventRecord();
        record.setOccurredAt(occurredAt);
        record.setPreviewKey(previewKey);
        record.setProviderId(providerId);
        record.setEventType(eventType.name());
        record.setClientType(clientType.name());
        record.setHttpStatus(httpStatus);
        record.setCacheHit(cacheHit);
        record.setDurationMs(durationMs);
        record.setErrorCode(errorCode == null ? null : errorCode.name());
        statsEventMapper.insertEvent(record);
    }
}
