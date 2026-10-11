package com.ispengya.nginx4j.server;

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
 */
public final class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final ServerOptions options;

    public HttpServerInitializer(ServerOptions options) {
        this.options = options;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("http-codec", new HttpServerCodec());
        pipeline.addLast("keep-alive", new HttpServerKeepAliveHandler());
        pipeline.addLast("chunked-writer", new ChunkedWriteHandler());
        pipeline.addLast("static-file", new StaticFileHandler(options.root(), options.indexFiles(),
                options.autoIndex(), options.keepAliveTimeoutSeconds()));
    }
}