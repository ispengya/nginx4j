package com.ispengya.nginx4j.conf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One {@code server} block with inheritance already folded in.
 *
 * <p>Inheritance follows nginx: {@code root}, {@code index}, {@code autoindex} and
 * {@code keepalive_timeout} may be set in the {@code http} block and are overridden per server, or
 * per location. The parser resolves that chain once, so a {@code ServerBlock} always answers with
 * final values.
 */
public final class ServerBlock {

    /** nginx's conventional "this server answers anything" name. */
    private static final String CATCH_ALL_NAME = "_";

    private final List<ListenEndpoint> listeners;
    private final List<String> serverNames;
    private final Path root;
    private final List<String> indexFiles;
    private final boolean autoIndex;
    private final long keepAliveTimeoutMillis;
    private final List<LocationBlock> locations;

    public ServerBlock(List<ListenEndpoint> listeners, List<String> serverNames, Path root,
            List<String> indexFiles, boolean autoIndex, long keepAliveTimeoutMillis,
            List<LocationBlock> locations) {
        this.listeners = Collections.unmodifiableList(new ArrayList<ListenEndpoint>(listeners));
        this.serverNames = Collections.unmodifiableList(new ArrayList<String>(serverNames));
        this.root = root;
        this.indexFiles = Collections.unmodifiableList(new ArrayList<String>(indexFiles));
        this.autoIndex = autoIndex;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
        this.locations = Collections.unmodifiableList(new ArrayList<LocationBlock>(locations));
    }

    public List<ListenEndpoint> listeners() {
        return listeners;
    }

    /** @return the declared names in configuration order; empty when {@code server_name} was absent */
    public List<String> serverNames() {
        return serverNames;
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

    public List<LocationBlock> locations() {
        return locations;
    }

    public boolean isDefaultServer() {
        for (ListenEndpoint endpoint : listeners) {
            if (endpoint.defaultServer()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether this server accepts any {@code Host} without a name match. A server is a
     *         catch-all when it declares {@code server_name _} or omits {@code server_name}
     *         entirely, which is what a single-server configuration means in practice.
     */
    public boolean isCatchAll() {
        return serverNames.isEmpty() || serverNames.contains(CATCH_ALL_NAME);
    }

    /** @return a comma separated name list for logs, {@code (any)} when the server is a catch-all */
    public String describeNames() {
        return serverNames.isEmpty() ? "(any)" : String.join(",", serverNames);
    }

    @Override
    public String toString() {
        return "server " + describeNames() + " -> " + root;
    }
}