package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShareSummaryReportTitlesTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final ShareSummaryReportTitles titles = new ShareSummaryReportTitles(Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZONE));

    @Test
    void titlesFollowPeriodTypeInClockZone() {
        assertEquals("LinkPeek - 2026-11-25 日报", titles.title(run("DAILY", "2026-11-25", "2026-11-26")));
        assertEquals("LinkPeek - 2026年11月月报", titles.title(run("MONTHLY", "2026-11-01", "2026-12-01")));
        assertEquals("LinkPeek - 2026年第05周周报", titles.title(run("WEEKLY", "2026-01-26", "2026-02-02")));
        assertEquals("LinkPeek - 2026年第53周周报", titles.title(run("WEEKLY", "2026-12-28", "2027-01-04")));
    }

    @Test
    void descriptionShowsWindowStartAndEndDates() {
        assertEquals("本报告汇总了 2026-11-01 至 2026-12-01 的链接分享与内容洞察。",
                titles.description(run("MONTHLY", "2026-11-01", "2026-12-01")));
    }

    private static ShareSummaryRunRecord run(String periodType, String start, String end) {
        ShareSummaryRunRecord run = new ShareSummaryRunRecord();
        run.setPeriodType(periodType);
        run.setWindowStart(LocalDate.parse(start).atStartOfDay(ZONE).toInstant().toEpochMilli());
        run.setWindowEnd(LocalDate.parse(end).atStartOfDay(ZONE).toInstant().toEpochMilli());
        return run;
    }
}
