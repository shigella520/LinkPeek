package io.github.shigella520.linkpeek.server.admin.model;

public class ShareSummaryRunRecord {
    private Long id;
    private long taskId;
    private String taskName;
    private String triggerType;
    private String periodType;
    private long windowStart;
    private long windowEnd;
    private String status;
    private int linkCount;
    private int uniqueLinkCount;
    private int inputLinkCount;
    private String promptSnapshot;
    private String aiProviderNames;
    private long aiDurationMs;
    private String report;
    private String errorMessage;
    private long startedAt;
    private Long finishedAt;
    private String imageStatus;
    private String latestImageUrl;
    private String ogImageUrl;
    private String ogPageUrl;
    private String ogShareUrl;
    private String ogTitle;
    private String ogDescription;
    private String imageErrorMessage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getTaskId() {
        return taskId;
    }

    public void setTaskId(long taskId) {
        this.taskId = taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getPeriodType() {
        return periodType;
    }

    public void setPeriodType(String periodType) {
        this.periodType = periodType;
    }

    public long getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(long windowStart) {
        this.windowStart = windowStart;
    }

    public long getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(long windowEnd) {
        this.windowEnd = windowEnd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getLinkCount() {
        return linkCount;
    }

    public void setLinkCount(int linkCount) {
        this.linkCount = linkCount;
    }

    public int getUniqueLinkCount() {
        return uniqueLinkCount;
    }

    public void setUniqueLinkCount(int uniqueLinkCount) {
        this.uniqueLinkCount = uniqueLinkCount;
    }

    public int getInputLinkCount() {
        return inputLinkCount;
    }

    public void setInputLinkCount(int inputLinkCount) {
        this.inputLinkCount = inputLinkCount;
    }

    public String getPromptSnapshot() {
        return promptSnapshot;
    }

    public void setPromptSnapshot(String promptSnapshot) {
        this.promptSnapshot = promptSnapshot;
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

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getImageStatus() {
        return imageStatus;
    }

    public void setImageStatus(String imageStatus) {
        this.imageStatus = imageStatus;
    }

    public String getLatestImageUrl() {
        return latestImageUrl;
    }

    public void setLatestImageUrl(String latestImageUrl) {
        this.latestImageUrl = latestImageUrl;
    }

    public String getOgImageUrl() {
        return ogImageUrl;
    }

    public void setOgImageUrl(String ogImageUrl) {
        this.ogImageUrl = ogImageUrl;
    }

    public String getOgPageUrl() {
        return ogPageUrl;
    }

    public void setOgPageUrl(String ogPageUrl) {
        this.ogPageUrl = ogPageUrl;
    }

    public String getOgShareUrl() {
        return ogShareUrl;
    }

    public void setOgShareUrl(String ogShareUrl) {
        this.ogShareUrl = ogShareUrl;
    }

    public String getOgTitle() {
        return ogTitle;
    }

    public void setOgTitle(String ogTitle) {
        this.ogTitle = ogTitle;
    }

    public String getOgDescription() {
        return ogDescription;
    }

    public void setOgDescription(String ogDescription) {
        this.ogDescription = ogDescription;
    }

    public String getImageErrorMessage() {
        return imageErrorMessage;
    }

    public void setImageErrorMessage(String imageErrorMessage) {
        this.imageErrorMessage = imageErrorMessage;
    }
}
