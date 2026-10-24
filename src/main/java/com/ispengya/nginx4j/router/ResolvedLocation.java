package com.ispengya.nginx4j.router;

import com.ispengya.nginx4j.conf.LocationBlock;
import com.ispengya.nginx4j.conf.ProxyTarget;
import com.ispengya.nginx4j.conf.ServerBlock;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The effective configuration of one request after routing.
 *
 * <p>Produced once per request by {@link Router}. All inheritance has already been folded into the
 * underlying blocks at parse time (PLAN.md section 8.2), so every accessor here is a plain read of a
 * final value: the content handler never resolves a parent chain.
 *
 * <p>Immutable; one instance may be handed to any EventLoop.
 */
public final class ResolvedLocation {

    private final ServerBlock server;
    private final LocationBlock location;
    private final Path root;
    private final List<String> indexFiles;
    private final boolean autoIndex;
    private final long keepAliveTimeoutMillis;
    private final ProxyTarget proxyTarget;
    private final Map<String, String> proxySetHeaders;
    private final long proxyConnectTimeoutMillis;
    private final long proxyReadTimeoutMillis;
    private final long proxySendTimeoutMillis;

    /**
     * Builds a static resolution, used by tests that do not exercise proxying.
     */
    ResolvedLocation(ServerBlock server, LocationBlock location, Path root, List<String> indexFiles,
            boolean autoIndex, long keepAliveTimeoutMillis) {
        this(server, location, root, indexFiles, autoIndex, keepAliveTimeoutMillis, null,
                Collections.<String, String>emptyMap(), ServerBlock.DEFAULT_PROXY_TIMEOUT_MILLIS,
                ServerBlock.DEFAULT_PROXY_TIMEOUT_MILLIS, ServerBlock.DEFAULT_PROXY_TIMEOUT_MILLIS);
    }

    ResolvedLocation(ServerBlock server, LocationBlock location, Path root, List<String> indexFiles,
            boolean autoIndex, long keepAliveTimeoutMillis, ProxyTarget proxyTarget,
            Map<String, String> proxySetHeaders, long proxyConnectTimeoutMillis,
            long proxyReadTimeoutMillis, long proxySendTimeoutMillis) {
        this.server = server;
        this.location = location;
        this.root = root;
        this.indexFiles = indexFiles;
        this.autoIndex = autoIndex;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
        this.proxyTarget = proxyTarget;
        this.proxySetHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(proxySetHeaders));
        this.proxyConnectTimeoutMillis = proxyConnectTimeoutMillis;
        this.proxyReadTimeoutMillis = proxyReadTimeoutMillis;
        this.proxySendTimeoutMillis = proxySendTimeoutMillis;
    }

    /** @return the virtual server that owns the request */
    public ServerBlock server() {
        return server;
    }

    /** @return the matched location, or {@code null} when the server level defaults applied */
    public LocationBlock location() {
        return location;
    }

    /** @return the matched location pattern, or {@code null} when no location matched */
    public String locationPattern() {
        return location == null ? null : location.pattern();
    }

    public Path root() {
        return root;
    }

    public List<String> indexFiles() {
        return indexFiles;
    }

    public boolean autoIndex() {
        return autoIndex;
    }

    public long keepAliveTimeoutMillis() {
        return keepAliveTimeoutMillis;
    }

    /** @return the proxy target, or {@code null} when this request is served from disk */
    public ProxyTarget proxyTarget() {
        return proxyTarget;
    }

    /** @return whether this request must be forwarded instead of served from disk */
    public boolean isProxy() {
        return proxyTarget != null;
    }

    /** @return effective {@code proxy_set_header} values, merged http → server → location by name */
    public Map<String, String> proxySetHeaders() {
        return proxySetHeaders;
    }

    public long proxyConnectTimeoutMillis() {
        return proxyConnectTimeoutMillis;
    }

    public long proxyReadTimeoutMillis() {
        return proxyReadTimeoutMillis;
    }

    public long proxySendTimeoutMillis() {
        return proxySendTimeoutMillis;
    }

    @Override
    public String toString() {
        return "server[" + server.describeNames() + "] location["
                + (location == null ? "server defaults" : location.toString()) + "] root=" + root;
    }
}