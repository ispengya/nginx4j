package com.ispengya.nginx4j.router;

import com.ispengya.nginx4j.conf.HttpBlock;
import com.ispengya.nginx4j.conf.LocationBlock;
import com.ispengya.nginx4j.conf.LocationModifier;
import com.ispengya.nginx4j.conf.ServerBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stateless request router: picks a virtual server from the {@code Host} header, then a location
 * inside it, following nginx priorities exactly (PLAN.md section 8.3).
 *
 * <p>Server selection order: exact name, longest {@code *.example.com} wildcard, longest
 * {@code mail.*} wildcard, first matching regular expression in configuration order, then the
 * default server.
 *
 * <p>Location selection order: exact {@code =} match, then the longest prefix match; if that prefix
 * used {@code ^~} the search stops there, otherwise regular expressions are tried in configuration
 * order, and only when none matches does the longest prefix win.
 *
 * <p>Immutable after construction, so one instance is shared by every EventLoop; the compiled
 * patterns are shared for the same reason.
 */
public final class Router {

    private final List<ServerEntry> servers;
    private final ServerEntry defaultServer;
    private final Map<String, ServerEntry> exactNames = new HashMap<String, ServerEntry>();
    private final List<NameWildcard> headWildcards = new ArrayList<NameWildcard>();
    private final List<NameWildcard> tailWildcards = new ArrayList<NameWildcard>();
    private final List<NameRegex> regexNames = new ArrayList<NameRegex>();

    private Router(HttpBlock http) {
        this.servers = new ArrayList<ServerEntry>(http.servers().size());
        for (ServerBlock block : http.servers()) {
            servers.add(new ServerEntry(block));
        }
        for (ServerEntry entry : servers) {
            for (String name : entry.block().serverNames()) {
                registerName(entry, name);
            }
        }
        Comparator<NameWildcard> longestFirst = new Comparator<NameWildcard>() {
            @Override
            public int compare(NameWildcard left, NameWildcard right) {
                return right.pattern.length() - left.pattern.length();
            }
        };
        Collections.sort(headWildcards, longestFirst);
        Collections.sort(tailWildcards, longestFirst);
        this.defaultServer = entryOf(http.defaultServer());
    }

    /** Builds the immutable router for one http block. */
    public static Router of(HttpBlock http) {
        return new Router(http);
    }

    /**
     * Routes one request.
     *
     * @param hostHeader raw {@code Host} header value, may be {@code null}
     * @param path request path without the query string, matched verbatim (M2 does not decode
     *             percent escapes or collapse duplicate slashes)
     * @return the effective configuration for the request
     */
    public ResolvedLocation route(String hostHeader, String path) {
        return selectServer(normalizeHost(hostHeader)).route(path);
    }

    /** @return the number of virtual servers in configuration order */
    public int serverCount() {
        return servers.size();
    }

    // ------------------------------------------------------------------ server selection

    private void registerName(ServerEntry entry, String name) {
        if (name.startsWith("~")) {
            // nginx matches server_name regular expressions case insensitively.
            regexNames.add(new NameRegex(Pattern.compile(name.substring(1), Pattern.CASE_INSENSITIVE),
                    entry));
            return;
        }
        if (name.isEmpty()) {
            exactNames.put("", entry);
            return;
        }
        if ("_".equals(name)) {
            // A catch-all is not a name: it is handled by default-server selection.
            return;
        }
        if (name.startsWith("*.")) {
            headWildcards.add(new NameWildcard(name.substring(1), entry));
            return;
        }
        if (name.endsWith(".*")) {
            tailWildcards.add(new NameWildcard(name.substring(0, name.length() - 1), entry));
            return;
        }
        exactNames.put(name.toLowerCase(Locale.ROOT), entry);
    }

    private ServerEntry selectServer(String host) {
        if (host == null) {
            ServerEntry hostless = exactNames.get("");
            return hostless != null ? hostless : defaultServer;
        }
        ServerEntry exact = exactNames.get(host);
        if (exact != null) {
            return exact;
        }
        for (NameWildcard wildcard : headWildcards) {
            if (host.length() > wildcard.pattern.length() && host.endsWith(wildcard.pattern)) {
                return wildcard.server;
            }
        }
        for (NameWildcard wildcard : tailWildcards) {
            if (host.length() > wildcard.pattern.length() && host.startsWith(wildcard.pattern)) {
                return wildcard.server;
            }
        }
        for (NameRegex regex : regexNames) {
            if (regex.pattern.matcher(host).matches()) {
                return regex.server;
            }
        }
        return defaultServer;
    }

    private ServerEntry entryOf(ServerBlock block) {
        for (ServerEntry entry : servers) {
            if (entry.block() == block) {
                return entry;
            }
        }
        return servers.get(0);
    }

    /**
     * Normalises a {@code Host} header for matching: lower case, no port, no trailing dot.
     *
     * @return the normalised host, or {@code null} when the header is absent or empty
     */
    static String normalizeHost(String hostHeader) {
        if (hostHeader == null) {
            return null;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return null;
        }
        if (host.charAt(0) == '[') {
            int closing = host.indexOf(']');
            return closing > 0 ? host.substring(0, closing + 1) : host;
        }
        int firstColon = host.indexOf(':');
        if (firstColon >= 0 && firstColon == host.lastIndexOf(':')) {
            host = host.substring(0, firstColon);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isEmpty() ? null : host;
    }

    // ------------------------------------------------------------------ location selection

    /** One virtual server with its locations indexed for the nginx priority algorithm. */
    private static final class ServerEntry {

        private final ServerBlock block;
        private final Map<String, LocationBlock> exactLocations = new HashMap<String, LocationBlock>();
        private final List<LocationBlock> prefixLocations = new ArrayList<LocationBlock>();
        private final List<LocationBlock> regexLocations = new ArrayList<LocationBlock>();
        private final ResolvedLocation serverDefaults;

        ServerEntry(ServerBlock block) {
            this.block = block;
            for (LocationBlock location : block.locations()) {
                if (location.modifier().isRegex()) {
                    regexLocations.add(location);
                } else if (location.modifier() == LocationModifier.EXACT) {
                    exactLocations.put(location.pattern(), location);
                } else {
                    prefixLocations.add(location);
                }
            }
            // Longest prefix first, so the first match is the longest one.
            Collections.sort(prefixLocations, new Comparator<LocationBlock>() {
                @Override
                public int compare(LocationBlock left, LocationBlock right) {
                    return right.pattern().length() - left.pattern().length();
                }
            });
            this.serverDefaults = new ResolvedLocation(block, null, block.root(), block.indexFiles(),
                    block.autoIndex(), block.keepAliveTimeoutMillis());
        }

        ServerBlock block() {
            return block;
        }

        ResolvedLocation route(String path) {
            LocationBlock exact = exactLocations.get(path);
            if (exact != null) {
                return resolve(exact);
            }
            LocationBlock longestPrefix = null;
            for (LocationBlock candidate : prefixLocations) {
                if (path.startsWith(candidate.pattern())) {
                    longestPrefix = candidate;
                    break;
                }
            }
            if (longestPrefix != null
                    && longestPrefix.modifier() == LocationModifier.PREFIX_PRIORITY) {
                return resolve(longestPrefix);
            }
            for (LocationBlock candidate : regexLocations) {
                if (candidate.regex().matcher(path).find()) {
                    return resolve(candidate);
                }
            }
            return longestPrefix != null ? resolve(longestPrefix) : serverDefaults;
        }

        private ResolvedLocation resolve(LocationBlock location) {
            return new ResolvedLocation(block, location, location.root(), location.indexFiles(),
                    location.autoIndex(), location.keepAliveTimeoutMillis());
        }
    }

    /** A wildcard server name reduced to the suffix or prefix it must be matched against. */
    private static final class NameWildcard {

        private final String pattern;
        private final ServerEntry server;

        NameWildcard(String pattern, ServerEntry server) {
            this.pattern = pattern;
            this.server = server;
        }
    }

    private static final class NameRegex {

        private final Pattern pattern;
        private final ServerEntry server;

        NameRegex(Pattern pattern, ServerEntry server) {
            this.pattern = pattern;
            this.server = server;
        }
    }
}