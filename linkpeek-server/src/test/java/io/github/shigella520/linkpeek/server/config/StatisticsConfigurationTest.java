package io.github.shigella520.linkpeek.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsConfigurationTest {
    @Test
    void schemaInitializerAddsMissingAiStatsColumnsIdempotently() throws IOException {
        Path tempDir = Files.createTempDirectory("linkpeek-schema-test");
        try {
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("stats.db"));
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.execute("""
                    CREATE TABLE stats_event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        occurred_at INTEGER NOT NULL,
                        event_type TEXT NOT NULL,
                        preview_key TEXT,
                        provider_id TEXT,
                        http_status INTEGER NOT NULL,
                        cache_hit INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        client_type TEXT NOT NULL,
                        error_code TEXT
                    )
                    """);

            initializeSchema(dataSource);
            initializeSchema(dataSource);

            assertTrue(hasColumn(jdbcTemplate, "stats_event", "ai_requested"));
            assertTrue(hasColumn(jdbcTemplate, "stats_event", "ai_succeeded"));
            assertTrue(hasColumn(jdbcTemplate, "stats_event", "source_url"));
            assertTrue(hasColumn(jdbcTemplate, "stats_event", "requested_style"));
            assertTrue(hasColumn(jdbcTemplate, "stats_event", "actual_style"));
            assertTrue(hasColumn(jdbcTemplate, "stats_event", "ai_provider_names"));
            assertTrue(hasColumn(jdbcTemplate, "stats_event", "ai_duration_ms"));
            assertTrue(hasColumn(jdbcTemplate, "stats_event", "crawl_duration_ms"));
            assertTrue(hasColumn(jdbcTemplate, "ai_provider", "request_timeout_seconds"));
            assertTrue(hasColumn(jdbcTemplate, "share_summary_task", "min_links"));
            jdbcTemplate.update("""
                    INSERT INTO stats_event (
                        occurred_at,
                        event_type,
                        http_status,
                        cache_hit,
                        duration_ms,
                        client_type
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, 1L, "PREVIEW_CREATED", 200, 0, 10L, "CRAWLER");
            assertEquals(
                    1,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM stats_event WHERE ai_requested = 0 AND ai_succeeded = 0",
                            Integer.class
                    )
            );
            assertEquals(
                    1,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM stats_event WHERE ai_duration_ms = 0 AND crawl_duration_ms = 0",
                            Integer.class
                    )
            );
            jdbcTemplate.update("""
                    INSERT INTO ai_provider (
                        name,
                        enabled,
                        sort_order,
                        base_url,
                        api_kind,
                        model,
                        api_key,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "OpenAI", 1, 10, "https://api.openai.com/v1", "RESPONSES", "gpt-test", "sk-test", 1L);
            assertEquals(
                    1,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM ai_provider WHERE request_timeout_seconds = 45",
                            Integer.class
                    )
            );
        } finally {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
    }

    @Test
    void schemaInitializerDropsOldShareSummaryDataWhenRemovingDayOfMonth() throws IOException {
        Path tempDir = Files.createTempDirectory("linkpeek-share-summary-schema-test");
        try {
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("stats.db"));
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.execute("""
                    CREATE TABLE share_summary_task (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        period_type TEXT NOT NULL,
                        run_time TEXT NOT NULL,
                        day_of_week INTEGER,
                        day_of_month INTEGER,
                        prompt TEXT NOT NULL,
                        max_links INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE share_summary_run (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id INTEGER NOT NULL,
                        task_name TEXT NOT NULL,
                        trigger_type TEXT NOT NULL,
                        period_type TEXT NOT NULL,
                        window_start INTEGER NOT NULL,
                        window_end INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        prompt_snapshot TEXT NOT NULL,
                        started_at INTEGER NOT NULL
                    )
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE share_summary_image (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id INTEGER NOT NULL,
                        status TEXT NOT NULL
                    )
                    """);
            jdbcTemplate.update("""
                    INSERT INTO share_summary_task (
                        name,
                        enabled,
                        period_type,
                        run_time,
                        day_of_month,
                        prompt,
                        max_links,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "旧月报", 1, "MONTHLY", "09:00", 15, "总结", 100, 1L, 1L);

            initializeSchema(dataSource);
            initializeSchema(dataSource);

            org.junit.jupiter.api.Assertions.assertFalse(hasColumn(jdbcTemplate, "share_summary_task", "day_of_month"));
            assertTrue(hasColumn(jdbcTemplate, "share_summary_task", "deleted"));
            assertTrue(hasColumn(jdbcTemplate, "share_summary_task", "min_links"));
            assertTrue(hasColumn(jdbcTemplate, "share_summary_run", "link_count"));
            assertTrue(hasColumn(jdbcTemplate, "share_summary_image", "public_token"));
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM share_summary_task", Integer.class));
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM share_summary_run", Integer.class));
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM share_summary_image", Integer.class));
        } finally {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
    }

    private void initializeSchema(SQLiteDataSource dataSource) {
        new StatisticsConfiguration.StatisticsSchemaInitializer(
                dataSource,
                new ResourceDatabasePopulator(new ClassPathResource("db/stats-schema.sql"))
        );
    }

    private boolean hasColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        return jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")")
                .stream()
                .map(row -> String.valueOf(row.get("name")).toLowerCase(Locale.ROOT))
                .anyMatch(columnName::equals);
    }
}
