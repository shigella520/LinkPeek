package io.github.shigella520.linkpeek.core.util;

public final class HtmlEscaper {
    private HtmlEscaper() {
    }

    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
