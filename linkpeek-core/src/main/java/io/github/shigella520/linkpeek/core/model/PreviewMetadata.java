package io.github.shigella520.linkpeek.core.model;

import java.util.Objects;

public record PreviewMetadata(
        String sourceUrl,
        String canonicalUrl,
        String providerId,
        String title,
        String description,
        String siteName,
        String thumbnailUrl,
        int imageWidth,
        int imageHeight,
        ContentType contentType
) {
    public PreviewMetadata {
        sourceUrl = require(sourceUrl, "sourceUrl");
        canonicalUrl = require(canonicalUrl, "canonicalUrl");
        providerId = require(providerId, "providerId");
        title = defaultString(title);
        description = defaultString(description);
        siteName = defaultString(siteName);
        thumbnailUrl = require(thumbnailUrl, "thumbnailUrl");
        contentType = Objects.requireNonNull(contentType, "contentType must not be null");

        if (imageWidth <= 0) {
            imageWidth = 1200;
        }
        if (imageHeight <= 0) {
            imageHeight = 630;
        }
    }

    private static String require(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value.strip();
    }
}
