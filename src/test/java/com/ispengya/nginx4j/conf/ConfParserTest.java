package com.ispengya.nginx4j.conf;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the nginx.conf subset parser.
 *
 * <p>Two contracts are covered: the happy path must fold inheritance into every block so that the
 * runtime never walks a parent chain, and every error must name the file, line and column.
 */
public class ConfParserTest {

    private static final String SOURCE = "test.conf";

    @Test
    public void parsesFullConfigurationAndFoldsInheritance() {
        MainBlock main = parse("worker_processes 4;\n"
                + "events { worker_connections 2048; }\n"
                + "http {\n"
                + "  keepalive_timeout 30s;\n"
                + "  root /srv/www;\n"
                + "  index index.html index.htm;\n"
                + "  server {\n"
                + "    listen 8080 default_server;\n"
                + "    server_name example.com *.example.com;\n"
                + "    autoindex on;\n"
                + "    location /assets/ { root /srv/assets; }\n"
                + "    location = /health { autoindex off; }\n"
                + "    location ~* \\.(png|jpg)$ { }\n"
                + "    location ^~ /private/ { keepalive_timeout 5; }\n"
                + "  }\n"
                + "  server {\n"
                + "    listen 8080;\n"
                + "    server_name admin.example.com;\n"
                + "    root /srv/admin;\n"
                + "  }\n"
                + "}\n");

        assertEquals(4, main.workerProcesses());
        assertEquals(2048, main.events().workerConnections());

        HttpBlock http = main.http();
        assertEquals(2, http.servers().size());
        assertEquals(8080, http.listen().port());
        assertNull(http.listen().host());

        ServerBlock first = http.servers().get(0);
        assertEquals(Arrays.asList("example.com", "*.example.com"), first.serverNames());
        assertTrue(first.isDefaultServer());
        assertFalse(first.isCatchAll());
        assertEquals(Paths.get("/srv/www").toAbsolutePath().normalize(), first.root());
        assertEquals(Arrays.asList("index.html", "index.htm"), first.indexFiles());
        assertTrue(first.autoIndex());
        assertEquals(30000L, first.keepAliveTimeoutMillis());

        List<LocationBlock> locations = first.locations();
        assertEquals(4, locations.size());

        LocationBlock assets = locations.get(0);
        assertEquals(LocationModifier.PREFIX, assets.modifier());
        assertEquals("/assets/", assets.pattern());
        assertEquals(Paths.get("/srv/assets").toAbsolutePath().normalize(), assets.root());
        assertEquals(Arrays.asList("index.html", "index.htm"), assets.indexFiles());
        assertTrue(assets.autoIndex());
        assertEquals(30000L, assets.keepAliveTimeoutMillis());

        LocationBlock health = locations.get(1);
        assertEquals(LocationModifier.EXACT, health.modifier());
        assertEquals(Paths.get("/srv/www").toAbsolutePath().normalize(), health.root());
        assertFalse(health.autoIndex());

        LocationBlock images = locations.get(2);
        assertEquals(LocationModifier.REGEX_IGNORE_CASE, images.modifier());
        assertNotNull(images.regex());
        assertTrue(images.regex().matcher("/img/logo.PNG").find());
        assertFalse(images.regex().matcher("/img/logo.gif").find());

        LocationBlock privateArea = locations.get(3);
        assertEquals(LocationModifier.PREFIX_PRIORITY, privateArea.modifier());
        assertEquals(5000L, privateArea.keepAliveTimeoutMillis());

        ServerBlock second = http.servers().get(1);
        assertFalse(second.isDefaultServer());
        assertEquals(Paths.get("/srv/admin").toAbsolutePath().normalize(), second.root());
        assertEquals(Paths.get("/srv/www").toAbsolutePath().normalize(), http.defaultServer().root());
    }

    @Test
    public void serverWithoutServerNameIsCatchAll() {
        HttpBlock http = parse("http {\n  root /srv/www;\n  server { listen 8080; }\n}\n").http();

        ServerBlock server = http.servers().get(0);
        assertTrue(server.isCatchAll());
        assertEquals("(any)", server.describeNames());
        assertTrue(server.isDefaultServer() || http.defaultServer() == server);
    }

    @Test
    public void underscoreAndDotNamesFollowNginxMeaning() {
        HttpBlock http = parse("http {\n"
                + "  root /srv/www;\n"
                + "  server { listen 8080; server_name _; }\n"
                + "  server { listen 8080; server_name .example.com; }\n"
                + "}\n").http();

        ServerBlock catchAll = http.servers().get(0);
        assertTrue(catchAll.isCatchAll());

        // ".example.com" is the exact name plus its wildcard form, as in nginx.
        assertEquals(Arrays.asList("example.com", "*.example.com"),
                http.servers().get(1).serverNames());
        // A catch-all server wins over the first server only when nothing else matches.
        assertEquals(catchAll, http.defaultServer());
    }

    @Test
    public void relativeRootResolvesAgainstTheConfigurationDirectory() throws Exception {
        Path baseDir = java.nio.file.Files.createTempDirectory("nginx4j-conf").toAbsolutePath();
        MainBlock main = ConfParser.parseString(SOURCE,
                "http {\n  root html;\n  server { listen 8080; }\n}\n", baseDir);

        assertEquals(baseDir.resolve("html").normalize(), main.http().servers().get(0).root());
    }

    @Test
    public void commentsQuotesAndTimeUnitsAreSupported() {
        HttpBlock http = parse("# leading comment\n"
                + "http {\n"
                + "  root /srv/www; # trailing comment\n"
                + "  index \"index.html\";\n"
                + "  keepalive_timeout 1m 20s;\n"
                + "  server {\n"
                + "    listen 8080;\n"
                + "    server_name \"one.example.com\";\n"
                + "  }\n"
                + "}\n").http();

        ServerBlock server = http.servers().get(0);
        assertEquals(Collections.singletonList("index.html"), server.indexFiles());
        assertEquals(60000L, server.keepAliveTimeoutMillis());
        assertEquals(Collections.singletonList("one.example.com"), server.serverNames());
    }

    @Test
    public void addressAndDefaultPortFormsAreAccepted() {
        HttpBlock wildcard = parse("http {\n  root /srv/www;\n"
                + "  server { listen 127.0.0.1:8081; }\n}\n").http();
        assertEquals("127.0.0.1", wildcard.listen().host());
        assertEquals(8081, wildcard.listen().port());

        HttpBlock bareHost = parse("http {\n  root /srv/www;\n"
                + "  server { listen 127.0.0.1; }\n}\n").http();
        assertEquals("127.0.0.1", bareHost.listen().host());
        assertEquals(80, bareHost.listen().port());

        HttpBlock ipv6 = parse("http {\n  root /srv/www;\n"
                + "  server { listen [::]:9090; }\n}\n").http();
        assertNull(ipv6.listen().host());
        assertEquals(9090, ipv6.listen().port());
    }

    @Test
    public void missingSemicolonPointsAtTheOffendingToken() {
        ConfException error = reject("http {\n"
                + "  root /srv/www\n"
                + "}\n");

        assertTrue(error.getMessage(), error.getMessage().startsWith(SOURCE + ":3:1: "));
        assertTrue(error.getMessage(), error.getMessage().contains("'root' must end with ';'"));
    }

    @Test
    public void unknownDirectiveNamesTheMilestoneThatWillImplementIt() {
        ConfException error = reject("http {\n  sendfile on;\n}\n");

        assertTrue(error.getMessage(), error.getMessage().contains("unknown directive 'sendfile'"));
        assertTrue(error.getMessage(), error.getMessage().contains("not part of the M2 subset"));

        ConfException proxy = reject("http {\n  proxy_pass http://backend;\n}\n");
        assertTrue(proxy.getMessage(), proxy.getMessage().contains("M3 reverse proxy"));
    }

    @Test
    public void duplicateDirectiveInOneContextIsRejected() {
        ConfException error = reject("http {\n  root /a;\n  root /b;\n}\n");

        assertTrue(error.getMessage(), error.getMessage().contains("duplicate 'root'"));
        assertTrue(error.getMessage(), error.getMessage().startsWith(SOURCE + ":3:3: "));
    }

    @Test
    public void invalidRegexIsRejectedAtThePattern() {
        ConfException error = reject("http {\n  root /srv/www;\n  server {\n"
                + "    listen 8080;\n    location ~* [unclosed {\n  }\n}\n");

        assertTrue(error.getMessage(), error.getMessage().contains("invalid regular expression"));
    }

    @Test
    public void nestedLocationIsRejected() {
        ConfException error = reject("http {\n  root /srv/www;\n  server {\n"
                + "    listen 8080;\n    location /a/ { location /a/b/ { } }\n  }\n}\n");

        assertTrue(error.getMessage(),
                error.getMessage().contains("nested 'location' blocks are not supported"));
    }

    @Test
    public void variablesAreRejectedOutsideRegexes() {
        ConfException error = reject("http {\n  root /srv/$host;\n}\n");

        assertTrue(error.getMessage(), error.getMessage().contains("variables are not supported"));
    }

    @Test
    public void severalListenPortsAreRejected() {
        ConfException error = reject("http {\n  root /srv/www;\n"
                + "  server { listen 8080; }\n"
                + "  server { listen 9090; }\n}\n");

        assertTrue(error.getMessage(), error.getMessage().contains("single address"));
    }

    @Test
    public void tlsListenParametersAreDeferredToM5() {
        ConfException error = reject("http {\n  root /srv/www;\n"
                + "  server { listen 8443 ssl; }\n}\n");

        assertTrue(error.getMessage(), error.getMessage().contains("M5"));
    }

    @Test
    public void missingRootListenAndServerBlocksAreRejected() {
        assertTrue(reject("http {\n  server { listen 8080; }\n}\n").getMessage()
                .contains("no 'root' directive"));
        assertTrue(reject("http {\n  root /srv/www;\n  server { server_name a.com; }\n}\n")
                .getMessage().contains("needs a 'listen' directive"));
        assertTrue(reject("http {\n  root /srv/www;\n}\n").getMessage()
                .contains("at least one 'server'"));
        assertTrue(reject("worker_processes 2;\n").getMessage().contains("missing 'http"));
    }

    @Test
    public void unclosedBlockAndUnterminatedQuoteAreRejected() {
        assertTrue(reject("http {\n  root /srv/www;\n  server { listen 8080;\n")
                .getMessage().contains("not closed with '}'"));
        assertTrue(reject("http {\n  root \"/srv/www;\n}\n").getMessage()
                .contains("unterminated quoted string"));
    }

    @Test
    public void malformedWildcardsAndSecondDefaultServerAreRejected() {
        assertTrue(reject("http {\n  root /srv/www;\n"
                + "  server { listen 8080; server_name a*b.com; }\n}\n")
                .getMessage().contains("wildcard server_name"));
        assertTrue(reject("http {\n  root /srv/www;\n"
                + "  server { listen 8080 default_server; }\n"
                + "  server { listen 8080 default_server; }\n}\n")
                .getMessage().contains("second 'default_server'"));
    }

    @Test
    public void listenAndBlockShapeMistakesAreRejected() {
        assertTrue(reject("http {\n  root /srv/www;\n  server { listen 8080 bogus; }\n}\n")
                .getMessage().contains("unsupported 'listen' parameter"));
        assertTrue(reject("http {\n  root /srv/www;\n  server { listen 8080 { } }\n}\n")
                .getMessage().contains("does not take a block"));
        assertTrue(reject("http {\n  root /srv/www;\n  server { listen 70000; }\n}\n")
                .getMessage().contains("port out of range"));
        assertTrue(reject("http {\n  root /srv/www;\n  server { listen 8080; location /a/b; }\n}\n")
                .getMessage().contains("requires a '{ ... }' block"));
    }

    private static MainBlock parse(String conf) {
        return ConfParser.parseString(SOURCE, conf, Paths.get("."));
    }

    private static ConfException reject(String conf) {
        try {
            parse(conf);
        } catch (ConfException expected) {
            return expected;
        }
        fail("expected the configuration to be rejected: " + conf);
        return null;
    }
}