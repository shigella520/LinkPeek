package io.github.shigella520.linkpeek.server.stats.service;

import io.github.shigella520.linkpeek.server.config.LinkPeekProperties;
import io.github.shigella520.linkpeek.server.stats.persistence.StatsEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class StatisticsMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(StatisticsMaintenanceService.class);

    private final StatsEventMapper statsEventMapper;
    private final LinkPeekProperties properties;
    private final Clock clock;

    public StatisticsMaintenanceService(
            StatsEventMapper statsEventMapper,
            LinkPeekProperties properties,
            Clock clock
    ) {
        this.statsEventMapper = statsEventMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void scheduledPurgeExpiredData() {
        purgeExpiredData();
    }

    public PurgeResult purgeExpiredData() {
        if (properties.getStatsRetentionDays() <= 0) {
            return new PurgeResult(0, 0);
        }

        long threshold = Instant.now(clock)
                .minus(properties.getStatsRetentionDays(), ChronoUnit.DAYS)
                .toEpochMilli();

        int deletedEvents = statsEventMapper.deleteEventsOlderThan(threshold);
        int deletedLinks = statsEventMapper.deleteOrphanLinks();
        log.info(
                "statistics_cleanup completed deletedEvents={} deletedLinks={} retentionDays={}",
                deletedEvents,
                deletedLinks,
                properties.getStatsRetentionDays()
        );
        return new PurgeResult(deletedEvents, deletedLinks);
    }

    public record PurgeResult(int deletedEvents, int deletedLinks) {
    }
}
