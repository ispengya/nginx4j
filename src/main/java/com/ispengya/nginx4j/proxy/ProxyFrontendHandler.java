package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.conf.HttpBlock;
import com.ispengya.nginx4j.conf.ProxyTarget;
import com.ispengya.nginx4j.conf.UpstreamBlock;
import com.ispengya.nginx4j.conf.UpstreamServer;
import com.ispengya.nginx4j.http.HttpConstants;
import com.ispengya.nginx4j.router.ResolvedLocation;
import com.ispengya.nginx4j.server.IdleTimeoutHandler;
import com.ispengya.nginx4j.util.HttpDates;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Frontend half of one proxied exchange: picks an upstream, opens a connection on the same
 * EventLoop, streams the request body, applies the three timeouts and maps failures to 502/504.
 *
 * <p>Backpressure is reciprocal: while the upstream is not writable the frontend stops reading, and
 * while the client is not writable the upstream stops reading. Both directions are driven from
 * {@code ChannelWritabilityChanged} events rather than by polling.
 *
 * <p>An instance belongs to one client channel (created by {@code RequestDispatcher}); at most one
 * transaction is active at a time. A second request arriving before the first finishes is rejected
 * by the dispatcher.
 */
public final class ProxyFrontendHandler {

    private static final Logger LOG = Logger.getLogger(ProxyFrontendHandler.class.getName());

    private final HttpBlock http;
    private final LoadBalancer loadBalancer;
    private ChannelHandlerContext context;
    private ProxyTransaction transaction;

    public ProxyFrontendHandler(HttpBlock http, LoadBalancer loadBalancer) {
        this.http = http;
        this.loadBalancer = loadBalancer;
    }

    public boolean isActive() {
        return transaction != null && !transaction.isFinished();
    }

    /**
     * Starts forwarding one request. The caller keeps ownership of {@code request} and releases it;
     * a full request's body is copied before this method returns.
     */
    public void start(ChannelHandlerContext ctx, HttpRequest request, ResolvedLocation location) {
        this.context = ctx;
        ProxyTarget target = location.proxyTarget();
        UpstreamServer selected = null;
        final InetAddress address;
        final int port;
        if (target.isUpstream()) {
            UpstreamBlock upstream = http.upstream(target.upstreamName());
            if (upstream == null) {
                // The parser rejects this; a hand-built config could still get here.
                writeUnavailable(ctx);
                return;
            }
            selected = loadBalancer.select(upstream);
            address = selected.addresses().get(0);
            port = selected.port();
        } else {
            address = target.addresses().get(0);
            port = target.port();
        }

        DefaultHttpRequest outbound = ProxyRequestRewriter.rewrite(request, target,
                (InetSocketAddress) ctx.channel().remoteAddress(), location.proxySetHeaders());
        final ProxyTransaction txn = new ProxyTransaction(ctx.channel(), location,
                HttpUtil.isKeepAlive(request), target, selected, outbound);
        this.transaction = txn;

        if (request instanceof HttpContent) {
            HttpContent content = (HttpContent) request;
            txn.enqueue(copyContent(content));
            if (content instanceof LastHttpContent) {
                txn.markRequestComplete();
            }
        }

        // Stop reading the client until the upstream is connected, so a slow connect cannot grow
        // the pending queue without bound.
        ctx.channel().config().setAutoRead(false);
        connect(ctx, txn, address, port);
    }

    /** Takes ownership of a request body chunk from the dispatcher. */
    public void onContent(HttpContent content) {
        ProxyTransaction txn = transaction;
        if (txn == null || txn.isFinished()) {
            ReferenceCountUtil.release(content);
            return;
        }
        if (content instanceof LastHttpContent) {
            txn.markRequestComplete();
        }
        if (txn.backend() != null) {
            txn.backend().writeAndFlush(content);
        } else {
            txn.enqueue(content);
        }
        updateAutoRead();
    }

    void onBackendComplete(final ProxyTransaction backendTxn, final LastHttpContent last) {
        final ProxyTransaction txn = backendTxn;
        if (txn != transaction || txn.isFinished()) {
            ReferenceCountUtil.release(last);
            return;
        }
        txn.markResponseComplete();
        final boolean canKeepAlive = txn.keepAlive() && txn.isRequestComplete()
                && txn.frontend().isActive();
        ChannelFuture write = txn.frontend().writeAndFlush(last);
        write.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                txn.finish();
                if (transaction == txn) {
                    transaction = null;
                }
                Channel backend = txn.backend();
                if (backend != null) {
                    backend.close();
                }
                if (context != null && context.channel().isActive()) {
                    context.channel().config().setAutoRead(true);
                    if (canKeepAlive) {
                        IdleTimeoutHandler.startKeepAliveTimeout(context,
                                txn.location().keepAliveTimeoutMillis());
                    } else {
                        context.close();
                    }
                }
            }
        });
    }

    void onBackendClosed(ProxyTransaction backendTxn) {
        ProxyTransaction txn = backendTxn;
        if (txn != transaction || txn.isFinished() || txn.isResponseComplete()) {
            return;
        }
        if (txn.isResponseStarted()) {
            txn.finish();
            transaction = null;
            if (context != null) {
                context.close();
            }
        } else {
            fail(txn, HttpResponseStatus.BAD_GATEWAY);
        }
    }

    void onBackendTimeout(ProxyTransaction backendTxn) {
        if (backendTxn != transaction || backendTxn.isFinished()) {
            return;
        }
        fail(backendTxn, HttpResponseStatus.GATEWAY_TIMEOUT);
    }

    void onBackendFailure(ProxyTransaction backendTxn, Throwable cause) {
        if (backendTxn != transaction || backendTxn.isFinished()) {
            return;
        }
        LOG.log(Level.FINE, "upstream failed", cause);
        fail(backendTxn, HttpResponseStatus.BAD_GATEWAY);
    }

    void onBackendWritabilityChanged(ProxyTransaction backendTxn) {
        if (backendTxn != transaction) {
            return;
        }
        updateAutoRead();
    }

    public void onFrontendWritabilityChanged(boolean writable) {
        ProxyTransaction txn = transaction;
        if (txn != null && txn.backend() != null) {
            txn.backend().config().setAutoRead(writable);
        }
    }

    public void onFrontendInactive() {
        ProxyTransaction txn = transaction;
        transaction = null;
        context = null;
        if (txn == null) {
            return;
        }
        txn.finish();
        Channel backend = txn.backend();
        if (backend != null) {
            backend.close();
        }
    }

    private void connect(final ChannelHandlerContext ctx, final ProxyTransaction txn,
            InetAddress address, int port) {
        final long connectTimeoutMillis = txn.location().proxyConnectTimeoutMillis();
        final long readTimeoutMillis = txn.location().proxyReadTimeoutMillis();
        final long sendTimeoutMillis = txn.location().proxySendTimeoutMillis();

        Bootstrap bootstrap = new Bootstrap()
                .group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout(connectTimeoutMillis))
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast("http-codec", new HttpClientCodec());
                        // IdleStateHandler's arguments are (reader, writer, all); proxy_read_timeout
                        // is the reader idle time between two upstream inbound reads.
                        pipeline.addLast("read-timeout", new IdleStateHandler(readTimeoutMillis, 0, 0,
                                TimeUnit.MILLISECONDS));
                        if (sendTimeoutMillis > 0) {
                            pipeline.addLast("write-timeout",
                                    new WriteTimeoutHandler(sendTimeoutMillis, TimeUnit.MILLISECONDS));
                        }
                        pipeline.addLast("proxy-backend",
                                new ProxyBackendHandler(txn, ProxyFrontendHandler.this));
                    }
                });

        bootstrap.connect(address, port).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    if (!txn.isFinished()) {
                        fail(txn, future.cause() instanceof ConnectTimeoutException
                                ? HttpResponseStatus.GATEWAY_TIMEOUT
                                : HttpResponseStatus.BAD_GATEWAY);
                    }
                    return;
                }
                if (txn.isFinished() || !ctx.channel().isActive()) {
                    future.channel().close();
                    return;
                }
                onConnected(txn, future.channel());
            }
        });
    }

    private void onConnected(ProxyTransaction txn, Channel backend) {
        txn.attachBackend(backend);
        backend.write(txn.outboundRequest());
        HttpContent content;
        while ((content = txn.pollPending()) != null) {
            backend.write(content);
        }
        backend.flush();
        updateAutoRead();
    }

    private void updateAutoRead() {
        ProxyTransaction txn = transaction;
        if (context == null || txn == null || txn.isFinished()) {
            return;
        }
        boolean read = txn.backend() != null && !txn.isRequestComplete()
                && txn.backend().isWritable();
        context.channel().config().setAutoRead(read);
    }

    /**
     * Writes a 502/504 when no response head has been sent and then either keeps the client
     * connection (the request body was fully consumed) or closes it.
     */
    private void fail(ProxyTransaction txn, HttpResponseStatus status) {
        if (txn != transaction || txn.isFinished()) {
            return;
        }
        txn.finish();
        transaction = null;
        Channel backend = txn.backend();
        if (backend != null) {
            backend.close();
        }

        Channel frontend = txn.frontend();
        if (!frontend.isActive()) {
            return;
        }
        if (txn.isResponseStarted()) {
            frontend.close();
            return;
        }

        boolean keepAlive = txn.keepAlive() && txn.isRequestComplete();
        frontend.config().setAutoRead(false);
        frontend.writeAndFlush(errorResponse(status, keepAlive))
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        if (!frontend.isActive()) {
                            return;
                        }
                        frontend.config().setAutoRead(true);
                        if (keepAlive) {
                            IdleTimeoutHandler.startKeepAliveTimeout(context,
                                    txn.location().keepAliveTimeoutMillis());
                        } else {
                            frontend.close();
                        }
                    }
                });
    }

    private void writeUnavailable(ChannelHandlerContext ctx) {
        ctx.channel().config().setAutoRead(false);
        ctx.writeAndFlush(errorResponse(HttpResponseStatus.BAD_GATEWAY, false))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private static FullHttpResponse errorResponse(HttpResponseStatus status, boolean keepAlive) {
        ByteBuf content = Unpooled.copiedBuffer(status.code() + " " + status.reasonPhrase() + "\n",
                CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.DATE, HttpDates.format(new Date()));
        response.headers().set(HttpHeaderNames.SERVER, HttpConstants.SERVER_NAME);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        HttpUtil.setKeepAlive(response, keepAlive);
        return response;
    }

    private static HttpContent copyContent(HttpContent content) {
        if (content instanceof LastHttpContent) {
            DefaultLastHttpContent copy = new DefaultLastHttpContent(content.content().retain());
            copy.trailingHeaders().set(((LastHttpContent) content).trailingHeaders());
            return copy;
        }
        return new DefaultHttpContent(content.content().retain());
    }

    private static int connectTimeout(long millis) {
        if (millis <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
    }
}
