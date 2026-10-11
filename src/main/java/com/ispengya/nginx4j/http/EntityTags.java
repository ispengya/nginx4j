package com.ispengya.nginx4j.http;

/**
 * Entity tag (ETag) generation and comparison.
 *
 * <p>The strong tag format follows nginx: {@code "<mtime-seconds-hex>-<size-hex>"}.
 */
public final class EntityTags {

    private static final String WEAK_PREFIX = "W/";

    private EntityTags() {
    }

    /**
     * Builds a strong entity tag from the file metadata nginx uses.
     *
     * @param lastModifiedMillis file modification time in milliseconds
     * @param size file size in bytes
     */
    public static String of(long lastModifiedMillis, long size) {
        return "\"" + Long.toHexString(lastModifiedMillis / 1000L) + '-' + Long.toHexString(size) + '"';
    }

    /**
     * Evaluates an {@code If-None-Match} header: {@code *} matches any existing entity, and weak
     * tags compare by opaque value (RFC 7232 section 2.3.2).
     */
    public static boolean ifNoneMatchMatches(String headerValue, String etag) {
        if (headerValue == null) {
            return false;
        }
        String value = headerValue.trim();
        if ("*".equals(value)) {
            return true;
        }
        for (String candidate : value.split(",")) {
            if (normalize(candidate).equals(normalize(etag))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares an entity-tag form of {@code If-Range} (the date form is handled by the caller).
     */
    public static boolean tagMatches(String headerValue, String etag) {
        return normalize(headerValue).equals(normalize(etag));
    }

    private static String normalize(String tag) {
        String value = tag.trim();
        if (value.startsWith(WEAK_PREFIX)) {
            value = value.substring(WEAK_PREFIX.length()).trim();
        }
        return value;
    }
}