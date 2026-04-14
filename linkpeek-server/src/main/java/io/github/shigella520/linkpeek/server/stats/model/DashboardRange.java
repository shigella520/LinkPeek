package io.github.shigella520.linkpeek.server.stats.model;

import java.util.Arrays;

public enum DashboardRange {
    DAYS_7("7d", 7),
    DAYS_30("30d", 30),
    DAYS_90("90d", 90),
    DAYS_180("180d", 180);

    private final String value;
    private final int days;

    DashboardRange(String value, int days) {
        this.value = value;
        this.days = days;
    }

    public String value() {
        return value;
    }

    public int days() {
        return days;
    }

    public static DashboardRange fromValue(String value) {
        return Arrays.stream(values())
                .filter(range -> range.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported range: " + value));
    }
}
