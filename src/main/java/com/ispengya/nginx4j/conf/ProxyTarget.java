package com.ispengya.nginx4j.conf;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The already-resolved target of one {@code proxy_pass} directive.
 *
 * <p>M3 only speaks plain HTTP, so {@link #scheme()} is always {@code http}. A target either points
 * at a literal host (with its addresses resolved at configuration time, PLAN.md M3-D3) or at an
 * {@code upstream} block by name, in which case {@link #upstreamName()} is non-null and the
 * addresses live in each {@link UpstreamServer}.
 *
 * <p>Immutable and safe to share across EventLoops.
 */
public final class ProxyTarget {

    /** nginx's default port for an upstream URL without an explicit port. */
    public static final int DEFAULT_HTTP_PORT = 80;

    private static final String HTTP_SCHEME = "http";

    private final String scheme;
    private final String host;
    private final int port;
    private final String upstreamName;
    private final List<InetAddress> addresses;

    private ProxyTarget(String scheme, String host, int port, String upstreamName,
            List<InetAddress> addresses) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.upstreamName = upstreamName;
        this.addresses = Collections.unmodifiableList(new ArrayList<InetAddress>(addresses));
    }

    /**
     * Creates a target for {@code proxy_pass http://host[:port]}.
     *
     * @param host host exactly as written without IPv6 brackets
     * @param port explicit or defaulted port
     * @param addresses addresses resolved at configuration time; must not be empty
     */
    public static ProxyTarget direct(String host, int port, List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("a direct proxy target needs at least one address");
        }
        return new ProxyTarget(HTTP_SCHEME, host, port, null, addresses);
    }

    /**
     * Creates a target that names an {@code upstream} block.
     *
     * @param name upstream name, unique within the http block
     */
    public static ProxyTarget upstream(String name) {
        return new ProxyTarget(HTTP_SCHEME, name, DEFAULT_HTTP_PORT, name,
                Collections.<InetAddress>emptyList());
    }

    public String scheme() {
        return scheme;
    }

    /** @return the literal host, or the upstream name for an upstream reference */
    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** @return the referenced upstream name, or {@code null} for a literal host */
    public String upstreamName() {
        return upstreamName;
    }

    public boolean isUpstream() {
        return upstreamName != null;
    }

    /**
     * @return the literal host's addresses resolved at configuration time; empty for an upstream
     *         reference, whose addresses live in the upstream block
     */
    public List<InetAddress> addresses() {
        return addresses;
    }

    /** @return a human readable form for startup logs */
    public String describe() {
        if (isUpstream()) {
            return "upstream " + upstreamName;
        }
        String literal = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return scheme + "://" + literal + ":" + port;
    }

    @Override
    public String toString() {
        return describe();
    }
}
