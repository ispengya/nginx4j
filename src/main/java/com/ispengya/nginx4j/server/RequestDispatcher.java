package com.ispengya.nginx4j.server;

import com.ispengya.nginx4j.conf.HttpBlock;
import com.ispengya.nginx4j.proxy.LoadBalancer;
import com.ispengya.nginx4j.proxy.ProxyFrontendHandler;
import com.ispengya.nginx4j.router.ResolvedLocation;
import com.ispengya.nginx4j.router.Router;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single entry point for a client request: routes it once, then hands it to the static content
 * path or the reverse proxy (PLAN.md section 9.4).
 *
 * <p>HTTP/1.1 pipelining is not supported for proxied requests: a second request arriving before the
 * first exchange completes is rejected and the connection is closed, instead of interleaving two
 * asynchronous responses. Static responses are written immediately, so they never overlap.
 */
public final class RequestDispatcher extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = Logger.getLogger(RequestDispatcher.class.getName());

    private final Router router;
    private final StaticFileHandler staticHandler;
    private final ProxyFrontendHandler proxyFrontend;

    /**
     * @param router immutable router built once per server
     * @param http immutable configuration used to resolve upstream groups
     * @param loadBalancer shared balancer whose cursors are per EventLoop
     */
    public RequestDispatcher(Router router, HttpBlock http, LoadBalancer loadBalancer) {
        this.router = router;
        this.staticHandler = new StaticFileHandler(router);
        this.proxyFrontend = new ProxyFrontendHandler(http, loadBalancer);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpRequest)) {
            if (msg instanceof HttpContent && proxyFrontend.isActive()) {
                proxyFrontend.onContent((HttpContent) msg);
                return;
            }
            ReferenceCountUtil.release(msg);
            return;
        }

        HttpRequest request = (HttpRequest) msg;
        try {
            if (proxyFrontend.isActive()) {
                rejectPipelined(ctx);
                return;
            }
            ResolvedLocation location = router.route(
                    request.headers().get(HttpHeaderNames.HOST),
                    new QueryStringDecoder(request.uri()).path());
            if (location.isProxy()) {
                proxyFrontend.start(ctx, request, location);
            } else {
                staticHandler.handle(ctx, request, location);
            }
        } finally {
            ReferenceCountUtil.release(request);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        proxyFrontend.onFrontendWritabilityChanged(ctx.channel().isWritable());
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        proxyFrontend.onFrontendInactive();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.FINE, "client connection failed " + ctx.channel().remoteAddress(), cause);
        proxyFrontend.onFrontendInactive();
        ctx.close();
    }

    private void rejectPipelined(ChannelHandlerContext ctx) {
        proxyFrontend.onFrontendInactive();
        ctx.channel().config().setAutoRead(false);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_REQUEST,
                Unpooled.copiedBuffer("400 Bad Request: pipelined proxy request\n",
                        CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                response.content().readableBytes());
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
