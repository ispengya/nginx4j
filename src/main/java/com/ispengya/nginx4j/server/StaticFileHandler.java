package com.ispengya.nginx4j.server;

import com.ispengya.nginx4j.conf.HttpBlock;
import com.ispengya.nginx4j.http.EntityTags;
import com.ispengya.nginx4j.http.HttpConstants;
import com.ispengya.nginx4j.http.RangeRequest;
import com.ispengya.nginx4j.router.ResolvedLocation;
import com.ispengya.nginx4j.router.Router;
import com.ispengya.nginx4j.util.HttpDates;
import com.ispengya.nginx4j.util.MimeTypes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves static files for the location the router selected.
 *
 * <p>Implements the nginx static-content semantics for this project's scope: GET/HEAD, the
 * {@code index} directive, directory redirect and {@code autoindex}, zero-copy body transfers,
 * {@code Range} requests (206/416), conditional requests (ETag, Last-Modified, If-Range) and
 * keep-alive with an inter-request idle timeout. From M2 on, {@code root}, {@code index},
 * {@code autoindex} and {@code keepalive_timeout} all come from the matched server/location, whose
 * inheritance was already resolved by the configuration parser.
 *
 * <p>One instance is created per channel by {@link HttpServerInitializer}; the immutable
 * {@link Router} is shared by every channel, and the frozen threading model (one thread per
 * EventLoop) is the only synchronization the timer field needs.
 */
public final class StaticFileHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = Logger.getLogger(StaticFileHandler.class.getName());
    private static final String PLAIN_TEXT_UTF8 = "text/plain; charset=utf-8";
    private static final String HTML_UTF8 = "text/html; charset=utf-8";

    private final Router router;
    private ScheduledFuture<?> keepAliveTimeout;

    public StaticFileHandler(Router router) {
        this.router = router;
    }

    /**
     * Convenience for tests and for the command line bootstrap path: one server with no locations,
     * so every request resolves to these values.
     *
     * @param keepAliveTimeoutSeconds idle keep-alive timeout, as on the M0/M1 command line
     */
    public StaticFileHandler(Path root, List<String> indexFiles, boolean autoIndex,
            int keepAliveTimeoutSeconds) {
        this(Router.of(HttpBlock.singleServer(0, root, indexFiles, autoIndex,
                keepAliveTimeoutSeconds * 1000L)));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpRequest)) {
            // Trailing content of a request that was already answered. It must not touch the
            // keep-alive timer: that clock belongs to the gap between two requests, and cancelling
            // it here would let an idle connection live forever.
            ReferenceCountUtil.release(msg);
            return;
        }
        // A new request arrived, so the connection is in use again.
        cancelKeepAliveTimeout();
        try {
            handleRequest(ctx, (HttpRequest) msg);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelKeepAliveTimeout();
        super.channelInactive(ctx);
    }

    private void handleRequest(ChannelHandlerContext ctx, HttpRequest request) {
        // Routing comes first: every reply, including an error reply, has to carry the keep-alive
        // timeout of the location that owns the request.
        ResolvedLocation location = router.route(request.headers().get(HttpHeaderNames.HOST),
                requestPath(request.uri()));

        if (!isGetOrHead(request)) {
            // An unread request body would corrupt the next request on this connection: always close.
            writeError(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, false, location);
            return;
        }

        final Path target;
        try {
            target = resolveTarget(location.root(), request.uri());
        } catch (InvalidPathException e) {
            writeError(ctx, request, HttpResponseStatus.BAD_REQUEST, HttpUtil.isKeepAlive(request),
                    location);
            return;
        }
        if (target == null || !Files.exists(target)) {
            writeError(ctx, request, HttpResponseStatus.NOT_FOUND, HttpUtil.isKeepAlive(request),
                    location);
            return;
        }

        if (Files.isDirectory(target)) {
            if (!requestPath(request.uri()).endsWith("/")) {
                writeDirectoryRedirect(ctx, request, location);
                return;
            }
            Path index = findIndexFile(target, location.indexFiles());
            if (index != null) {
                serveFile(ctx, request, index, location);
                return;
            }
            if (location.autoIndex()) {
                serveAutoIndex(ctx, request, target, location);
                return;
            }
            writeError(ctx, request, HttpResponseStatus.FORBIDDEN, HttpUtil.isKeepAlive(request),
                    location);
            return;
        }

        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            writeError(ctx, request, HttpResponseStatus.FORBIDDEN, HttpUtil.isKeepAlive(request),
                    location);
            return;
        }
        serveFile(ctx, request, target, location);
    }

    private void serveFile(ChannelHandlerContext ctx, HttpRequest request, Path file,
            ResolvedLocation location) {
        final long length;
        final long lastModifiedMillis;
        try {
            length = Files.size(file);
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot stat file: " + file, e);
            writeError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, false, location);
            return;
        }

        final String etag = EntityTags.of(lastModifiedMillis, length);
        final boolean keepAlive = HttpUtil.isKeepAlive(request);
        final long keepAliveTimeoutMillis = location.keepAliveTimeoutMillis();

        if (isNotModified(request, etag, lastModifiedMillis)) {
            // 304 carries validators only, no body and no Content-Length (same as nginx).
            HttpResponse notModified = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NOT_MODIFIED);
            setValidatorHeaders(notModified, etag, lastModifiedMillis);
            HttpUtil.setKeepAlive(notModified, keepAlive);
            ctx.write(notModified);
            completeResponse(ctx, ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), keepAlive,
                    keepAliveTimeoutMillis);
            return;
        }

        RangeRequest range = RangeRequest.parse(request.headers().get(HttpHeaderNames.RANGE), length);
        if (range.kind() == RangeRequest.Kind.UNSATISFIABLE) {
            ByteBuf content = Unpooled.copiedBuffer("416 Requested Range Not Satisfiable\n",
                    CharsetUtil.UTF_8);
            FullHttpResponse unsatisfiable = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE, content);
            unsatisfiable.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes */" + length);
            unsatisfiable.headers().set(HttpHeaderNames.CONTENT_TYPE, PLAIN_TEXT_UTF8);
            unsatisfiable.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
            setValidatorHeaders(unsatisfiable, etag, lastModifiedMillis);
            HttpUtil.setKeepAlive(unsatisfiable, keepAlive);
            completeResponse(ctx, ctx.writeAndFlush(unsatisfiable), keepAlive, keepAliveTimeoutMillis);
            return;
        }

        long start = 0;
        long contentLength = length;
        HttpResponseStatus status = HttpResponseStatus.OK;
        if (range.kind() == RangeRequest.Kind.SATISFIABLE
                && ifRangeAllows(request, etag, lastModifiedMillis)) {
            start = range.start();
            contentLength = range.length();
            status = HttpResponseStatus.PARTIAL_CONTENT;
        }

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        setValidatorHeaders(response, etag, lastModifiedMillis);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, MimeTypes.of(file));
        response.headers().set(HttpHeaderNames.ACCEPT_RANGES, "bytes");
        if (status == HttpResponseStatus.PARTIAL_CONTENT) {
            response.headers().set(HttpHeaderNames.CONTENT_RANGE, range.contentRange(length));
        }
        HttpUtil.setContentLength(response, contentLength);
        HttpUtil.setKeepAlive(response, keepAlive);
        ctx.write(response);

        if (!HttpMethod.HEAD.equals(request.method()) && contentLength > 0) {
            // Zero-copy: the kernel moves the bytes, the EventLoop never touches them.
            ctx.write(new DefaultFileRegion(file.toFile(), start, contentLength));
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("serving " + file + " status=" + status.code() + " start=" + start
                    + " length=" + contentLength + " location=" + location.locationPattern()
                    + " keepAlive=" + keepAlive);
        }
        completeResponse(ctx, ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), keepAlive,
                keepAliveTimeoutMillis);
    }

    private void serveAutoIndex(ChannelHandlerContext ctx, HttpRequest request, Path directory,
            ResolvedLocation location) {
        final String html;
        try {
            html = AutoIndexRenderer.render(directory, requestPath(request.uri()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot list directory: " + directory, e);
            writeError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, false, location);
            return;
        }
        ByteBuf content = Unpooled.copiedBuffer(html, CharsetUtil.UTF_8);
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.DATE, HttpDates.format(new Date()));
        response.headers().set(HttpHeaderNames.SERVER, HttpConstants.SERVER_NAME);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HTML_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        HttpUtil.setKeepAlive(response, keepAlive);
        completeResponse(ctx, ctx.writeAndFlush(response), keepAlive,
                location.keepAliveTimeoutMillis());
    }

    /**
     * Issues the 301 that nginx sends for a directory request missing its trailing slash; relative
     * links inside the index page would otherwise resolve against the parent path.
     */
    private void writeDirectoryRedirect(ChannelHandlerContext ctx, HttpRequest request,
            ResolvedLocation location) {
        String uri = request.uri();
        int queryIndex = uri.indexOf('?');
        String path = queryIndex < 0 ? uri : uri.substring(0, queryIndex);
        String query = queryIndex < 0 ? "" : uri.substring(queryIndex);

        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.MOVED_PERMANENTLY, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.DATE, HttpDates.format(new Date()));
        response.headers().set(HttpHeaderNames.SERVER, HttpConstants.SERVER_NAME);
        response.headers().set(HttpHeaderNames.LOCATION, path + "/" + query);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);
        completeResponse(ctx, ctx.writeAndFlush(response), keepAlive,
                location.keepAliveTimeoutMillis());
    }

    /**
     * @return the resolved path, or {@code null} when the URI escapes the root of the matched
     *         location; the router has already decided which root that is
     */
    private Path resolveTarget(Path root, String uri) {
        String rawPath = requestPath(uri);
        String relative = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        Path resolved = root.resolve(relative).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static String requestPath(String uri) {
        return new QueryStringDecoder(uri).path();
    }

    private Path findIndexFile(Path directory, List<String> indexFiles) {
        for (String name : indexFiles) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isGetOrHead(HttpRequest request) {
        return HttpMethod.GET.equals(request.method()) || HttpMethod.HEAD.equals(request.method());
    }

    /**
     * Evaluates the conditional request headers. {@code If-None-Match} wins over
     * {@code If-Modified-Since} when both are present (RFC 7232 section 3.3).
     */
    private static boolean isNotModified(HttpRequest request, String etag, long lastModifiedMillis) {
        String ifNoneMatch = request.headers().get(HttpHeaderNames.IF_NONE_MATCH);
        if (ifNoneMatch != null) {
            return EntityTags.ifNoneMatchMatches(ifNoneMatch, etag);
        }
        String ifModifiedSince = request.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null) {
            Date since = HttpDates.parse(ifModifiedSince);
            // HTTP dates have one-second resolution, so compare truncated to seconds.
            return since != null && lastModifiedMillis / 1000L * 1000L <= since.getTime();
        }
        return false;
    }

    /**
     * @return whether a {@code Range} may be honoured; a failed {@code If-Range} validator downgrades
     *         the response to a full 200
     */
    private static boolean ifRangeAllows(HttpRequest request, String etag, long lastModifiedMillis) {
        String ifRange = request.headers().get(HttpHeaderNames.IF_RANGE);
        if (ifRange == null) {
            return true;
        }
        String value = ifRange.trim();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return EntityTags.tagMatches(value, etag);
        }
        Date date = HttpDates.parse(value);
        return date != null && lastModifiedMillis / 1000L * 1000L <= date.getTime();
    }

    private static void setValidatorHeaders(HttpMessage message, String etag, long lastModifiedMillis) {
        message.headers().set(HttpHeaderNames.DATE, HttpDates.format(new Date()));
        message.headers().set(HttpHeaderNames.SERVER, HttpConstants.SERVER_NAME);
        message.headers().set(HttpHeaderNames.LAST_MODIFIED,
                HttpDates.format(new Date(lastModifiedMillis)));
        message.headers().set(HttpHeaderNames.ETAG, etag);
    }

    /**
     * Starts the keep-alive idle timer once a response has been written. Like nginx's
     * {@code keepalive_timeout}, which may be set per location, the clock only runs between
     * requests, so a slow download is never killed mid-transfer.
     */
    private void scheduleKeepAliveTimeout(final ChannelHandlerContext ctx, long keepAliveTimeoutMillis) {
        keepAliveTimeout = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("keep-alive timeout, closing " + ctx.channel().remoteAddress());
                }
                ctx.close();
            }
        }, keepAliveTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelKeepAliveTimeout() {
        if (keepAliveTimeout != null) {
            keepAliveTimeout.cancel(false);
            keepAliveTimeout = null;
        }
    }

    private void completeResponse(ChannelHandlerContext ctx, ChannelFuture lastWrite,
            boolean keepAlive, long keepAliveTimeoutMillis) {
        final long timeoutMillis = keepAliveTimeoutMillis;
        lastWrite.addListener((ChannelFuture future) -> {
            if (!future.isSuccess()) {
                LOG.log(Level.FINE, "response write failed: " + ctx.channel().remoteAddress(),
                        future.cause());
                return;
            }
            if (keepAlive) {
                scheduleKeepAliveTimeout(ctx, timeoutMillis);
            }
        });
    }

    private void writeError(ChannelHandlerContext ctx, HttpRequest request, HttpResponseStatus status,
            boolean keepAlive, ResolvedLocation location) {
        ByteBuf content = Unpooled.copiedBuffer(status.code() + " " + status.reasonPhrase() + "\n",
                CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.DATE, HttpDates.format(new Date()));
        response.headers().set(HttpHeaderNames.SERVER, HttpConstants.SERVER_NAME);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, PLAIN_TEXT_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        HttpUtil.setKeepAlive(response, keepAlive);
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("responding " + status.code() + " to " + ctx.channel().remoteAddress()
                    + " for " + request.uri() + " location=" + location.locationPattern());
        }
        completeResponse(ctx, ctx.writeAndFlush(response), keepAlive,
                location.keepAliveTimeoutMillis());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException) {
            // Clients aborting downloads are routine, not an error worth a stack trace above FINE.
            LOG.log(Level.FINE, "connection error: " + ctx.channel().remoteAddress(), cause);
        } else {
            LOG.log(Level.WARNING, "unexpected error: " + ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }
}