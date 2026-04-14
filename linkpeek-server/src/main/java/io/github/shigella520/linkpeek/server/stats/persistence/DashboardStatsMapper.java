package io.github.shigella520.linkpeek.server.stats.persistence;

import io.github.shigella520.linkpeek.server.stats.persistence.row.DailyEventCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.FailureCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.HeatmapCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.NewReturningCountRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.TopLinkRow;
import io.github.shigella520.linkpeek.server.stats.persistence.row.WindowSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DashboardStatsMapper {
    WindowSummaryRow selectWindowSummary(
            @Param("start") long start,
            @Param("end") long end
    );

    long countNewLinks(
            @Param("start") long start,
            @Param("end") long end
    );

    List<DailyEventCountRow> selectGrowthTrend(
            @Param("start") long start,
            @Param("end") long end
    );

    List<FailureCountRow> selectFailureBreakdown(
            @Param("start") long start,
            @Param("end") long end
    );

    List<TopLinkRow> selectTopLinks(
            @Param("start") long start,
            @Param("end") long end,
            @Param("limit") int limit
    );

    List<HeatmapCountRow> selectHeatmap(
            @Param("start") long start,
            @Param("end") long end
    );

    List<NewReturningCountRow> selectNewVsReturningTrend(
            @Param("start") long start,
            @Param("end") long end
    );
}
