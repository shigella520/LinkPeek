package io.github.shigella520.linkpeek.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

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
        }
    }
}
