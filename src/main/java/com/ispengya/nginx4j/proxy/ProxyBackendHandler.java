package com.ispengya.nginx4j.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.util.ReferenceCountUtil;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The upstream side of one proxy exchange: re-frames response heads, pumps chunks to the client
 * without aggregating, and reports timeouts or an early close back to the frontend.
 *
 * <p>The pipeline also carries {@code IdleStateHandler} (read timeout) and, when enabled,
 * {@code WriteTimeoutHandler} (send timeout); this handler turns their events into 504s.
 */
final class ProxyBackendHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = Logger.getLogger(ProxyBackendHandler.class.getName());

    private final ProxyTransaction transaction;
    private final ProxyFrontendHandler frontend;
    /** True while forwarding an informational (1xx) response split across several messages. */
    private boolean informational;

    ProxyBackendHandler(ProxyTransaction transaction, ProxyFrontendHandler frontend) {
        this.transaction = transaction;
        this.frontend = frontend;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            ProxyResponseRewriter.rewrite(response);
            int status = response.status().code();
            if (status >= 100 && status < 200) {
                // 100-continue and other informational responses end with their own
                // LastHttpContent and must not start or finish the real exchange.
                transaction.frontend().writeAndFlush(response);
                informational = !(msg instanceof LastHttpContent);
                return;
            }
            transaction.markResponseStarted();
            if (msg instanceof LastHttpContent) {
                frontend.onBackendComplete(transaction, (LastHttpContent) msg);
            } else {
                transaction.frontend().writeAndFlush(msg);
            }
            return;
        }
        if (msg instanceof HttpContent) {
            if (informational) {
                transaction.frontend().writeAndFlush(msg);
                if (msg instanceof LastHttpContent) {
                    informational = false;
                }
                return;
            }
            if (msg instanceof LastHttpContent) {
                frontend.onBackendComplete(transaction, (LastHttpContent) msg);
            } else {
                transaction.frontend().writeAndFlush(msg);
            }
            return;
        }
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent
                && ((IdleStateEvent) event).state() == IdleState.READER_IDLE) {
            LOG.fine("upstream read timeout, closing " + ctx.channel().remoteAddress());
            frontend.onBackendTimeout(transaction);
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof WriteTimeoutException) {
            LOG.log(Level.FINE, "upstream send timeout " + ctx.channel().remoteAddress(), cause);
            frontend.onBackendTimeout(transaction);
        } else {
            LOG.log(Level.FINE, "upstream failure " + ctx.channel().remoteAddress(), cause);
            frontend.onBackendFailure(transaction, cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        frontend.onBackendClosed(transaction);
        super.channelInactive(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        frontend.onBackendWritabilityChanged(transaction);
        super.channelWritabilityChanged(ctx);
    }
}
