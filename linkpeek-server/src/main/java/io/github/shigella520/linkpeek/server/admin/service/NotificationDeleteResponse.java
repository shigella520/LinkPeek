package io.github.shigella520.linkpeek.server.admin.service;

/** Response body of the notification admin delete endpoints: {@code {"deleted": n}}. */
public record NotificationDeleteResponse(int deleted) {
}
