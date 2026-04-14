package io.github.shigella520.linkpeek.core.model;

import io.github.shigella520.linkpeek.core.util.UrlNormalizer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record PreviewKey(String value) {
    public PreviewKey {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static PreviewKey fromCanonicalUrl(String canonicalUrl) {
        return fromCanonicalUrl(URI.create(canonicalUrl));
    }

    public static PreviewKey fromCanonicalUrl(URI canonicalUrl) {
        String normalized = UrlNormalizer.normalizeHttpUrl(canonicalUrl).toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return new PreviewKey(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
