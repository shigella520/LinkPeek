package io.github.shigella520.linkpeek.server.admin.model;

public class AdminPreviewEventRow {
    private long id;
    private long occurredAt;
    private String previewKey;
    private String sourceUrl;
    private String canonicalUrl;
    private String metadataTitle;
    private String providerId;
    private boolean aiRequested;
    private boolean aiSucceeded;
    private String requestedStyle;
    private String actualStyle;
    private String aiProviderNames;
    private long aiDurationMs;
    private long crawlDurationMs;
    private long durationMs;
    private boolean cacheHit;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(long occurredAt) {
        this.occurredAt = occurredAt;
    }

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

    public String getMetadataTitle() {
        return metadataTitle;
    }

    public void setMetadataTitle(String metadataTitle) {
        this.metadataTitle = metadataTitle;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
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

    public String getRequestedStyle() {
        return requestedStyle;
    }

    public void setRequestedStyle(String requestedStyle) {
        this.requestedStyle = requestedStyle;
    }

    public String getActualStyle() {
        return actualStyle;
    }

    public void setActualStyle(String actualStyle) {
        this.actualStyle = actualStyle;
    }

    public String getAiProviderNames() {
        return aiProviderNames;
    }

    public void setAiProviderNames(String aiProviderNames) {
        this.aiProviderNames = aiProviderNames;
    }

    public long getAiDurationMs() {
        return aiDurationMs;
    }

    public void setAiDurationMs(long aiDurationMs) {
        this.aiDurationMs = aiDurationMs;
    }

    public long getCrawlDurationMs() {
        return crawlDurationMs;
    }

    public void setCrawlDurationMs(long crawlDurationMs) {
        this.crawlDurationMs = crawlDurationMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
    }
}
