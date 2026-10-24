package com.ispengya.nginx4j.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * The single owner of the inter-request keep-alive clock (PLAN.md M3-D1).
 *
 * <p>The clock only runs between requests: any inbound message cancels it, and the content path
 * starts it again after a complete response has been written. Static and proxied requests share this
 * handler, so a connection cannot end up with two independent timers that never see each other.
 *
 * <p>When the pipeline has no instance (for example an {@link io.netty.channel.embedded.EmbeddedChannel}
 * running the static handler directly), {@link #startKeepAliveTimeout} is a no-op.
 */
public final class IdleTimeoutHandler extends ChannelInboundHandlerAdapter {

    private ChannelHandlerContext context;
    private ScheduledFuture<?> timeout;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.context = ctx;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancel();
        this.context = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Only a new request head means the connection is in use again. The trailing
        // LastHttpContent of the request that was just answered arrives after the response and must
        // not cancel the clock that was started for the next gap.
        if (msg instanceof HttpRequest) {
            cancel();
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancel();
        super.channelInactive(ctx);
    }

    /**
     * Starts (or restarts) the idle clock after a response has been fully written.
     *
     * @param millis per-location {@code keepalive_timeout}; zero or negative disables the clock
     */
    public static void startKeepAliveTimeout(ChannelHandlerContext ctx, long millis) {
        IdleTimeoutHandler handler = ctx.pipeline().get(IdleTimeoutHandler.class);
        if (handler != null) {
            handler.schedule(millis);
        }
    }

    private void schedule(long millis) {
        cancel();
        if (context == null || millis <= 0) {
            return;
        }
        timeout = context.executor().schedule(new Runnable() {
            @Override
            public void run() {
                if (context.channel().isActive()) {
                    context.close();
                }
            }
        }, millis, TimeUnit.MILLISECONDS);
    }

    private void cancel() {
        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
    }
}
