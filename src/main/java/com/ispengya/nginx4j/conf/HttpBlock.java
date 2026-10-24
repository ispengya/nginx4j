package com.ispengya.nginx4j.conf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code http} block: every virtual server that shares one bound address plus the named
 * upstream groups those servers may proxy to.
 *
 * <p>Values are not stored here because inheritance is folded into each {@link ServerBlock} at parse
 * time (PLAN.md section 8.2). What remains is the endpoint the server binds, the upstream groups and
 * the server list in configuration order, which is also the tie-breaker order for default-server
 * selection.
 */
public final class HttpBlock {

    private final ListenEndpoint listen;
    private final List<UpstreamBlock> upstreams;
    private final Map<String, UpstreamBlock> upstreamsByName;
    private final List<ServerBlock> servers;

    /**
     * Builds an http block without upstream groups, the M0-M2 shape used by the command line and by
     * tests that do not exercise proxying.
     */
    public HttpBlock(ListenEndpoint listen, List<ServerBlock> servers) {
        this(listen, Collections.<UpstreamBlock>emptyList(), servers);
    }

    public HttpBlock(ListenEndpoint listen, List<UpstreamBlock> upstreams,
            List<ServerBlock> servers) {
        this.listen = listen;
        this.upstreams = Collections.unmodifiableList(new ArrayList<UpstreamBlock>(upstreams));
        Map<String, UpstreamBlock> lookup = new LinkedHashMap<String, UpstreamBlock>();
        for (UpstreamBlock upstream : upstreams) {
            lookup.put(upstream.name(), upstream);
        }
        this.upstreamsByName = Collections.unmodifiableMap(lookup);
        this.servers = Collections.unmodifiableList(new ArrayList<ServerBlock>(servers));
    }

    /** @return the single bound endpoint; M2 rejects configurations that need more than one */
    public ListenEndpoint listen() {
        return listen;
    }

    public List<ServerBlock> servers() {
        return servers;
    }

    /** @return the declared upstream groups in configuration order */
    public List<UpstreamBlock> upstreams() {
        return upstreams;
    }

    /**
     * Looks up an upstream group by name.
     *
     * @param name upstream name as written in {@code proxy_pass http://NAME}
     * @return the group, or {@code null} when no such upstream was declared
     */
    public UpstreamBlock upstream(String name) {
        return upstreamsByName.get(name);
    }

    /**
     * @return the server that answers a request no {@code server_name} matches: an explicit
     *         {@code default_server} first, otherwise the first catch-all server, otherwise the
     *         first server in configuration order
     */
    public ServerBlock defaultServer() {
        for (ServerBlock server : servers) {
            if (server.isDefaultServer()) {
                return server;
            }
        }
        for (ServerBlock server : servers) {
            if (server.isCatchAll()) {
                return server;
            }
        }
        return servers.get(0);
    }

    /**
     * Builds the one-server configuration used when the server is started from command line options
     * instead of a configuration file (M0/M1 behaviour, kept for tests and quick runs).
     *
     * @param keepAliveTimeoutMillis idle keep-alive timeout in milliseconds
     */
    public static HttpBlock singleServer(int port, Path root, List<String> indexFiles,
            boolean autoIndex, long keepAliveTimeoutMillis) {
        ListenEndpoint endpoint = new ListenEndpoint(null, port, true);
        ServerBlock server = new ServerBlock(Collections.singletonList(endpoint),
                Collections.<String>emptyList(), root, indexFiles, autoIndex, keepAliveTimeoutMillis,
                Collections.<LocationBlock>emptyList());
        return new HttpBlock(endpoint, Collections.singletonList(server));
    }
}