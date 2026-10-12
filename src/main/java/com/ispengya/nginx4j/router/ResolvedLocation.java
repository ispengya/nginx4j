package com.ispengya.nginx4j.router;

import com.ispengya.nginx4j.conf.LocationBlock;
import com.ispengya.nginx4j.conf.ServerBlock;

import java.nio.file.Path;
import java.util.List;

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

    ResolvedLocation(ServerBlock server, LocationBlock location, Path root, List<String> indexFiles,
            boolean autoIndex, long keepAliveTimeoutMillis) {
        this.server = server;
        this.location = location;
        this.root = root;
        this.indexFiles = indexFiles;
        this.autoIndex = autoIndex;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
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

    @Override
    public String toString() {
        return "server[" + server.describeNames() + "] location["
                + (location == null ? "server defaults" : location.toString()) + "] root=" + root;
    }
}