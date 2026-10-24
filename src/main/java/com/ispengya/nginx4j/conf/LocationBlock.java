package com.ispengya.nginx4j.conf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code location} block with inheritance already folded in.
 *
 * <p>Every value accessor returns the value that is in effect for requests matched by this location:
 * the parser has already resolved http and server level overrides, so the runtime never walks a
 * parent chain (PLAN.md section 8.2).
 *
 * <p>A non-null {@link #proxyTarget()} turns the location into a reverse proxy; the static file
 * handler is bypassed for it. Immutable and safe to share across EventLoops; the compiled
 * {@link #regex} is shared for the same reason ({@link java.util.regex.Pattern} is thread-safe).
 */
public final class LocationBlock {

    private final LocationModifier modifier;
    private final String pattern;
    private final java.util.regex.Pattern regex;
    private final Path root;
    private final List<String> indexFiles;
    private final boolean autoIndex;
    private final long keepAliveTimeoutMillis;
    private final ProxyTarget proxyTarget;
    private final Map<String, String> proxySetHeaders;
    private final long proxyConnectTimeoutMillis;
    private final long proxyReadTimeoutMillis;
    private final long proxySendTimeoutMillis;

    /**
     * Builds a static location, the M0-M2 shape used by tests that do not exercise proxying.
     */
    public LocationBlock(LocationModifier modifier, String pattern, java.util.regex.Pattern regex,
            Path root, List<String> indexFiles, boolean autoIndex, long keepAliveTimeoutMillis) {
        this(modifier, pattern, regex, root, indexFiles, autoIndex, keepAliveTimeoutMillis, null,
                Collections.<String, String>emptyMap(), ServerBlock.DEFAULT_PROXY_TIMEOUT_MILLIS,
                ServerBlock.DEFAULT_PROXY_TIMEOUT_MILLIS, ServerBlock.DEFAULT_PROXY_TIMEOUT_MILLIS);
    }

    /**
     * @param proxyTarget resolved {@code proxy_pass} target, or {@code null} for a static location
     * @param proxySetHeaders effective {@code proxy_set_header} values after inheritance; an empty
     *        value means "remove this header"
     */
    public LocationBlock(LocationModifier modifier, String pattern, java.util.regex.Pattern regex,
            Path root, List<String> indexFiles, boolean autoIndex, long keepAliveTimeoutMillis,
            ProxyTarget proxyTarget, Map<String, String> proxySetHeaders,
            long proxyConnectTimeoutMillis, long proxyReadTimeoutMillis,
            long proxySendTimeoutMillis) {
        this.modifier = modifier;
        this.pattern = pattern;
        this.regex = regex;
        this.root = root;
        this.indexFiles = Collections.unmodifiableList(new ArrayList<String>(indexFiles));
        this.autoIndex = autoIndex;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
        this.proxyTarget = proxyTarget;
        this.proxySetHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(proxySetHeaders));
        this.proxyConnectTimeoutMillis = proxyConnectTimeoutMillis;
        this.proxyReadTimeoutMillis = proxyReadTimeoutMillis;
        this.proxySendTimeoutMillis = proxySendTimeoutMillis;
    }

    public LocationModifier modifier() {
        return modifier;
    }

    /** @return the pattern exactly as written, without the modifier */
    public String pattern() {
        return pattern;
    }

    /** @return the compiled pattern for regex locations, {@code null} for prefix locations */
    public java.util.regex.Pattern regex() {
        return regex;
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

    /** @return the proxy target, or {@code null} when this location serves files */
    public ProxyTarget proxyTarget() {
        return proxyTarget;
    }

    /** @return whether requests matched here are forwarded instead of served from disk */
    public boolean isProxy() {
        return proxyTarget != null;
    }

    /** @return effective {@code proxy_set_header} values, merged http → server → location by name */
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

    @Override
    public String toString() {
        return "location " + modifier.spelling() + " " + pattern;
    }
}