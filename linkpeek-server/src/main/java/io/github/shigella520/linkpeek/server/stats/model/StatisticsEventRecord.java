package io.github.shigella520.linkpeek.server.stats.model;

public class StatisticsEventRecord {
    private long occurredAt;
    private String eventType;
    private String previewKey;
    private String providerId;
    private int httpStatus;
    private boolean cacheHit;
    private boolean aiRequested;
    private boolean aiSucceeded;
    private String sourceUrl;
    private String requestedStyle;
    private String actualStyle;
    private String aiProviderNames;
    private long aiDurationMs;
    private long crawlDurationMs;
    private Long aiInputTokens;
    private Long aiOutputTokens;

    public Long getAiInputTokens() { return aiInputTokens; }
    public void setAiInputTokens(Long aiInputTokens) { this.aiInputTokens = aiInputTokens; }
    public Long getAiOutputTokens() { return aiOutputTokens; }
    public void setAiOutputTokens(Long aiOutputTokens) { this.aiOutputTokens = aiOutputTokens; }
    private long durationMs;
    private String clientType;
    private String errorCode;

    public long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(long occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPreviewKey() {
        return previewKey;
    }

    public void setPreviewKey(String previewKey) {
        this.previewKey = previewKey;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
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

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
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

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
