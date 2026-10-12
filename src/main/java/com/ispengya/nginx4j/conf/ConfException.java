package com.ispengya.nginx4j.conf;

/**
 * Syntax or semantic error in an nginx.conf file.
 *
 * <p>Extends {@link IllegalArgumentException} so that the bootstrap path in
 * {@code com.ispengya.nginx4j.Main} treats a bad configuration exactly like a bad command line:
 * report the reason and exit. The message always starts with {@code file:line:column} so that a
 * failure points at the offending token instead of the whole file.
 */
public final class ConfException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String source;
    private final int line;
    private final int column;

    public ConfException(String source, int line, int column, String message) {
        super(source + ":" + line + ":" + column + ": " + message);
        this.source = source;
        this.line = line;
        this.column = column;
    }

    public String source() {
        return source;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}