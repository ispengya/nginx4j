package com.ispengya.nginx4j.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
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

    /**
     * Removes every hop-by-hop header from one message, including the extension headers named by the
     * message's own {@code Connection} header (RFC 7230 section 6.1).
     *
     * <p>Callers that need to re-frame a chunked body must read
     * {@code HttpUtil.isTransferEncodingChunked(message)} before calling this method, because the
     * transfer coding is removed along with the rest of the hop-by-hop set.
     */
    public static void removeHopByHop(HttpHeaders headers) {
        Set<String> names = new HashSet<String>(HOP_BY_HOP_HEADERS);
        for (String connection : headers.getAll(HttpHeaderNames.CONNECTION)) {
            for (String token : connection.split(",")) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        for (String name : names) {
            headers.remove(name);
        }
    }
}