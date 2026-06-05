package io.github.shigella520.linkpeek.server.admin.model;

import java.util.Locale;

public enum ShareSummaryImageProviderType {
    OPENAI_COMPATIBLE;

    public static ShareSummaryImageProviderType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        return ShareSummaryImageProviderType.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
