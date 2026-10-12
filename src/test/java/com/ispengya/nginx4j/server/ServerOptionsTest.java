package com.ispengya.nginx4j.server;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the fail-fast contract of the bootstrap options that stand in for nginx.conf until M2.
 */
public class ServerOptionsTest {

    @Test
    public void defaultsFollowTheFrozenThreadingModel() {
        ServerOptions options = ServerOptions.parse(new String[0]);

        assertEquals(8080, options.port());
        assertEquals(Paths.get("webroot").toAbsolutePath().normalize(), options.root());
        assertEquals(Runtime.getRuntime().availableProcessors(), options.workerThreads());
        assertEquals(65, options.keepAliveTimeoutSeconds());
        assertEquals(Collections.singletonList("index.html"), options.indexFiles());
        assertFalse(options.autoIndex());
    }

    @Test
    public void explicitArgumentsOverrideDefaults() {
        ServerOptions options = ServerOptions.parse(new String[] {
                "--port", "9090", "--root", "src", "--worker-threads", "3", "--keep-alive-timeout", "10",
                "--index", "index.html, index.htm", "--autoindex", "on"});

        assertEquals(9090, options.port());
        assertEquals(Paths.get("src").toAbsolutePath().normalize(), options.root());
        assertEquals(3, options.workerThreads());
        assertEquals(10, options.keepAliveTimeoutSeconds());
        assertEquals(java.util.Arrays.asList("index.html", "index.htm"), options.indexFiles());
        assertTrue(options.autoIndex());
    }

    @Test
    public void portZeroIsAcceptedSoTestsCanPickAFreePort() {
        assertEquals(0, ServerOptions.parse(new String[] {"--port", "0"}).port());
    }

    @Test
    public void unknownArgumentFailsFast() {
        try {
            ServerOptions.parse(new String[] {"--nope"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("--nope"));
        }
    }

    @Test
    public void missingValueFailsFast() {
        try {
            ServerOptions.parse(new String[] {"--port"});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("--port"));
        }
    }

    @Test
    public void nonNumericAndNonPositiveValuesFailFast() {
        assertRejected(new String[] {"--port", "abc"}, "--port");
        assertRejected(new String[] {"--worker-threads", "0"}, "--worker-threads");
        assertRejected(new String[] {"--keep-alive-timeout", "-1"}, "--keep-alive-timeout");
    }

    @Test
    public void malformedIndexAndAutoIndexValuesFailFast() {
        assertRejected(new String[] {"--autoindex", "maybe"}, "--autoindex");
        assertRejected(new String[] {"--index", "index.html,"}, "--index");
    }

    @Test
    public void missingRootDirectoryFailsFast() {
        assertRejected(new String[] {"--root", "definitely-not-a-directory"}, "--root");
    }

    @Test
    public void configurationFileOwnsTheValuesTheCommandLineWouldOtherwiseSet() throws Exception {
        Path confFile = writeConf("http {\n"
                + "  keepalive_timeout 5s;\n"
                + "  server { listen 0; server_name a.com; root " + slashTempDir() + "; }\n"
                + "}\n");

        ServerOptions options = ServerOptions.parse(new String[] {
                "-c", confFile.toString(), "--worker-threads", "3"});

        assertEquals(0, options.port());
        assertEquals(3, options.workerThreads());
        assertEquals(5, options.keepAliveTimeoutSeconds());
        assertEquals(confFile.toAbsolutePath().normalize(), options.confFile());
        assertEquals(1, options.http().servers().size());

        assertRejected(new String[] {"-c", confFile.toString(), "--port", "9090"}, "--port");
        assertRejected(new String[] {"-c", confFile.toString(), "--root", "."}, "--root");
        assertRejected(new String[] {"-c", confFile.toString(), "--index", "a.html"}, "--index");
        assertRejected(new String[] {"-c", confFile.toString(), "--autoindex", "on"}, "--autoindex");
        assertRejected(new String[] {"-c", confFile.toString(), "--keep-alive-timeout", "9"},
                "--keep-alive-timeout");
    }

    @Test
    public void unreadableOrRootlessConfigurationFilesFailFast() throws Exception {
        assertRejected(new String[] {"-c", "definitely-not-a.conf"},
                "cannot read configuration file");

        Path confFile = writeConf("http {\n  root /definitely/not/here;\n  server { listen 0; }\n}\n");
        assertRejected(new String[] {"-c", confFile.toString()}, "does not exist");
    }

    private static Path writeConf(String content) throws java.io.IOException {
        Path directory = java.nio.file.Files.createTempDirectory("nginx4j-options");
        Path confFile = directory.resolve("nginx.conf");
        java.nio.file.Files.write(confFile, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return confFile;
    }

    private static String slashTempDir() throws java.io.IOException {
        return java.nio.file.Files.createTempDirectory("nginx4j-root").toAbsolutePath()
                .toString().replace('\\', '/');
    }

    private static void assertRejected(String[] args, String expectedToken) {
        try {
            ServerOptions.parse(args);
            fail("expected IllegalArgumentException for " + expectedToken);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedToken));
        }
    }
}