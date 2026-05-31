package io.github.shigella520.linkpeek.server.admin.model;

import java.util.Locale;

public enum NotificationEventType {
    SHARE_SUMMARY_IMAGE_SUCCESS;

    public static NotificationEventType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Notification event type is required.");
        }
        try {
            return NotificationEventType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported notification event type: " + value, exception);
        }
    }
}
