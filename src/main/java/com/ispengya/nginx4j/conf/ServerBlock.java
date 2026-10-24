package com.ispengya.nginx4j.conf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code server} block with inheritance already folded in.
 *
 * <p>Inheritance follows nginx: {@code root}, {@code index}, {@code autoindex},
 * {@code keepalive_timeout}, {@code proxy_set_header} and the three {@code proxy_*} timeouts may be
 * set in the {@code http} block and are overridden per server, or per location. The parser resolves
 * that chain once, so a {@code ServerBlock} always answers with final values.
 */
public final class ServerBlock {

    /** nginx's conventional "this server answers anything" name. */
    private static final String CATCH_ALL_NAME = "_";
    /** nginx's default for {@code proxy_connect_timeout}, {@code proxy_read_timeout} and
     *  {@code proxy_send_timeout}. */
    public static final long DEFAULT_PROXY_TIMEOUT_MILLIS = 60000L;

    private final List<ListenEndpoint> listeners;
    private final List<String> serverNames;
    private final Path root;
    private final List<String> indexFiles;
    private final boolean autoIndex;
    private final long keepAliveTimeoutMillis;
    private final Map<String, String> proxySetHeaders;
    private final long proxyConnectTimeoutMillis;
    private final long proxyReadTimeoutMillis;
    private final long proxySendTimeoutMillis;
    private final List<LocationBlock> locations;

    /**
     * Builds a server without proxy settings, the M0-M2 shape used by the command line and by
     * tests that do not exercise proxying.
     */
    public ServerBlock(List<ListenEndpoint> listeners, List<String> serverNames, Path root,
            List<String> indexFiles, boolean autoIndex, long keepAliveTimeoutMillis,
            List<LocationBlock> locations) {
        this(listeners, serverNames, root, indexFiles, autoIndex, keepAliveTimeoutMillis,
                Collections.<String, String>emptyMap(), DEFAULT_PROXY_TIMEOUT_MILLIS,
                DEFAULT_PROXY_TIMEOUT_MILLIS, DEFAULT_PROXY_TIMEOUT_MILLIS, locations);
    }

    /**
     * @param proxySetHeaders effective {@code proxy_set_header} values after inheritance, in
     *        configuration order; an empty value means "remove this header"
     */
    public ServerBlock(List<ListenEndpoint> listeners, List<String> serverNames, Path root,
            List<String> indexFiles, boolean autoIndex, long keepAliveTimeoutMillis,
            Map<String, String> proxySetHeaders, long proxyConnectTimeoutMillis,
            long proxyReadTimeoutMillis, long proxySendTimeoutMillis,
            List<LocationBlock> locations) {
        this.listeners = Collections.unmodifiableList(new ArrayList<ListenEndpoint>(listeners));
        this.serverNames = Collections.unmodifiableList(new ArrayList<String>(serverNames));
        this.root = root;
        this.indexFiles = Collections.unmodifiableList(new ArrayList<String>(indexFiles));
        this.autoIndex = autoIndex;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
        this.proxySetHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(proxySetHeaders));
        this.proxyConnectTimeoutMillis = proxyConnectTimeoutMillis;
        this.proxyReadTimeoutMillis = proxyReadTimeoutMillis;
        this.proxySendTimeoutMillis = proxySendTimeoutMillis;
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

    /** @return effective {@code proxy_set_header} values, merged http → server by name */
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