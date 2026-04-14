package io.github.shigella520.linkpeek.server.stats.persistence.row;

public class FailureCountRow {
    private String errorCode;
    private long totalCount;

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(long totalCount) {
        this.totalCount = totalCount;
    }
}
