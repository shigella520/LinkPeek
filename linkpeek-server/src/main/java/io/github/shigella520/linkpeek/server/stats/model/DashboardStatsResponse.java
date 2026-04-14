package io.github.shigella520.linkpeek.server.stats.model;

import java.time.Instant;
import java.util.List;

public record DashboardStatsResponse(
        Instant generatedAt,
        String timezone,
        String range,
        Overview overview,
        List<TrendPoint> growthTrend,
        Funnel funnel,
        FailureBreakdown failureBreakdown,
        List<TopLink> topLinks,
        List<HeatmapCell> activityHeatmap,
        List<NewVsReturningPoint> newVsReturningTrend
) {
    public record Overview(
            MetricCard createCount,
            MetricCard openCount,
            MetricCard uniqueLinkCount,
            MetricCard newLinkCount
    ) {
    }

    public record MetricCard(
            long value,
            long previousValue,
            Double changeRate
    ) {
    }

    public record TrendPoint(
            String date,
            long createdCount,
            long openedCount
    ) {
    }

    public record Funnel(
            long allPreviewRequests,
            long createCount,
            long openCount,
            long failedCount,
            double previewSuccessRate,
            double createRate,
            Double openCreateRatio
    ) {
    }

    public record FailureBreakdown(
            long invalid,
            long unsupported,
            long upstream,
            long other,
            long total
    ) {
    }

    public record TopLink(
            String previewKey,
            String title,
            String canonicalUrl,
            String providerId,
            long createdCount,
            long openedCount,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {
    }

    public record HeatmapCell(
            int weekday,
            int hour,
            long count
    ) {
    }

    public record NewVsReturningPoint(
            String date,
            long newLinkCount,
            long returningLinkCount
    ) {
    }
}
