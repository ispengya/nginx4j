package com.ispengya.nginx4j.http;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared HTTP protocol constants.
 *
 * <p>The hop-by-hop set is the RFC 7230 section 6.1 list and is consumed by the M3 proxy request
 * rewriter, which must strip these headers before forwarding to an upstream.
 */
public final class HttpConstants {

    /** Value of the {@code Server} response header; keep in sync with the pom version. */
    public static final String SERVER_NAME = "nginx4j/0.1.0";

    /** Headers that are meaningful for a single hop only and must never be forwarded. */
    public static final Set<String> HOP_BY_HOP_HEADERS;

    static {
        Set<String> headers = new HashSet<String>();
        headers.add("connection");
        headers.add("keep-alive");
        headers.add("proxy-authenticate");
        headers.add("proxy-authorization");
        headers.add("te");
        headers.add("trailer");
        headers.add("transfer-encoding");
        headers.add("upgrade");
        HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(headers);
    }

    private HttpConstants() {
    }
}