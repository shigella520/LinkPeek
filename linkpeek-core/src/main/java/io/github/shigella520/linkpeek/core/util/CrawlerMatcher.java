package io.github.shigella520.linkpeek.core.util;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class CrawlerMatcher {
    private final List<String> signatures;

    public CrawlerMatcher(List<String> signatures) {
        this.signatures = signatures.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
    }

    public boolean matches(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }

        String normalized = userAgent.toLowerCase(Locale.ROOT);
        return signatures.stream().anyMatch(normalized::contains);
    }
}
