package io.github.shigella520.linkpeek.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;

@Configuration
public class StatisticsConfiguration {
    @Bean
    public DataSource dataSource(LinkPeekProperties properties) throws IOException {
        Path dbPath = properties.getStatsDbPath().toAbsolutePath().normalize();
        Path parent = dbPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5_000);
        config.enforceForeignKeys(false);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public ResourceDatabasePopulator statisticsSchemaPopulator() {
        return new ResourceDatabasePopulator(new ClassPathResource("db/stats-schema.sql"));
    }

    @Bean
    public StatisticsSchemaInitializer statisticsSchemaInitializer(
            DataSource dataSource,
            ResourceDatabasePopulator statisticsSchemaPopulator
    ) {
        return new StatisticsSchemaInitializer(dataSource, statisticsSchemaPopulator);
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    public static final class StatisticsSchemaInitializer {
        public StatisticsSchemaInitializer(
                DataSource dataSource,
                ResourceDatabasePopulator statisticsSchemaPopulator
        ) {
            rebuildShareSummaryTablesWithoutDayOfMonth(new JdbcTemplate(dataSource));
            statisticsSchemaPopulator.execute(dataSource);
            applyIdempotentMigrations(dataSource);
        }

        private void applyIdempotentMigrations(DataSource dataSource) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            ensureColumn(jdbcTemplate, "stats_event", "ai_requested", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(jdbcTemplate, "stats_event", "ai_succeeded", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(jdbcTemplate, "stats_event", "source_url", "TEXT");
            ensureColumn(jdbcTemplate, "stats_event", "requested_style", "TEXT");
            ensureColumn(jdbcTemplate, "stats_event", "actual_style", "TEXT");
            ensureColumn(jdbcTemplate, "stats_event", "ai_provider_names", "TEXT");
            ensureColumn(jdbcTemplate, "stats_event", "ai_duration_ms", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(jdbcTemplate, "stats_event", "crawl_duration_ms", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(jdbcTemplate, "ai_provider", "request_timeout_seconds", "INTEGER NOT NULL DEFAULT 45");
            deleteLegacyAiProviderTimeoutDowngradeConfig(jdbcTemplate);
            rebuildShareSummaryTablesWithoutDayOfMonth(jdbcTemplate);
            ensureColumn(jdbcTemplate, "share_summary_task", "deleted", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(jdbcTemplate, "share_summary_task", "deleted_at", "INTEGER");
            ensureColumn(jdbcTemplate, "share_summary_task", "min_links", "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(jdbcTemplate, "share_summary_task", "period_selection_mode", "TEXT NOT NULL DEFAULT 'CURRENT'");
            ensureTable(jdbcTemplate, "share_summary_image_config", """
                    CREATE TABLE IF NOT EXISTS share_summary_image_config (
                        id INTEGER PRIMARY KEY,
                        enabled INTEGER NOT NULL DEFAULT 0,
                        auto_generate INTEGER NOT NULL DEFAULT 0,
                        provider_type TEXT NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
                        base_url TEXT NOT NULL DEFAULT '',
                        endpoint_path TEXT NOT NULL DEFAULT '/v1/images/generations',
                        api_key TEXT NOT NULL DEFAULT '',
                        model TEXT NOT NULL DEFAULT '',
                        image_size TEXT NOT NULL DEFAULT 'auto',
                        quality TEXT NOT NULL DEFAULT 'auto',
                        output_format TEXT NOT NULL DEFAULT 'png',
                        style_prompt TEXT NOT NULL DEFAULT '',
                        request_timeout_seconds INTEGER NOT NULL DEFAULT 300,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            ensureTable(jdbcTemplate, "share_summary_image", """
                    CREATE TABLE IF NOT EXISTS share_summary_image (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id INTEGER NOT NULL,
                        attempt_no INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        provider_type TEXT NOT NULL,
                        model TEXT NOT NULL,
                        image_size TEXT NOT NULL,
                        output_format TEXT NOT NULL,
                        quality TEXT,
                        style_prompt_snapshot TEXT,
                        prompt_snapshot TEXT NOT NULL,
                        storage_key TEXT,
                        public_token TEXT NOT NULL,
                        image_url TEXT,
                        og_image_url TEXT,
                        og_page_url TEXT,
                        og_title TEXT NOT NULL,
                        og_description TEXT NOT NULL,
                        raw_response_snapshot TEXT,
                        error_message TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        started_at INTEGER,
                        finished_at INTEGER
                    )
                    """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_image_run_id ON share_summary_image (run_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_image_status ON share_summary_image (status)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_image_created_at ON share_summary_image (created_at)");
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_share_summary_image_public_token ON share_summary_image (public_token)");
            ensureColumn(jdbcTemplate, "share_summary_image_config", "endpoint_path", "TEXT NOT NULL DEFAULT '/v1/images/generations'");
            ensureColumn(jdbcTemplate, "share_summary_image_config", "quality", "TEXT NOT NULL DEFAULT 'auto'");
            ensureColumn(jdbcTemplate, "share_summary_image", "quality", "TEXT");
            ensureTable(jdbcTemplate, "share_summary_audio_config", """
                    CREATE TABLE IF NOT EXISTS share_summary_audio_config (
                        id INTEGER PRIMARY KEY,
                        enabled INTEGER NOT NULL DEFAULT 0,
                        auto_generate INTEGER NOT NULL DEFAULT 0,
                        provider_type TEXT NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
                        base_url TEXT NOT NULL DEFAULT 'https://tts.wangwangit.com',
                        endpoint_path TEXT NOT NULL DEFAULT '/v1/audio/speech',
                        api_key TEXT NOT NULL DEFAULT '',
                        model TEXT NOT NULL DEFAULT '',
                        voice TEXT NOT NULL DEFAULT 'zh-CN-YunhaoNeural',
                        speed REAL NOT NULL DEFAULT 1.2,
                        pitch INTEGER NOT NULL DEFAULT 0,
                        style TEXT NOT NULL DEFAULT 'newscast',
                        output_format TEXT NOT NULL DEFAULT 'mp3',
                        request_timeout_seconds INTEGER NOT NULL DEFAULT 120,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            ensureTable(jdbcTemplate, "share_summary_audio", """
                    CREATE TABLE IF NOT EXISTS share_summary_audio (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id INTEGER NOT NULL,
                        attempt_no INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        provider_type TEXT NOT NULL,
                        model TEXT,
                        voice TEXT NOT NULL,
                        speed REAL NOT NULL,
                        pitch INTEGER NOT NULL,
                        style TEXT NOT NULL,
                        output_format TEXT NOT NULL,
                        text_snapshot TEXT NOT NULL,
                        storage_key TEXT,
                        audio_url TEXT,
                        play_count INTEGER NOT NULL DEFAULT 0,
                        raw_response_snapshot TEXT,
                        error_message TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        started_at INTEGER,
                        finished_at INTEGER
                    )
                    """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_audio_run_id ON share_summary_audio (run_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_audio_status ON share_summary_audio (status)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_audio_created_at ON share_summary_audio (created_at)");
            ensureColumn(jdbcTemplate, "share_summary_audio", "play_count", "INTEGER NOT NULL DEFAULT 0");
            ensureNotificationTables(jdbcTemplate);
        }

        private void ensureNotificationTables(JdbcTemplate jdbcTemplate) {
            ensureTable(jdbcTemplate, "notification_channel", """
                    CREATE TABLE IF NOT EXISTS notification_channel (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        url TEXT NOT NULL,
                        method TEXT NOT NULL,
                        headers_json TEXT,
                        body_template TEXT NOT NULL DEFAULT '{{message.bodyJson}}',
                        secret TEXT,
                        timeout_seconds INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            ensureColumn(jdbcTemplate, "notification_channel", "body_template", "TEXT NOT NULL DEFAULT '{{message.bodyJson}}'");
            ensureTable(jdbcTemplate, "notification_task", """
                    CREATE TABLE IF NOT EXISTS notification_task (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        event_type TEXT NOT NULL,
                        filters_json TEXT,
                        template_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            ensureTable(jdbcTemplate, "notification_task_channel", """
                    CREATE TABLE IF NOT EXISTS notification_task_channel (
                        task_id INTEGER NOT NULL,
                        channel_id INTEGER NOT NULL,
                        PRIMARY KEY (task_id, channel_id)
                    )
                    """);
            ensureTable(jdbcTemplate, "notification_delivery", """
                    CREATE TABLE IF NOT EXISTS notification_delivery (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_type TEXT NOT NULL,
                        event_key TEXT NOT NULL,
                        notification_task_id INTEGER NOT NULL,
                        notification_task_name TEXT NOT NULL,
                        channel_id INTEGER NOT NULL,
                        channel_name TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        request_url TEXT NOT NULL,
                        request_body TEXT,
                        request_body_snapshot TEXT,
                        response_status INTEGER,
                        response_body_snapshot TEXT,
                        error_message TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        finished_at INTEGER
                    )
                    """);
            ensureColumn(jdbcTemplate, "notification_delivery", "request_body", "TEXT");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_channel_enabled ON notification_channel (enabled, type)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_task_event_enabled ON notification_task (event_type, enabled)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_delivery_event_key ON notification_delivery (event_key)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_delivery_status_created ON notification_delivery (status, created_at)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_delivery_task_created ON notification_delivery (notification_task_id, created_at)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_notification_delivery_channel_created ON notification_delivery (channel_id, created_at)");
        }

        private void ensureTable(JdbcTemplate jdbcTemplate, String tableName, String createSql) {
            if (!tableExists(jdbcTemplate, tableName)) {
                jdbcTemplate.execute(createSql);
            }
        }

        private void deleteLegacyAiProviderTimeoutDowngradeConfig(JdbcTemplate jdbcTemplate) {
            jdbcTemplate.update("""
                    DELETE FROM provider_config
                    WHERE provider_id = 'ai_provider'
                      AND config_key = 'auto_downgrade_timeout_threshold'
                    """);
        }

        private void rebuildShareSummaryTablesWithoutDayOfMonth(JdbcTemplate jdbcTemplate) {
            if (!tableExists(jdbcTemplate, "share_summary_task")
                    || !columnExists(jdbcTemplate, "share_summary_task", "day_of_month")) {
                return;
            }
            jdbcTemplate.execute("DROP TABLE IF EXISTS share_summary_image");
            jdbcTemplate.execute("DROP TABLE IF EXISTS share_summary_run");
            jdbcTemplate.execute("DROP TABLE IF EXISTS share_summary_task");
            jdbcTemplate.execute("""
                    CREATE TABLE share_summary_task (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        period_type TEXT NOT NULL,
                        period_selection_mode TEXT NOT NULL DEFAULT 'CURRENT',
                        run_time TEXT NOT NULL,
                        day_of_week INTEGER,
                        prompt TEXT NOT NULL,
                        max_links INTEGER NOT NULL,
                        min_links INTEGER NOT NULL DEFAULT 1,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        deleted_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_task_enabled ON share_summary_task (enabled, deleted, period_type)");
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
                        link_count INTEGER NOT NULL DEFAULT 0,
                        unique_link_count INTEGER NOT NULL DEFAULT 0,
                        input_link_count INTEGER NOT NULL DEFAULT 0,
                        prompt_snapshot TEXT NOT NULL,
                        ai_provider_names TEXT,
                        ai_duration_ms INTEGER NOT NULL DEFAULT 0,
                        report TEXT,
                        error_message TEXT,
                        started_at INTEGER NOT NULL,
                        finished_at INTEGER
                    )
                    """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_run_task_window ON share_summary_run (task_id, window_start, window_end)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_run_started_at ON share_summary_run (started_at)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_summary_run_status ON share_summary_run (status)");
        }

        private void ensureColumn(
                JdbcTemplate jdbcTemplate,
                String tableName,
                String columnName,
                String columnDefinition
        ) {
            if (!columnExists(jdbcTemplate, tableName, columnName)) {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
            }
        }

        private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    Integer.class,
                    tableName
            );
            return count != null && count > 0;
        }

        private boolean columnExists(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
            return jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")")
                    .stream()
                    .map(row -> String.valueOf(row.get("name")).toLowerCase(Locale.ROOT))
                    .anyMatch(columnName::equals);
        }
    }
}
