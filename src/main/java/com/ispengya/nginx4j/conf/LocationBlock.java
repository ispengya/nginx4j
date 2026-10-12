package com.ispengya.nginx4j.conf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One {@code location} block with inheritance already folded in.
 *
 * <p>Every value accessor returns the value that is in effect for requests matched by this location:
 * the parser has already resolved http and server level overrides, so the runtime never walks a
 * parent chain (PLAN.md section 8.2).
 *
 * <p>Immutable and safe to share across EventLoops; the compiled {@link #regex} is shared for the
 * same reason ({@link java.util.regex.Pattern} is thread-safe).
 */
public final class LocationBlock {

    private final LocationModifier modifier;
    private final String pattern;
    private final java.util.regex.Pattern regex;
    private final Path root;
    private final List<String> indexFiles;
    private final boolean autoIndex;
    private final long keepAliveTimeoutMillis;

    public LocationBlock(LocationModifier modifier, String pattern, java.util.regex.Pattern regex,
            Path root, List<String> indexFiles, boolean autoIndex, long keepAliveTimeoutMillis) {
        this.modifier = modifier;
        this.pattern = pattern;
        this.regex = regex;
        this.root = root;
        this.indexFiles = Collections.unmodifiableList(new ArrayList<String>(indexFiles));
        this.autoIndex = autoIndex;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
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

    @Override
    public String toString() {
        return "location " + modifier.spelling() + " " + pattern;
    }
}