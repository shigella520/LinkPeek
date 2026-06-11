package io.github.shigella520.linkpeek.server.admin.model;

import java.util.Locale;

public enum ShareSummaryPeriodSelectionMode {
    CURRENT,
    PREVIOUS;

    public static ShareSummaryPeriodSelectionMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return CURRENT;
        }
        try {
            return ShareSummaryPeriodSelectionMode.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Period selection mode must be CURRENT or PREVIOUS.", exception);
        }
    }
}
