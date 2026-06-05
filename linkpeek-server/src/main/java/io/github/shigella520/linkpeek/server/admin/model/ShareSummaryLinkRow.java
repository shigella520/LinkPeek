package io.github.shigella520.linkpeek.server.admin.model;

public class ShareSummaryLinkRow {
    private String previewKey;
    private String sourceUrl;
    private String canonicalUrl;
    private String providerId;
    private String title;
    private int occurrenceCount;
    private long firstOccurredAt;
    private boolean aiRequested;
    private boolean aiSucceeded;

    public String getPreviewKey() {
        return previewKey;
    }

    public void setPreviewKey(String previewKey) {
        this.previewKey = previewKey;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(int occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public long getFirstOccurredAt() {
        return firstOccurredAt;
    }

    public void setFirstOccurredAt(long firstOccurredAt) {
        this.firstOccurredAt = firstOccurredAt;
    }

    public boolean isAiRequested() {
        return aiRequested;
    }

    public void setAiRequested(boolean aiRequested) {
        this.aiRequested = aiRequested;
    }

    public boolean isAiSucceeded() {
        return aiSucceeded;
    }

    public void setAiSucceeded(boolean aiSucceeded) {
        this.aiSucceeded = aiSucceeded;
    }
}
