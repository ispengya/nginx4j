package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.conf.ProxyTarget;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for the request-hop rewriting rules (PLAN.md section 9.5).
 */
public class ProxyRequestRewriterTest {

    @Test
    public void stripsHopByHopIncludingConnectionTokensAndRewritesForwardingHeaders() throws Exception {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/x?q=1");
        request.headers().set(HttpHeaderNames.HOST, "client.example");
        request.headers().set(HttpHeaderNames.CONNECTION, "keep-alive, X-Drop");
        request.headers().set("X-Drop", "secret");
        request.headers().set(HttpHeaderNames.KEEP_ALIVE, "timeout=5");
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        request.headers().set("X-Keep", "yes");

        DefaultHttpRequest outbound = ProxyRequestRewriter.rewrite(request,
                ProxyTarget.direct("backend.example", 9000, localhost()),
                new InetSocketAddress("192.0.2.10", 12345),
                Collections.<String, String>emptyMap());

        assertEquals(HttpMethod.GET, outbound.method());
        assertEquals("/x?q=1", outbound.uri());
        assertEquals(HttpVersion.HTTP_1_1, outbound.protocolVersion());
        assertNull(outbound.headers().get("x-drop"));
        assertNull(outbound.headers().get(HttpHeaderNames.KEEP_ALIVE));
        assertNull(outbound.headers().get(HttpHeaderNames.UPGRADE));
        assertEquals("yes", outbound.headers().get("x-keep"));
        assertEquals("backend.example:9000", outbound.headers().get(HttpHeaderNames.HOST));
        assertEquals("192.0.2.10", outbound.headers().get(ProxyRequestRewriter.X_FORWARDED_FOR));
        assertEquals(HttpHeaderValues.CLOSE.toString(),
                outbound.headers().get(HttpHeaderNames.CONNECTION));
    }

    @Test
    public void appendsToAnExistingForwardedForChain() throws Exception {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(ProxyRequestRewriter.X_FORWARDED_FOR, "10.0.0.1");

        DefaultHttpRequest outbound = ProxyRequestRewriter.rewrite(request,
                ProxyTarget.direct("127.0.0.1", 80, localhost()),
                new InetSocketAddress("192.0.2.10", 12345),
                Collections.<String, String>emptyMap());

        assertEquals("10.0.0.1, 192.0.2.10",
                outbound.headers().get(ProxyRequestRewriter.X_FORWARDED_FOR));
    }

    @Test
    public void hostHeaderUsesTheUpstreamNameAndBracketsIpv6Literals() throws Exception {
        assertEquals("backend.example", ProxyRequestRewriter.hostHeader(
                ProxyTarget.direct("backend.example", 80, localhost())));
        assertEquals("backend.example:8080", ProxyRequestRewriter.hostHeader(
                ProxyTarget.direct("backend.example", 8080, localhost())));
        assertEquals("[2001:db8::1]:8080", ProxyRequestRewriter.hostHeader(
                ProxyTarget.direct("2001:db8::1", 8080, localhost())));
        assertEquals("pool", ProxyRequestRewriter.hostHeader(ProxyTarget.upstream("pool")));
    }

    @Test
    public void proxySetHeaderOverridesByCaseInsensitiveNameAndEmptyValueDeletes() throws Exception {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set("X-Remove", "old");
        request.headers().set("X-Keep", "kept");

        Map<String, String> overrides = new LinkedHashMap<String, String>();
        overrides.put("host", "edge.example");
        overrides.put(ProxyRequestRewriter.X_FORWARDED_FOR, "10.0.0.1");
        overrides.put("X-Remove", "");

        DefaultHttpRequest outbound = ProxyRequestRewriter.rewrite(request,
                ProxyTarget.direct("backend.example", 9000, localhost()),
                new InetSocketAddress("192.0.2.10", 12345), overrides);

        assertEquals("edge.example", outbound.headers().get(HttpHeaderNames.HOST));
        assertEquals("10.0.0.1",
                outbound.headers().get(ProxyRequestRewriter.X_FORWARDED_FOR));
        assertNull(outbound.headers().get("x-remove"));
        assertEquals("kept", outbound.headers().get("x-keep"));
    }

    @Test
    public void chunkedRequestBodiesKeepTheirFraming() throws Exception {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

        DefaultHttpRequest outbound = ProxyRequestRewriter.rewrite(request,
                ProxyTarget.direct("127.0.0.1", 9000, localhost()), null,
                Collections.<String, String>emptyMap());

        assertEquals(HttpHeaderValues.CHUNKED.toString(),
                outbound.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
        assertEquals("unknown",
                outbound.headers().get(ProxyRequestRewriter.X_FORWARDED_FOR));
    }

    private static List<InetAddress> localhost() throws Exception {
        return Collections.singletonList(InetAddress.getByName("127.0.0.1"));
    }
}
