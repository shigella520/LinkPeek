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
            ensureColumn(jdbcTemplate, "share_summary_task", "deleted", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(jdbcTemplate, "share_summary_task", "deleted_at", "INTEGER");
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
        }

        private void ensureTable(JdbcTemplate jdbcTemplate, String tableName, String createSql) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    Integer.class,
                    tableName
            );
            if (count == null || count == 0) {
                jdbcTemplate.execute(createSql);
            }
        }

        private void ensureColumn(
                JdbcTemplate jdbcTemplate,
                String tableName,
                String columnName,
                String columnDefinition
        ) {
            boolean exists = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")")
                    .stream()
                    .map(row -> String.valueOf(row.get("name")).toLowerCase(Locale.ROOT))
                    .anyMatch(columnName::equals);
            if (!exists) {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
            }
        }
    }
}
