package io.github.shigella520.linkpeek.server.stats.persistence.row;

public class WindowSummaryRow {
    private long createCount;
    private long openCount;
    private long failedCount;
    private long allPreviewRequests;
    private long uniqueLinkCount;

    public long getCreateCount() {
        return createCount;
    }

    public void setCreateCount(long createCount) {
        this.createCount = createCount;
    }

    public long getOpenCount() {
        return openCount;
    }

    public void setOpenCount(long openCount) {
        this.openCount = openCount;
    }

    public long getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(long failedCount) {
        this.failedCount = failedCount;
    }

    public long getAllPreviewRequests() {
        return allPreviewRequests;
    }

    public void setAllPreviewRequests(long allPreviewRequests) {
        this.allPreviewRequests = allPreviewRequests;
    }

    public long getUniqueLinkCount() {
        return uniqueLinkCount;
    }

    public void setUniqueLinkCount(long uniqueLinkCount) {
        this.uniqueLinkCount = uniqueLinkCount;
    }
}
