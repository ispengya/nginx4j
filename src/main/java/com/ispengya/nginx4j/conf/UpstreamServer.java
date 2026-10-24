package com.ispengya.nginx4j.conf;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One {@code server} inside an {@code upstream} block.
 *
 * <p>The host is resolved when the configuration is parsed (PLAN.md M3-D3), so serving a request
 * never performs a DNS lookup. {@code weight} participates in the smooth weighted round-robin
 * selection implemented by the proxy layer.
 *
 * <p>Immutable and safe to share across EventLoops.
 */
public final class UpstreamServer {

    private final String host;
    private final int port;
    private final int weight;
    private final List<InetAddress> addresses;

    /**
     * @param host host as written in the configuration, without IPv6 brackets
     * @param port port from the address, or nginx's default of 80
     * @param weight positive weight, 1 when {@code weight=} was absent
     * @param addresses addresses resolved at configuration time; must not be empty
     */
    public UpstreamServer(String host, int port, int weight, List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("upstream server needs at least one address");
        }
        this.host = host;
        this.port = port;
        this.weight = weight;
        this.addresses = Collections.unmodifiableList(new ArrayList<InetAddress>(addresses));
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public int weight() {
        return weight;
    }

    public List<InetAddress> addresses() {
        return addresses;
    }

    @Override
    public String toString() {
        return host + ":" + port + " weight=" + weight;
    }
}
