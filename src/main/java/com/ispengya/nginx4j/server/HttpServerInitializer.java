package com.ispengya.nginx4j.server;

import com.ispengya.nginx4j.conf.HttpBlock;
import com.ispengya.nginx4j.proxy.LoadBalancer;
import com.ispengya.nginx4j.router.Router;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * Builds the front-end pipeline: HTTP codec, keep-alive governance, the shared idle clock, then the
 * routing dispatcher.
 *
 * <p>Bodies are deliberately never aggregated (see PLAN.md decision 2): static requests answer as
 * soon as the request head is parsed, and proxied bodies are streamed chunk by chunk. The chunked
 * writer remains in place for large zero-copy/non-zero-copy streams.
 *
 * <p>{@code HttpServerKeepAliveHandler} only implements the {@code Connection} header semantics;
 * the inter-request idle timeout is owned by {@link IdleTimeoutHandler}, which both the static and
 * proxy paths feed after a complete response.
 *
 * <p>The router, config tree and load balancer are built once per server and shared by every
 * channel: they are immutable (the balancer keeps only per-EventLoop cursors) and safe across
 * EventLoops.
 */
public final class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final HttpBlock http;
    private final Router router;
    private final LoadBalancer loadBalancer;

    public HttpServerInitializer(ServerOptions options) {
        this.http = options.http();
        this.router = Router.of(http);
        this.loadBalancer = new LoadBalancer();
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("http-codec", new HttpServerCodec());
        pipeline.addLast("keep-alive", new HttpServerKeepAliveHandler());
        pipeline.addLast("idle-timeout", new IdleTimeoutHandler());
        pipeline.addLast("chunked-writer", new ChunkedWriteHandler());
        pipeline.addLast("dispatcher", new RequestDispatcher(router, http, loadBalancer));
    }
}
