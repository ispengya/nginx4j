package com.ispengya.nginx4j.server;

import com.ispengya.nginx4j.conf.ServerBlock;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Front-end assembly of the frozen threading model (PLAN.md section 3).
 *
 * <p>boss holds a single accept-only thread; every worker EventLoop is the equivalent of one
 * single-threaded nginx worker. Backend connections created in M3 must be pinned to the EventLoop
 * of their client channel so that both ends of a proxied request share one thread.
 *
 * <p>Lifecycle is split into {@link #start()}, {@link #awaitShutdown()} and {@link #stop()} so that
 * tests can run a real server on an ephemeral port.
 */
public final class HttpServer {

    private static final Logger LOG = Logger.getLogger(HttpServer.class.getName());
    private static final int BOSS_THREADS = 1;
    private static final int SO_BACKLOG = 1024;
    private static final int SHUTDOWN_QUIET_PERIOD_SECONDS = 0;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ServerOptions options;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public HttpServer(ServerOptions options) {
        this.options = options;
    }

    /**
     * Binds the listen port and returns immediately.
     *
     * @throws IllegalStateException if binding is interrupted or fails
     */
    public void start() {
        bossGroup = new NioEventLoopGroup(BOSS_THREADS, new DefaultThreadFactory("nginx4j-boss"));
        workerGroup = new NioEventLoopGroup(options.workerThreads(),
                new DefaultThreadFactory("nginx4j-worker"));
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, SO_BACKLOG)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new HttpServerInitializer(options));
            serverChannel = bootstrap.bind(bindAddress()).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("interrupted while binding port " + options.port(), e);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
        logStartup();
    }

    /**
     * Blocks until the server channel is closed, then releases all resources.
     */
    public void awaitShutdown() {
        try {
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stop();
        }
    }

    /**
     * Convenience for {@link com.ispengya.nginx4j.Main}: start, then block until shutdown.
     */
    public void startAndBlock() {
        start();
        awaitShutdown();
    }

    /**
     * @return the bound port, which differs from the configured port when 0 was requested
     * @throws IllegalStateException if the server has not been started
     */
    public int port() {
        if (serverChannel == null) {
            throw new IllegalStateException("server is not started");
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /**
     * Closes the listen socket and shuts both groups down gracefully. Idempotent: the shutdown hook
     * and the main thread may race here.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        LOG.info("nginx4j stopping");
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        }
        LOG.info("nginx4j stopped");
    }

    private InetSocketAddress bindAddress() {
        return options.host() == null
                ? new InetSocketAddress(options.port())
                : new InetSocketAddress(options.host(), options.port());
    }

    private void logStartup() {
        LOG.info(String.format(
                "nginx4j listening%n  conf       : %s%n  listen     : %s%n  boss       : %d thread%n"
                        + "  worker     : %d EventLoop (availableProcessors=%d)%n  servers    : %s",
                options.confFile() == null ? "(command line)" : options.confFile().toString(),
                (options.host() == null ? "*" : options.host()) + ":" + port(), BOSS_THREADS,
                options.workerThreads(), Runtime.getRuntime().availableProcessors(),
                describeServers()));
    }

    /** One line per virtual server, so the startup log shows what was actually configured. */
    private String describeServers() {
        StringBuilder text = new StringBuilder();
        for (ServerBlock server : options.http().servers()) {
            if (text.length() > 0) {
                text.append(System.lineSeparator()).append("               ");
            }
            text.append(server.describeNames()).append(" -> ").append(server.root())
                    .append(" (").append(server.locations().size()).append(" location)")
                    .append(server.isDefaultServer() ? " [default_server]" : "");
        }
        return text.toString();
    }
}