package io.github.shigella520.linkpeek.server.admin.model;

import java.util.Locale;

public enum ShareSummaryPeriodType {
    DAILY,
    WEEKLY,
    MONTHLY;

    public static ShareSummaryPeriodType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Period type is required.");
        }
        try {
            return ShareSummaryPeriodType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Period type must be DAILY, WEEKLY, or MONTHLY.", exception);
        }
    }
}
