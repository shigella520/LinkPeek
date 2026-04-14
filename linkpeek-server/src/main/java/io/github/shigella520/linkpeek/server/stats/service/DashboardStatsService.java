package io.github.shigella520.linkpeek.server.stats.service;

import io.github.shigella520.linkpeek.server.stats.model.DashboardRange;
import io.github.shigella520.linkpeek.server.stats.model.DashboardStatsResponse;
import io.github.shigella520.linkpeek.server.stats.model.StatisticsErrorCode;
import io.github.shigella520.linkpeek.server.stats.persistence.DashboardStatsMapper;
import io.github.shigella520.linkpeek.server.stats.persistence.row.DailyEventCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.FailureCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.HeatmapCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.NewReturningCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.TopLinkRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.WindowSummaryRow;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardStatsService {
    private static final int TOP_LINK_LIMIT = 10;

    private final DashboardStatsMapper dashboardStatsMapper;
    private final Clock clock;

    public DashboardStatsService(DashboardStatsMapper dashboardStatsMapper, Clock clock) {
        this.dashboardStatsMapper = dashboardStatsMapper;
        this.clock = clock;
    }

    public DashboardStatsResponse getDashboardStats(DashboardRange range) {
        ZoneId zoneId = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate startDate = today.minusDays(range.days() - 1L);
        LocalDate endDateExclusive = today.plusDays(1);
        LocalDate previousStartDate = startDate.minusDays(range.days());

        long currentStart = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli();
        long currentEnd = endDateExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli();
        long previousStart = previousStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli();
        long previousEnd = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli();

        WindowSummaryRow currentSummary = dashboardStatsMapper.selectWindowSummary(currentStart, currentEnd);
        WindowSummaryRow previousSummary = dashboardStatsMapper.selectWindowSummary(previousStart, previousEnd);

        long currentNewLinks = dashboardStatsMapper.countNewLinks(currentStart, currentEnd);
        long previousNewLinks = dashboardStatsMapper.countNewLinks(previousStart, previousEnd);

        return new DashboardStatsResponse(
                Instant.now(clock),
                zoneId.getId(),
                range.value(),
                new DashboardStatsResponse.Overview(
                        metricCard(currentSummary.getCreateCount(), previousSummary.getCreateCount()),
                        metricCard(currentSummary.getOpenCount(), previousSummary.getOpenCount()),
                        metricCard(currentSummary.getUniqueLinkCount(), previousSummary.getUniqueLinkCount()),
                        metricCard(currentNewLinks, previousNewLinks)
                ),
                buildGrowthTrend(range, startDate, currentStart, currentEnd),
                buildFunnel(currentSummary),
                buildFailureBreakdown(currentStart, currentEnd),
                buildTopLinks(currentStart, currentEnd),
                buildHeatmap(currentStart, currentEnd),
                buildNewVsReturningTrend(range, startDate, currentStart, currentEnd)
        );
    }

    private DashboardStatsResponse.MetricCard metricCard(long current, long previous) {
        return new DashboardStatsResponse.MetricCard(current, previous, changeRate(current, previous));
    }

    private List<DashboardStatsResponse.TrendPoint> buildGrowthTrend(
            DashboardRange range,
            LocalDate startDate,
            long currentStart,
            long currentEnd
    ) {
        Map<String, DailyTrendAccumulator> rows = new HashMap<>();
        for (DailyEventCountRow row : dashboardStatsMapper.selectGrowthTrend(currentStart, currentEnd)) {
            DailyTrendAccumulator accumulator = rows.computeIfAbsent(row.getDayBucket(), ignored -> new DailyTrendAccumulator());
            if ("PREVIEW_CREATED".equals(row.getEventType())) {
                accumulator.createdCount = row.getTotalCount();
            }
            if ("PREVIEW_OPENED".equals(row.getEventType())) {
                accumulator.openedCount = row.getTotalCount();
            }
        }

        List<DashboardStatsResponse.TrendPoint> trend = new ArrayList<>(range.days());
        for (int dayOffset = 0; dayOffset < range.days(); dayOffset++) {
            LocalDate date = startDate.plusDays(dayOffset);
            DailyTrendAccumulator accumulator = rows.getOrDefault(date.toString(), new DailyTrendAccumulator());
            trend.add(new DashboardStatsResponse.TrendPoint(
                    date.toString(),
                    accumulator.createdCount,
                    accumulator.openedCount
            ));
        }
        return trend;
    }

    private DashboardStatsResponse.Funnel buildFunnel(WindowSummaryRow summary) {
        long total = summary.getAllPreviewRequests();
        long createCount = summary.getCreateCount();
        long openCount = summary.getOpenCount();
        long failedCount = summary.getFailedCount();
        return new DashboardStatsResponse.Funnel(
                total,
                createCount,
                openCount,
                failedCount,
                ratio(createCount + openCount, total),
                ratio(createCount, total),
                nullableRatio(openCount, createCount)
        );
    }

    private DashboardStatsResponse.FailureBreakdown buildFailureBreakdown(long currentStart, long currentEnd) {
        Map<StatisticsErrorCode, Long> counts = new EnumMap<>(StatisticsErrorCode.class);
        for (FailureCountRow row : dashboardStatsMapper.selectFailureBreakdown(currentStart, currentEnd)) {
            StatisticsErrorCode errorCode = parseErrorCode(row.getErrorCode());
            counts.put(errorCode, row.getTotalCount());
        }

        long invalid = counts.getOrDefault(StatisticsErrorCode.INVALID_URL, 0L);
        long unsupported = counts.getOrDefault(StatisticsErrorCode.UNSUPPORTED_URL, 0L);
        long upstream = counts.getOrDefault(StatisticsErrorCode.UPSTREAM_ERROR, 0L);
        long other = counts.getOrDefault(StatisticsErrorCode.OTHER, 0L);

        return new DashboardStatsResponse.FailureBreakdown(
                invalid,
                unsupported,
                upstream,
                other,
                invalid + unsupported + upstream + other
        );
    }

    private List<DashboardStatsResponse.TopLink> buildTopLinks(long currentStart, long currentEnd) {
        return dashboardStatsMapper.selectTopLinks(currentStart, currentEnd, TOP_LINK_LIMIT).stream()
                .map(row -> new DashboardStatsResponse.TopLink(
                        row.getPreviewKey(),
                        row.getTitle() == null || row.getTitle().isBlank() ? row.getCanonicalUrl() : row.getTitle(),
                        row.getCanonicalUrl(),
                        row.getProviderId(),
                        row.getCreatedCount(),
                        row.getOpenedCount(),
                        Instant.ofEpochMilli(row.getFirstSeenAt()),
                        Instant.ofEpochMilli(row.getLastSeenAt())
                ))
                .toList();
    }

    private List<DashboardStatsResponse.HeatmapCell> buildHeatmap(long currentStart, long currentEnd) {
        Map<String, Long> rawCounts = new HashMap<>();
        for (HeatmapCountRow row : dashboardStatsMapper.selectHeatmap(currentStart, currentEnd)) {
            rawCounts.put(key(row.getWeekday(), row.getHour()), row.getTotalCount());
        }

        List<DashboardStatsResponse.HeatmapCell> cells = new ArrayList<>(7 * 24);
        for (int weekday = 0; weekday < 7; weekday++) {
            for (int hour = 0; hour < 24; hour++) {
                cells.add(new DashboardStatsResponse.HeatmapCell(
                        weekday,
                        hour,
                        rawCounts.getOrDefault(key(weekday, hour), 0L)
                ));
            }
        }
        return cells;
    }

    private List<DashboardStatsResponse.NewVsReturningPoint> buildNewVsReturningTrend(
            DashboardRange range,
            LocalDate startDate,
            long currentStart,
            long currentEnd
    ) {
        Map<String, NewReturningCountRow> rows = new HashMap<>();
        for (NewReturningCountRow row : dashboardStatsMapper.selectNewVsReturningTrend(currentStart, currentEnd)) {
            rows.put(row.getDayBucket(), row);
        }

        List<DashboardStatsResponse.NewVsReturningPoint> trend = new ArrayList<>(range.days());
        for (int dayOffset = 0; dayOffset < range.days(); dayOffset++) {
            LocalDate date = startDate.plusDays(dayOffset);
            NewReturningCountRow row = rows.get(date.toString());
            trend.add(new DashboardStatsResponse.NewVsReturningPoint(
                    date.toString(),
                    row == null ? 0L : row.getNewLinkCount(),
                    row == null ? 0L : row.getReturningLinkCount()
            ));
        }
        return trend;
    }

    private StatisticsErrorCode parseErrorCode(String rawErrorCode) {
        if (rawErrorCode == null || rawErrorCode.isBlank()) {
            return StatisticsErrorCode.OTHER;
        }
        try {
            return StatisticsErrorCode.valueOf(rawErrorCode);
        } catch (IllegalArgumentException ignored) {
            return StatisticsErrorCode.OTHER;
        }
    }

    private String key(int weekday, int hour) {
        return weekday + ":" + hour;
    }

    private Double changeRate(long current, long previous) {
        if (previous == 0) {
            return current == 0 ? 0.0 : null;
        }
        return ((double) (current - previous) / previous) * 100.0;
    }

    private double ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }

    private Double nullableRatio(long numerator, long denominator) {
        if (denominator == 0) {
            return numerator == 0 ? 0.0 : null;
        }
        return (double) numerator / denominator;
    }

    private static final class DailyTrendAccumulator {
        private long createdCount;
        private long openedCount;
    }
}
