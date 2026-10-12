package com.ispengya.nginx4j.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for a server started from an nginx.conf file.
 *
 * <p>These are the M2 acceptance evidence: two virtual servers on one port, each with its own root,
 * plus every location priority rule observed through real HTTP responses rather than through the
 * router API. The configuration binds port 0 so the suite never collides with a running instance.
 */
public class ConfIntegrationTest {

    private static final int KEEP_ALIVE_TIMEOUT_SECONDS = 2;

    private Path workspace;
    private HttpServer server;
    private int port;

    @Before
    public void startServerFromFile() throws IOException {
        workspace = Files.createTempDirectory("nginx4j-conf-integration");
        Path rootA = Files.createDirectories(workspace.resolve("root-a"));
        Path rootB = Files.createDirectories(workspace.resolve("root-b"));
        Path assets = Files.createDirectories(workspace.resolve("assets"));
        Path regexRoot = Files.createDirectories(workspace.resolve("regex-root"));

        write(rootA.resolve("index.html"), "A");
        write(rootB.resolve("index.html"), "B");
        write(rootA.resolve("only-a.txt"), "only-a");
        write(rootA.resolve("exact.txt"), "exact");
        write(rootA.resolve("private").resolve("x.json"), "private-json");
        write(assets.resolve("logo.txt"), "logo");
        write(regexRoot.resolve("data.json"), "regex-json");
        write(regexRoot.resolve("assets").resolve("x.json"), "regex-assets-json");

        Path confFile = workspace.resolve("nginx.conf");
        write(confFile, "worker_processes 2;\n"
                + "events { worker_connections 128; }\n"
                + "http {\n"
                + "  keepalive_timeout " + KEEP_ALIVE_TIMEOUT_SECONDS + "s;\n"
                + "  server {\n"
                + "    listen 0;\n"
                + "    server_name a.example.com;\n"
                + "    root " + slash(rootA) + ";\n"
                + "  }\n"
                + "  server {\n"
                + "    listen 0;\n"
                + "    server_name b.example.com;\n"
                + "    root " + slash(rootB) + ";\n"
                + "    location /assets/ { root " + slash(workspace) + "; }\n"
                + "    location = /exact.txt { root " + slash(rootA) + "; }\n"
                + "    location ^~ /private/ { root " + slash(rootA) + "; }\n"
                + "    location ~ \\.json$ { root " + slash(regexRoot) + "; }\n"
                + "  }\n"
                + "}\n");

        ServerOptions options = ServerOptions.parse(new String[] {
                "-c", confFile.toString(), "--worker-threads", "4"});
        server = new HttpServer(options);
        server.start();
        port = server.port();
    }

    @After
    public void stopServer() {
        server.stop();
        deleteRecursivelyQuietly(workspace);
    }

    @Test
    public void hostHeaderSelectsTheVirtualServer() throws IOException {
        RawHttpClient.Response fromA = RawHttpClient.get(port, "a.example.com", "/");
        RawHttpClient.Response fromB = RawHttpClient.get(port, "b.example.com", "/");

        assertEquals(200, fromA.status);
        assertEquals("A", fromA.bodyText());
        assertEquals(200, fromB.status);
        assertEquals("B", fromB.bodyText());
    }

    @Test
    public void hostHeaderIsMatchedCaseInsensitivelyAndWithoutPort() throws IOException {
        assertEquals("A", RawHttpClient.get(port, "A.Example.COM:8080", "/").bodyText());
        assertEquals("B", RawHttpClient.get(port, "b.example.com.", "/").bodyText());
    }

    @Test
    public void unmatchedHostFallsBackToTheFirstServer() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "unknown.example.net", "/");

        assertEquals(200, response.status);
        assertEquals("A", response.bodyText());
    }

    @Test
    public void eachServerHasItsOwnRoot() throws IOException {
        assertEquals(200, RawHttpClient.get(port, "a.example.com", "/only-a.txt").status);
        assertEquals("only-a", RawHttpClient.get(port, "a.example.com", "/only-a.txt").bodyText());
        // The same path does not exist in b's root: the roots really are per server.
        assertEquals(404, RawHttpClient.get(port, "b.example.com", "/only-a.txt").status);
    }

    @Test
    public void locationRootOverrideIsHonoured() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "b.example.com", "/assets/logo.txt");

        assertEquals(200, response.status);
        assertEquals("logo", response.bodyText());
    }

    @Test
    public void locationPrioritiesDecideWhichRootServesThePath() throws IOException {
        // = matches the exact URI and wins over every prefix and expression.
        assertEquals("exact", RawHttpClient.get(port, "b.example.com", "/exact.txt").bodyText());
        // ^~ suppresses the .json expression even though both match.
        assertEquals("private-json",
                RawHttpClient.get(port, "b.example.com", "/private/x.json").bodyText());
        // A plain prefix does not suppress the expression: the expression wins.
        assertEquals("regex-assets-json",
                RawHttpClient.get(port, "b.example.com", "/assets/x.json").bodyText());
        // Where no expression matches, the plain prefix still applies.
        assertEquals("logo", RawHttpClient.get(port, "b.example.com", "/assets/logo.txt").bodyText());
        // The expression applies on its own for any other .json path.
        assertEquals("regex-json",
                RawHttpClient.get(port, "b.example.com", "/data.json").bodyText());
    }

    @Test
    public void keepAliveTimeoutFromTheConfigurationClosesIdleConnections() throws IOException {
        try (Socket socket = RawHttpClient.connect(port)) {
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: a.example.com\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals(200, RawHttpClient.read(socket.getInputStream()).status);

            socket.setSoTimeout((KEEP_ALIVE_TIMEOUT_SECONDS + 4) * 1000);
            InputStream in = socket.getInputStream();
            assertEquals("the configured keepalive_timeout must close the idle connection",
                    -1, in.read());
        }
    }

    @Test
    public void malformedConfigurationFailsBeforeTheServerBinds() throws IOException {
        Path broken = workspace.resolve("broken.conf");
        write(broken, "http {\n  root " + slash(workspace) + ";\n  server { listen 0; location ^~ /x; }\n}\n");

        try {
            ServerOptions.parse(new String[] {"-c", broken.toString()});
            assertTrue("a broken configuration must be rejected", false);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("broken.conf"));
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Keeps Windows paths parseable as bare configuration tokens. */
    private static String slash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static void deleteRecursivelyQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            List<Path> paths = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
                stream.forEach(path -> paths.add(path));
            }
            Collections.sort(paths, Collections.reverseOrder());
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Windows may still hold file handles briefly after a transfer.
                }
            }
        } catch (IOException ignored) {
            // Temp-directory cleanup must never fail a test.
        }
    }
}