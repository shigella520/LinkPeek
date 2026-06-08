package io.github.shigella520.linkpeek.server.admin.model;

public class ShareSummaryTaskRecord {
    private Long id;
    private String name;
    private boolean enabled;
    private String periodType;
    private String periodSelectionMode;
    private String runTime;
    private Integer dayOfWeek;
    private String prompt;
    private int maxLinks;
    private int minLinks;
    private boolean deleted;
    private Long deletedAt;
    private long createdAt;
    private long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPeriodType() {
        return periodType;
    }

    public void setPeriodType(String periodType) {
        this.periodType = periodType;
    }

    public String getPeriodSelectionMode() {
        return periodSelectionMode;
    }

    public void setPeriodSelectionMode(String periodSelectionMode) {
        this.periodSelectionMode = periodSelectionMode;
    }

    public String getRunTime() {
        return runTime;
    }

    public void setRunTime(String runTime) {
        this.runTime = runTime;
    }

    public Integer getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(Integer dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public int getMaxLinks() {
        return maxLinks;
    }

    public void setMaxLinks(int maxLinks) {
        this.maxLinks = maxLinks;
    }

    public int getMinLinks() {
        return minLinks;
    }

    public void setMinLinks(int minLinks) {
        this.minLinks = minLinks;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
