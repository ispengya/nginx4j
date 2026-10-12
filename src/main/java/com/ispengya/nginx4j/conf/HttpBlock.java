package com.ispengya.nginx4j.conf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code http} block: every virtual server that shares one bound address.
 *
 * <p>Values are not stored here because inheritance is folded into each {@link ServerBlock} at parse
 * time (PLAN.md section 8.2). What remains is the endpoint the server binds and the server list in
 * configuration order, which is also the tie-breaker order for default-server selection.
 */
public final class HttpBlock {

    private final ListenEndpoint listen;
    private final List<ServerBlock> servers;

    public HttpBlock(ListenEndpoint listen, List<ServerBlock> servers) {
        this.listen = listen;
        this.servers = Collections.unmodifiableList(new ArrayList<ServerBlock>(servers));
    }

    /** @return the single bound endpoint; M2 rejects configurations that need more than one */
    public ListenEndpoint listen() {
        return listen;
    }

    public List<ServerBlock> servers() {
        return servers;
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