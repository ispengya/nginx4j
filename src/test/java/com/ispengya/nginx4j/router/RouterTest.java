package com.ispengya.nginx4j.router;

import com.ispengya.nginx4j.conf.ConfParser;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the nginx routing priority rules (PLAN.md section 8.3).
 *
 * <p>Every virtual server and every location in these fixtures uses a distinct {@code root}, so the
 * assertion "which block answered" reads directly off {@link ResolvedLocation#root()}.
 */
public class RouterTest {

    @Test
    public void serverNamePriorityFollowsNginxOrder() {
        Router router = router("http {\n"
                + "  root /srv/fallback;\n"
                + "  server { listen 8080 default_server; server_name _; root /srv/default; }\n"
                + "  server { listen 8080; server_name exact.example.com; root /srv/exact; }\n"
                + "  server { listen 8080; server_name *.wild.example.com; root /srv/head-wild; }\n"
                + "  server { listen 8080; server_name *.deep.wild.example.com;"
                + " root /srv/head-wild-long; }\n"
                + "  server { listen 8080; server_name b.deep.wild.example.com; root /srv/exact-over-wild; }\n"
                + "  server { listen 8080; server_name mail.*; root /srv/tail-wild; }\n"
                + "  server { listen 8080; server_name ~^vip-[0-9]+\\.example\\.com$; root /srv/regex; }\n"
                + "}\n");

        assertEquals(absolute("/srv/exact"), router.route("exact.example.com", "/x").root());
        assertEquals(absolute("/srv/exact-over-wild"),
                router.route("b.deep.wild.example.com", "/x").root());
        assertEquals(absolute("/srv/head-wild"), router.route("a.wild.example.com", "/x").root());
        assertEquals(absolute("/srv/head-wild-long"),
                router.route("a.deep.wild.example.com", "/x").root());
        assertEquals(absolute("/srv/tail-wild"), router.route("mail.example.org", "/x").root());
        assertEquals(absolute("/srv/regex"), router.route("vip-12.example.com", "/x").root());
        assertEquals(absolute("/srv/regex"), router.route("VIP-99.example.com", "/x").root());
        assertEquals(absolute("/srv/default"), router.route("unknown.example.net", "/x").root());
        assertEquals(absolute("/srv/default"), router.route(null, "/x").root());
    }

    @Test
    public void locationPriorityFollowsNginxOrder() {
        Router router = router("http {\n"
                + "  root /srv/www;\n"
                + "  server {\n"
                + "    listen 8080;\n"
                + "    location / { root /srv/all; }\n"
                + "    location /a/ { root /srv/a; }\n"
                + "    location ^~ /a/b/ { root /srv/ab; }\n"
                + "    location ~ \\.php$ { root /srv/php; }\n"
                + "    location ~* \\.jpg$ { root /srv/jpg; }\n"
                + "    location = /a/b/c.php { root /srv/exact-php; }\n"
                + "  }\n"
                + "}\n");

        // ^~ wins over the regular expressions that would otherwise match the same path.
        assertEquals(absolute("/srv/ab"), router.route("a.com", "/a/b/dup.php").root());
        // = is decided before anything else.
        assertEquals(absolute("/srv/exact-php"), router.route("a.com", "/a/b/c.php").root());
        // Regular expressions are matched unanchored, in configuration order.
        assertEquals(absolute("/srv/php"), router.route("a.com", "/a/x.php").root());
        assertEquals(absolute("/srv/jpg"), router.route("a.com", "/a/pic.JPG").root());
        // Longest prefix wins when no expression matches.
        assertEquals(absolute("/srv/a"), router.route("a.com", "/a/z").root());
        assertEquals(absolute("/srv/all"), router.route("a.com", "/other").root());

        assertEquals("/a/b/", router.route("a.com", "/a/b/dup.php").locationPattern());
    }

    @Test
    public void serverWithoutLocationsFallsBackToServerDefaults() {
        Router router = router("http {\n"
                + "  root /srv/www;\n"
                + "  index a.html b.html;\n"
                + "  autoindex on;\n"
                + "  keepalive_timeout 12s;\n"
                + "  server { listen 8080; server_name a.com; }\n"
                + "}\n");

        ResolvedLocation resolved = router.route("a.com", "/x");
        assertEquals(absolute("/srv/www"), resolved.root());
        assertEquals(Arrays.asList("a.html", "b.html"), resolved.indexFiles());
        assertTrue(resolved.autoIndex());
        assertEquals(12000L, resolved.keepAliveTimeoutMillis());
        assertNull(resolved.location());
        assertNull(resolved.locationPattern());
    }

    @Test
    public void locationOverridesSurfaceThroughResolvedLocation() {
        Router router = router("http {\n"
                + "  root /srv/www;\n"
                + "  index index.html;\n"
                + "  autoindex off;\n"
                + "  keepalive_timeout 30s;\n"
                + "  server {\n"
                + "    listen 8080;\n"
                + "    location /pub/ { index pub.html; autoindex on; keepalive_timeout 500ms; }\n"
                + "  }\n"
                + "}\n");

        ResolvedLocation inside = router.route("a.com", "/pub/file");
        assertEquals(absolute("/srv/www"), inside.root());
        assertEquals(Arrays.asList("pub.html"), inside.indexFiles());
        assertTrue(inside.autoIndex());
        assertEquals(500L, inside.keepAliveTimeoutMillis());
        assertEquals("/pub/", inside.locationPattern());

        ResolvedLocation outside = router.route("a.com", "/private/file");
        assertEquals(Arrays.asList("index.html"), outside.indexFiles());
        assertTrue(!outside.autoIndex());
        assertEquals(30000L, outside.keepAliveTimeoutMillis());
    }

    @Test
    public void hostNormalizationStripsPortCaseAndTrailingDot() {
        Router router = router("http {\n"
                + "  root /srv/www;\n"
                + "  server { listen 8080; server_name example.com; root /srv/example; }\n"
                + "  server { listen 8080; server_name other.com; root /srv/other; }\n"
                + "}\n");

        assertEquals(absolute("/srv/example"), router.route("EXAMPLE.com:8080", "/x").root());
        assertEquals(absolute("/srv/example"), router.route("example.com.", "/x").root());
        assertEquals(absolute("/srv/other"), router.route("other.com:81", "/x").root());
        assertEquals(2, router.serverCount());
        // Nothing matches, and no server claims to be the default: the first one answers.
        assertEquals(absolute("/srv/example"), router.route("unknown.example.net", "/x").root());
    }

    @Test
    public void emptyServerNameAnswersHostlessRequestsOnly() {
        Router router = router("http {\n"
                + "  root /srv/www;\n"
                + "  server { listen 8080; server_name \"\"; root /srv/hostless; }\n"
                + "  server { listen 8080; server_name a.com; root /srv/a; }\n"
                + "}\n");

        assertEquals(absolute("/srv/hostless"), router.route(null, "/x").root());
        assertEquals(absolute("/srv/a"), router.route("a.com", "/x").root());
        // An unmatched name that is not hostless still falls back to the default server.
        assertEquals(absolute("/srv/hostless"), router.route("b.com", "/x").root());
    }

    private static Router router(String conf) {
        return Router.of(ConfParser.parseString("test.conf", conf, Paths.get(".")).http());
    }

    /** Mirrors how the parser resolves a rooted-but-driveless path such as {@code /srv/www}. */
    private static Path absolute(String value) {
        return Paths.get(value).toAbsolutePath().normalize();
    }
}