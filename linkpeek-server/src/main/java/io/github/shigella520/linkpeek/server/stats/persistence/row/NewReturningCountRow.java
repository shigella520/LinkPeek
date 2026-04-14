package io.github.shigella520.linkpeek.server.stats.persistence.row;

public class NewReturningCountRow {
    private String dayBucket;
    private long newLinkCount;
    private long returningLinkCount;

    public String getDayBucket() {
        return dayBucket;
    }

    public void setDayBucket(String dayBucket) {
        this.dayBucket = dayBucket;
    }

    public long getNewLinkCount() {
        return newLinkCount;
    }

    public void setNewLinkCount(long newLinkCount) {
        this.newLinkCount = newLinkCount;
    }

    public long getReturningLinkCount() {
        return returningLinkCount;
    }

    public void setReturningLinkCount(long returningLinkCount) {
        this.returningLinkCount = returningLinkCount;
    }
}
