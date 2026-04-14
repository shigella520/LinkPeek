package io.github.shigella520.linkpeek.server.stats.model;

public class StatisticsLinkRecord {
    private String previewKey;
    private String providerId;
    private String canonicalUrl;
    private String title;
    private String siteName;
    private long firstSeenAt;
    private long lastSeenAt;

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

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public long getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(long firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
