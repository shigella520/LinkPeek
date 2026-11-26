package io.github.shigella520.linkpeek.server.admin.service;

import org.springframework.util.StringUtils;

/**
 * Describes failures of share summary asset generation (images and audio) for persisted error
 * messages and failure notifications.
 */
final class ShareSummaryAssetErrors {
    static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private ShareSummaryAssetErrors() {
    }

    /** Simple class name of the exception, or {@code fallbackType} when there is no exception. */
    static String errorType(Throwable exception, String fallbackType) {
        return exception == null ? fallbackType : exception.getClass().getSimpleName();
    }

    /**
     * First non-blank message along the cause chain, else {@code fallbackMessage}, else
     * {@code defaultMessage}; stripped and truncated to {@value #MAX_ERROR_MESSAGE_LENGTH} characters.
     */
    static String errorMessage(Throwable exception, String fallbackMessage, String defaultMessage) {
        String message = firstErrorMessage(exception);
        if (!StringUtils.hasText(message)) {
            message = fallbackMessage;
        }
        if (!StringUtils.hasText(message)) {
            message = defaultMessage;
        }
        String stripped = message.strip();
        return stripped.length() <= MAX_ERROR_MESSAGE_LENGTH ? stripped : stripped.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    /** First non-blank message along the cause chain, or an empty string. */
    static String firstErrorMessage(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "";
    }
}
