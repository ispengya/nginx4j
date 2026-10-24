package com.ispengya.nginx4j.proxy;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for the response-hop rewriting rules (PLAN.md section 9.5).
 */
public class ProxyResponseRewriterTest {

    @Test
    public void stripsHopByHopAndReframesChunkedResponses() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive, X-Hop");
        response.headers().set(HttpHeaderNames.KEEP_ALIVE, "timeout=5");
        response.headers().set("X-Hop", "secret");
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        response.headers().set(HttpHeaderNames.SERVER, "upstream/1");

        ProxyResponseRewriter.rewrite(response);

        assertNull(response.headers().get(HttpHeaderNames.CONNECTION));
        assertNull(response.headers().get(HttpHeaderNames.KEEP_ALIVE));
        assertNull(response.headers().get("x-hop"));
        assertEquals(HttpHeaderValues.CHUNKED.toString(),
                response.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
        assertEquals("text/plain", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals("upstream/1", response.headers().get(HttpHeaderNames.SERVER));
    }

    @Test
    public void contentLengthIsPreservedAndNoConnectionHeaderIsInvented() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "5");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        ProxyResponseRewriter.rewrite(response);

        assertEquals("5", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertNull(response.headers().get(HttpHeaderNames.CONNECTION));
    }

    @Test
    public void bodilessStatusesDoNotGetChunkedFraming() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_MODIFIED);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

        ProxyResponseRewriter.rewrite(response);

        assertNull(response.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
    }
}
