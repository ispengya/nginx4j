package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.conf.ProxyTarget;
import com.ispengya.nginx4j.conf.UpstreamServer;
import com.ispengya.nginx4j.router.ResolvedLocation;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One proxied request/response exchange, living on a single EventLoop.
 *
 * <p>Both channels point at the transaction through {@link #ATTR}, which lets either side find the
 * other when a read, write, timeout or close event arrives. M3 does not pool upstream connections, so
 * a transaction exists exactly as long as its upstream connection.
 *
 * <p>Every field is touched only by the owning EventLoop; nothing here is synchronized.
 */
public final class ProxyTransaction {

    /** Set on both the client channel and the upstream channel. */
    public static final AttributeKey<ProxyTransaction> ATTR =
            AttributeKey.valueOf("nginx4j.proxy.transaction");

    private final Channel frontend;
    private final ResolvedLocation location;
    private final boolean keepAlive;
    private final ProxyTarget target;
    private final UpstreamServer upstreamServer;
    private final HttpRequest outboundRequest;
    private final Deque<HttpContent> pending = new ArrayDeque<HttpContent>();

    private Channel backend;
    private boolean requestComplete;
    private boolean responseStarted;
    private boolean responseComplete;
    private boolean finished;

    ProxyTransaction(Channel frontend, ResolvedLocation location, boolean keepAlive,
            ProxyTarget target, UpstreamServer upstreamServer, HttpRequest outboundRequest) {
        this.frontend = frontend;
        this.location = location;
        this.keepAlive = keepAlive;
        this.target = target;
        this.upstreamServer = upstreamServer;
        this.outboundRequest = outboundRequest;
        frontend.attr(ATTR).set(this);
    }

    public Channel frontend() {
        return frontend;
    }

    public Channel backend() {
        return backend;
    }

    public ResolvedLocation location() {
        return location;
    }

    public ProxyTarget target() {
        return target;
    }

    public UpstreamServer upstreamServer() {
        return upstreamServer;
    }

    public HttpRequest outboundRequest() {
        return outboundRequest;
    }

    /** @return whether the client asked for the connection to stay open */
    public boolean keepAlive() {
        return keepAlive;
    }

    void attachBackend(Channel channel) {
        this.backend = channel;
        channel.attr(ATTR).set(this);
    }

    void enqueue(HttpContent content) {
        pending.add(content);
    }

    HttpContent pollPending() {
        return pending.poll();
    }

    void markRequestComplete() {
        requestComplete = true;
    }

    public boolean isRequestComplete() {
        return requestComplete;
    }

    void markResponseStarted() {
        responseStarted = true;
    }

    public boolean isResponseStarted() {
        return responseStarted;
    }

    void markResponseComplete() {
        responseComplete = true;
    }

    public boolean isResponseComplete() {
        return responseComplete;
    }

    /** Releases every buffered request chunk; used on abort and before the backend sees them. */
    void releasePending() {
        HttpContent content;
        while ((content = pending.poll()) != null) {
            ReferenceCountUtil.release(content);
        }
    }

    /**
     * Ends the transaction and clears both channel attributes.
     *
     * @return {@code false} when it had already finished, so callers can avoid double cleanup
     */
    boolean finish() {
        if (finished) {
            return false;
        }
        finished = true;
        releasePending();
        frontend.attr(ATTR).set(null);
        if (backend != null) {
            backend.attr(ATTR).set(null);
        }
        return true;
    }

    public boolean isFinished() {
        return finished;
    }
}
