package com.ispengya.nginx4j.conf;

/**
 * The five {@code location} forms of the supported subset.
 *
 * <p>The declaration order is nginx's spelling order, not the matching order: {@code =} wins
 * immediately and {@code ^~} suppresses regular expressions. The matching algorithm lives in the
 * router (PLAN.md section 8.3), which is the only place that should encode priority.
 */
public enum LocationModifier {

    /** {@code location = /path} — matches that exact URI only. */
    EXACT("="),
    /** {@code location /path} — plain prefix, lowest priority. */
    PREFIX(""),
    /** {@code location ^~ /path} — prefix that wins over regular expressions. */
    PREFIX_PRIORITY("^~"),
    /** {@code location ~ regex} — case sensitive regular expression. */
    REGEX("~"),
    /** {@code location ~* regex} — case insensitive regular expression. */
    REGEX_IGNORE_CASE("~*");

    private final String spelling;

    LocationModifier(String spelling) {
        this.spelling = spelling;
    }

    public String spelling() {
        return spelling;
    }

    public boolean isRegex() {
        return this == REGEX || this == REGEX_IGNORE_CASE;
    }

    public boolean isPrefix() {
        return this == PREFIX || this == PREFIX_PRIORITY;
    }
}