package com.ispengya.nginx4j.http;

/**
 * A parsed {@code Range} request header.
 *
 * <p>Only a single byte range is honoured, matching nginx: multi-range values and syntactically
 * invalid values are ignored (the whole entity is served with 200), while a syntactically valid but
 * unsatisfiable range yields 416.
 */
public final class RangeRequest {

    private static final String PREFIX = "bytes=";
    private static final RangeRequest NONE = new RangeRequest(Kind.NONE, 0, 0);
    private static final RangeRequest UNSATISFIABLE = new RangeRequest(Kind.UNSATISFIABLE, 0, 0);

    /**
     * Outcome of parsing a {@code Range} header.
     */
    public enum Kind {
        /** No usable range: serve the whole entity with 200. */
        NONE,
        /** A single satisfiable range: serve 206 with the recorded slice. */
        SATISFIABLE,
        /** A valid but unsatisfiable range: serve 416. */
        UNSATISFIABLE
    }

    private final Kind kind;
    private final long start;
    private final long length;

    private RangeRequest(Kind kind, long start, long length) {
        this.kind = kind;
        this.start = start;
        this.length = length;
    }

    /**
     * Parses a {@code Range} header value against the entity length.
     *
     * @param headerValue raw header value, may be {@code null}
     * @param contentLength total entity length in bytes
     */
    public static RangeRequest parse(String headerValue, long contentLength) {
        if (headerValue == null) {
            return NONE;
        }
        String value = headerValue.trim();
        if (!value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return NONE;
        }
        String spec = value.substring(PREFIX.length()).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            // Multi-range: nginx serves the full entity instead of multipart/byteranges.
            return NONE;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return NONE;
        }
        String first = spec.substring(0, dash).trim();
        String second = spec.substring(dash + 1).trim();

        if (first.isEmpty()) {
            // Suffix form: "bytes=-N" means the last N bytes.
            Long suffix = parseLong(second);
            if (suffix == null) {
                return NONE;
            }
            if (suffix <= 0 || contentLength == 0) {
                return UNSATISFIABLE;
            }
            long suffixLength = Math.min(suffix, contentLength);
            return new RangeRequest(Kind.SATISFIABLE, contentLength - suffixLength, suffixLength);
        }

        Long start = parseLong(first);
        if (start == null) {
            return NONE;
        }
        long end;
        if (second.isEmpty()) {
            end = contentLength - 1;
        } else {
            Long parsedEnd = parseLong(second);
            if (parsedEnd == null) {
                return NONE;
            }
            end = parsedEnd;
        }
        if (start > end || start >= contentLength) {
            return UNSATISFIABLE;
        }
        if (end > contentLength - 1) {
            end = contentLength - 1;
        }
        return new RangeRequest(Kind.SATISFIABLE, start, end - start + 1);
    }

    private static Long parseLong(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Kind kind() {
        return kind;
    }

    public long start() {
        return start;
    }

    public long length() {
        return length;
    }

    public long end() {
        return start + length - 1;
    }

    /**
     * @return the {@code Content-Range} value of this slice, e.g. {@code bytes 2-5/256}
     */
    public String contentRange(long contentLength) {
        return "bytes " + start + "-" + end() + "/" + contentLength;
    }
}