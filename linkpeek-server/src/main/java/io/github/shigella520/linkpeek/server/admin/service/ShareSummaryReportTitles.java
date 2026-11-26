package io.github.shigella520.linkpeek.server.admin.service;

import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryPeriodType;
import io.github.shigella520.linkpeek.server.admin.model.ShareSummaryRunRecord;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;

/** Default share title, description and date labels for a share summary run, in the clock's zone. */
final class ShareSummaryReportTitles {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Clock clock;

    ShareSummaryReportTitles(Clock clock) {
        this.clock = clock;
    }

    String title(ShareSummaryRunRecord run) {
        ShareSummaryPeriodType periodType = ShareSummaryPeriodType.fromValue(run.getPeriodType());
        LocalDate start = millisToDate(run.getWindowStart());
        return switch (periodType) {
            case DAILY -> "LinkPeek - " + start.format(DATE_FORMATTER) + " 日报";
            case WEEKLY -> weeklyTitle(start);
            case MONTHLY -> "LinkPeek - " + start.getYear() + "年" + start.getMonthValue() + "月月报";
        };
    }

    String description(ShareSummaryRunRecord run) {
        return "本报告汇总了 %s 至 %s 的链接分享与内容洞察。".formatted(
                dateLabel(run.getWindowStart()),
                dateLabel(run.getWindowEnd())
        );
    }

    String dateLabel(long millis) {
        return millisToDate(millis).format(DATE_FORMATTER);
    }

    private String weeklyTitle(LocalDate start) {
        WeekFields weekFields = WeekFields.ISO;
        int week = start.get(weekFields.weekOfWeekBasedYear());
        int year = start.get(weekFields.weekBasedYear());
        return "LinkPeek - %d年第%02d周周报".formatted(year, week);
    }

    private LocalDate millisToDate(long millis) {
        return Instant.ofEpochMilli(millis).atZone(clock.getZone()).toLocalDate();
    }
}
