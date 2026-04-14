package io.github.shigella520.linkpeek.core.util;

import io.github.shigella520.linkpeek.core.error.InvalidPreviewUrlException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class UrlNormalizer {
    private UrlNormalizer() {
    }

    public static URI parseHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidPreviewUrlException("The url parameter is required.");
        }

        try {
            return normalizeHttpUrl(new URI(rawUrl.strip()));
        } catch (URISyntaxException exception) {
            throw new InvalidPreviewUrlException("The supplied URL is not valid.");
        }
    }

    public static URI normalizeHttpUrl(URI input) {
        if (input == null) {
            throw new InvalidPreviewUrlException("The supplied URL is not valid.");
        }
        String scheme = lower(input.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new InvalidPreviewUrlException("Only http and https URLs are supported.");
        }
        String host = lower(input.getHost());
        if (host == null || host.isBlank()) {
            throw new InvalidPreviewUrlException("The supplied URL must include a host.");
        }

        int port = input.getPort();
        if ((port == 80 && "http".equals(scheme)) || (port == 443 && "https".equals(scheme))) {
            port = -1;
        }

        try {
            return new URI(
                    scheme,
                    input.getUserInfo(),
                    host,
                    port,
                    normalizePath(input.getPath()),
                    input.getRawQuery(),
                    null
            );
        } catch (URISyntaxException exception) {
            throw new InvalidPreviewUrlException("The supplied URL is not valid.");
        }
    }

    private static String normalizePath(String path) {
        return (path == null || path.isBlank()) ? "/" : path;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
