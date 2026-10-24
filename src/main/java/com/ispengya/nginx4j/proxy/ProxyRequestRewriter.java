package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.conf.ProxyTarget;
import com.ispengya.nginx4j.http.HttpConstants;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Builds the request head that is sent to an upstream.
 *
 * <p>Rules follow PLAN.md section 9.5: hop-by-hop headers (including the ones named by
 * {@code Connection}) are removed, {@code Host} is rewritten to the proxy target,
 * {@code X-Forwarded-For} gets the client address appended, {@code proxy_set_header} values override
 * by name, and the upstream connection is always declared {@code close} because M3 does not pool
 * upstream connections.
 *
 * <p>The upstream request is always HTTP/1.1 so that chunked bodies and streamed responses keep
 * working; the client-facing protocol version is irrelevant to the upstream hop.
 */
public final class ProxyRequestRewriter {

    /** Not a {@code HttpHeaderNames} constant in every Netty 4.1 release, so spell it out. */
    static final String X_FORWARDED_FOR = "x-forwarded-for";

    private ProxyRequestRewriter() {
    }

    /**
     * @param request client request whose method and URI are forwarded unchanged
     * @param target resolved {@code proxy_pass} target
     * @param client peer address of the client connection, may be {@code null}
     * @param setHeaders effective {@code proxy_set_header} values; an empty value removes the header
     * @return a new head owned by the caller; the original request is not modified
     */
    public static DefaultHttpRequest rewrite(HttpRequest request, ProxyTarget target,
            InetSocketAddress client, Map<String, String> setHeaders) {
        boolean chunked = HttpUtil.isTransferEncodingChunked(request);

        DefaultHttpRequest outbound = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                request.method(), request.uri());
        outbound.headers().set(request.headers());
        HttpConstants.removeHopByHop(outbound.headers());

        outbound.headers().set(HttpHeaderNames.HOST, hostHeader(target));
        appendForwardedFor(outbound.headers(), client);

        for (Map.Entry<String, String> header : setHeaders.entrySet()) {
            if (header.getValue().isEmpty()) {
                outbound.headers().remove(header.getKey());
            } else {
                outbound.headers().set(header.getKey(), header.getValue());
            }
        }

        // Re-frame a chunked request: the transfer coding is hop-by-hop and was just stripped.
        if (chunked) {
            outbound.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        // M3 never reuses an upstream connection, regardless of what proxy_set_header said.
        outbound.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return outbound;
    }

    /**
     * @return the {@code Host} value nginx would send: the upstream name for an upstream reference,
     *         otherwise the literal host plus a non-default port
     */
    static String hostHeader(ProxyTarget target) {
        if (target.isUpstream()) {
            return target.upstreamName();
        }
        String host = target.host().indexOf(':') >= 0
                ? "[" + target.host() + "]"
                : target.host();
        return target.port() == ProxyTarget.DEFAULT_HTTP_PORT ? host : host + ":" + target.port();
    }

    private static void appendForwardedFor(HttpHeaders headers, InetSocketAddress client) {
        String address = "unknown";
        if (client != null && client.getAddress() != null) {
            address = client.getAddress().getHostAddress();
        }
        String existing = headers.get(X_FORWARDED_FOR);
        headers.set(X_FORWARDED_FOR,
                existing == null || existing.isEmpty() ? address : existing + ", " + address);
    }
}
