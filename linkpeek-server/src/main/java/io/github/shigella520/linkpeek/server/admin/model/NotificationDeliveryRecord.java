package io.github.shigella520.linkpeek.server.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class NotificationDeliveryRecord {
    private Long id;
    private String eventType;
    private String eventKey;
    private long notificationTaskId;
    private String notificationTaskName;
    private long channelId;
    private String channelName;
    private String status;
    private int attemptCount;
    private String requestUrl;
    private String requestBody;
    private String requestBodySnapshot;
    private Integer responseStatus;
    private String responseBodySnapshot;
    private String errorMessage;
    private long durationMs;
    private long createdAt;
    private Long finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public long getNotificationTaskId() {
        return notificationTaskId;
    }

    public void setNotificationTaskId(long notificationTaskId) {
        this.notificationTaskId = notificationTaskId;
    }

    public String getNotificationTaskName() {
        return notificationTaskName;
    }

    public void setNotificationTaskName(String notificationTaskName) {
        this.notificationTaskName = notificationTaskName;
    }

    public long getChannelId() {
        return channelId;
    }

    public void setChannelId(long channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public void setRequestUrl(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    @JsonIgnore
    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getRequestBodySnapshot() {
        return requestBodySnapshot;
    }

    public void setRequestBodySnapshot(String requestBodySnapshot) {
        this.requestBodySnapshot = requestBodySnapshot;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBodySnapshot() {
        return responseBodySnapshot;
    }

    public void setResponseBodySnapshot(String responseBodySnapshot) {
        this.responseBodySnapshot = responseBodySnapshot;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }
}
