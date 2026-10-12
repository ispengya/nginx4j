package com.ispengya.nginx4j.conf;

/**
 * One {@code listen} directive of the supported subset.
 *
 * <p>M2 binds a single address for the whole configuration, so every server must declare the same
 * endpoint; the parser enforces that and reports the conflict otherwise. {@code host} is
 * {@code null} for wildcard binds, which covers {@code listen 8080}, {@code listen *:80},
 * {@code listen 0.0.0.0:80} and {@code listen [::]:80}.
 */
public final class ListenEndpoint {

    private final String host;
    private final int port;
    private final boolean defaultServer;

    public ListenEndpoint(String host, int port, boolean defaultServer) {
        this.host = host;
        this.port = port;
        this.defaultServer = defaultServer;
    }

    /** @return the bind address, or {@code null} meaning every local interface */
    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean defaultServer() {
        return defaultServer;
    }

    /**
     * @return whether both endpoints denote the same socket address; the {@code default_server}
     *         flag is excluded because it is a server attribute, not part of the bind
     */
    public boolean sameAddressAs(ListenEndpoint other) {
        if (port != other.port) {
            return false;
        }
        return host == null ? other.host == null : host.equals(other.host);
    }

    @Override
    public String toString() {
        return (host == null ? "*" : host) + ":" + port + (defaultServer ? " default_server" : "");
    }
}