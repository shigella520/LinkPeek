package io.github.shigella520.linkpeek.core.model;

public enum ContentType {
    VIDEO("video.other"),
    ARTICLE("article"),
    IMAGE("website"),
    GENERIC("website");

    private final String ogType;

    ContentType(String ogType) {
        this.ogType = ogType;
    }

    public String ogType() {
        return ogType;
    }
}
