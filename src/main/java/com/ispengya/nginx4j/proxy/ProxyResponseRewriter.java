package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.http.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;

/**
 * Cleans one upstream response head in place before it is sent to the client.
 *
 * <p>Every hop-by-hop header is removed, including extension headers named by the upstream
 * {@code Connection}. A chunked response is re-framed as chunked for the client hop, because removing
 * {@code Transfer-Encoding} without knowing the length would make the response unframed. The
 * {@code Connection} header is deliberately not set here: {@code HttpServerKeepAliveHandler} derives
 * the client-facing semantics from the original client request.
 */
public final class ProxyResponseRewriter {

    private ProxyResponseRewriter() {
    }

    public static void rewrite(HttpResponse response) {
        boolean chunked = HttpUtil.isTransferEncodingChunked(response);
        HttpConstants.removeHopByHop(response.headers());

        int status = response.status().code();
        if (chunked && status != 204 && status != 304) {
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
    }
}
