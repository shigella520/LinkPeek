package io.github.shigella520.linkpeek.server.stats.persistence.row;

public class DailyEventCountRow {
    private String dayBucket;
    private String eventType;
    private long totalCount;

    public String getDayBucket() {
        return dayBucket;
    }

    public void setDayBucket(String dayBucket) {
        this.dayBucket = dayBucket;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(long totalCount) {
        this.totalCount = totalCount;
    }
}
