package com.ispengya.nginx4j.conf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One named {@code upstream} block from the {@code http} context.
 *
 * <p>The name is unique within the http block and is referenced by
 * {@code proxy_pass http://NAME}. At least one server is required, mirroring nginx's rejection of
 * an empty upstream.
 *
 * <p>Immutable and safe to share across EventLoops.
 */
public final class UpstreamBlock {

    private final String name;
    private final List<UpstreamServer> servers;

    public UpstreamBlock(String name, List<UpstreamServer> servers) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("upstream name must not be empty");
        }
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("upstream " + name + " needs at least one server");
        }
        this.name = name;
        this.servers = Collections.unmodifiableList(new ArrayList<UpstreamServer>(servers));
    }

    public String name() {
        return name;
    }

    public List<UpstreamServer> servers() {
        return servers;
    }

    @Override
    public String toString() {
        return "upstream " + name + " " + servers;
    }
}
