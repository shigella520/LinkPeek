package io.github.shigella520.linkpeek.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "linkpeek")
public class LinkPeekProperties {
    private String baseUrl = "http://localhost:8080";
    private String webIconPath;
    private Path cacheDir = Path.of("/data/cache");
    private Path statsDbPath = Path.of("/data/stats/linkpeek.db");
    private long cacheTtlSeconds = 86400;
    private double cacheMaxSizeGb = 10.0;
    private int statsRetentionDays = 180;
    private Duration downloadTimeout = Duration.ofSeconds(120);
    private String logLevel = "INFO";
    private int videoMaxQuality = 480;
    private boolean previewWarmupEnabled = true;
    private int previewWarmupThreads = 2;
    private int previewWarmupQueueCapacity = 64;
    private List<String> crawlerSignatures = List.of(
            "facebookexternalhit",
            "Facebot",
            "Twitterbot",
            "Applebot"
    );

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWebIconPath() {
        return webIconPath;
    }

    public void setWebIconPath(String webIconPath) {
        this.webIconPath = webIconPath;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public Path getStatsDbPath() {
        return statsDbPath;
    }

    public void setStatsDbPath(Path statsDbPath) {
        this.statsDbPath = statsDbPath;
    }

    public double getCacheMaxSizeGb() {
        return cacheMaxSizeGb;
    }

    public void setCacheMaxSizeGb(double cacheMaxSizeGb) {
        this.cacheMaxSizeGb = cacheMaxSizeGb;
    }

    public int getStatsRetentionDays() {
        return statsRetentionDays;
    }

    public void setStatsRetentionDays(int statsRetentionDays) {
        this.statsRetentionDays = statsRetentionDays;
    }

    public Duration getDownloadTimeout() {
        return downloadTimeout;
    }

    public void setDownloadTimeout(Duration downloadTimeout) {
        this.downloadTimeout = downloadTimeout;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public int getVideoMaxQuality() {
        return videoMaxQuality;
    }

    public void setVideoMaxQuality(int videoMaxQuality) {
        this.videoMaxQuality = videoMaxQuality;
    }

    public boolean isPreviewWarmupEnabled() {
        return previewWarmupEnabled;
    }

    public void setPreviewWarmupEnabled(boolean previewWarmupEnabled) {
        this.previewWarmupEnabled = previewWarmupEnabled;
    }

    public int getPreviewWarmupThreads() {
        return previewWarmupThreads;
    }

    public void setPreviewWarmupThreads(int previewWarmupThreads) {
        this.previewWarmupThreads = previewWarmupThreads;
    }

    public int getPreviewWarmupQueueCapacity() {
        return previewWarmupQueueCapacity;
    }

    public void setPreviewWarmupQueueCapacity(int previewWarmupQueueCapacity) {
        this.previewWarmupQueueCapacity = previewWarmupQueueCapacity;
    }

    public List<String> getCrawlerSignatures() {
        return crawlerSignatures;
    }

    public void setCrawlerSignatures(List<String> crawlerSignatures) {
        this.crawlerSignatures = crawlerSignatures;
    }
}
