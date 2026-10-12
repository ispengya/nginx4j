package com.ispengya.nginx4j.server;

import com.ispengya.nginx4j.router.Router;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * Builds the front-end pipeline: HTTP codec, keep-alive governance, then the content handler.
 *
 * <p>Bodies are deliberately never aggregated (see PLAN.md decision 2): the static handler answers
 * as soon as the request head is parsed. The chunked writer is already in place for large outbound
 * streams and for the TLS fallback path that replaces zero-copy file transfers in M5.
 *
 * <p>{@code HttpServerKeepAliveHandler} only implements the {@code Connection} header semantics here;
 * the inter-request idle timeout is owned by the content handler, which starts its clock once a
 * response has been written.
 *
 * <p>The router is built once per server and shared by every channel: it is immutable, and its
 * compiled patterns are safe to use from several EventLoops.
 */
public final class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final Router router;

    public HttpServerInitializer(ServerOptions options) {
        this.router = Router.of(options.http());
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("http-codec", new HttpServerCodec());
        pipeline.addLast("keep-alive", new HttpServerKeepAliveHandler());
        pipeline.addLast("chunked-writer", new ChunkedWriteHandler());
        pipeline.addLast("static-file", new StaticFileHandler(router));
    }
}