package com.ispengya.nginx4j.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests over real sockets. These cover what {@code EmbeddedChannel} cannot: the actual
 * pipeline wiring (codec, keep-alive handler, zero-copy writes) and the keep-alive idle timer.
 *
 * <p>The server always binds port 0, so the suite never collides with a locally running instance.
 */
public class HttpServerIntegrationTest {

    private static final byte[] INDEX_BODY =
            "<html><body>nginx4j integration</body></html>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BIG_BODY = new byte[1024 * 1024];
    private static final int KEEP_ALIVE_TIMEOUT_SECONDS = 2;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private Path workspace;
    private Path root;
    private HttpServer server;
    private int port;

    @Before
    public void startServer() throws IOException {
        workspace = Files.createTempDirectory("nginx4j-integration");
        root = Files.createDirectories(workspace.resolve("root"));
        Files.write(root.resolve("index.html"), INDEX_BODY);
        new Random(42L).nextBytes(BIG_BODY);
        Files.write(root.resolve("big.bin"), BIG_BODY);
        Files.createDirectories(root.resolve("sub"));
        Files.write(root.resolve("sub/index.html"), "sub".getBytes(StandardCharsets.UTF_8));

        ServerOptions options = ServerOptions.parse(new String[] {
                "--port", "0",
                "--root", root.toString(),
                "--worker-threads", "4",
                "--keep-alive-timeout", Integer.toString(KEEP_ALIVE_TIMEOUT_SECONDS)});
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
    public void rootPathServesIndexOverTcp() throws IOException {
        HttpResponse response = request("GET", "/", "close");

        assertEquals(200, response.status);
        assertEquals("text/html; charset=utf-8", response.header("content-type"));
        assertArrayEquals(INDEX_BODY, response.body);
        assertNotNull(response.header("etag"));
        assertNotNull(response.header("last-modified"));
        assertEquals("bytes", response.header("accept-ranges"));
    }

    @Test
    public void rangeRequestOverTcpReturnsExactSlice() throws IOException {
        HttpResponse response = rawRequest("GET /big.bin HTTP/1.1\r\nHost: localhost\r\n"
                + "Range: bytes=1048570-1048575\r\nConnection: close\r\n\r\n");

        assertEquals(206, response.status);
        assertEquals("bytes 1048570-1048575/1048576", response.header("content-range"));
        assertArrayEquals(java.util.Arrays.copyOfRange(BIG_BODY, 1048570, 1048576), response.body);
    }

    @Test
    public void conditionalRequestOverTcpReturns304() throws IOException {
        String etag = request("GET", "/index.html", "close").header("etag");
        HttpResponse response = rawRequest("GET /index.html HTTP/1.1\r\nHost: localhost\r\n"
                + "If-None-Match: " + etag + "\r\nConnection: close\r\n\r\n");

        assertEquals(304, response.status);
        assertEquals(0, response.body.length);
    }

    @Test
    public void directoryWithoutSlashRedirectsOverTcp() throws IOException {
        HttpResponse response = request("GET", "/sub", "close");

        assertEquals(301, response.status);
        assertEquals("/sub/", response.header("location"));
    }

    @Test
    public void twoRequestsShareOneKeepAliveConnection() throws IOException {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            HttpResponse first = readResponse(in);

            out.write("GET /sub/ HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            HttpResponse second = readResponse(in);

            assertEquals(200, first.status);
            assertArrayEquals(INDEX_BODY, first.body);
            assertEquals(200, second.status);
            assertArrayEquals("sub".getBytes(StandardCharsets.UTF_8), second.body);
        }
    }

    @Test
    public void idleKeepAliveConnectionIsClosedByTimeout() throws IOException {
        try (Socket socket = connect()) {
            socket.getOutputStream().write("GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals(200, readResponse(socket.getInputStream()).status);

            socket.setSoTimeout((KEEP_ALIVE_TIMEOUT_SECONDS + 4) * 1000);
            assertEquals("the idle keep-alive connection must be closed by the server",
                    -1, socket.getInputStream().read());
        }
    }

    @Test
    public void concurrentClientsAreAllServed() throws Exception {
        final int clients = 8;
        final int requestsPerClient = 25;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Callable<Integer>> tasks = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < clients; i++) {
                tasks.add(new Callable<Integer>() {
                    @Override
                    public Integer call() throws IOException {
                        int served = 0;
                        for (int j = 0; j < requestsPerClient; j++) {
                            HttpResponse response = request("GET", "/index.html", "close");
                            if (response.status == 200
                                    && java.util.Arrays.equals(INDEX_BODY, response.body)) {
                                served++;
                            }
                        }
                        return served;
                    }
                });
            }
            int total = 0;
            for (Future<Integer> future : pool.invokeAll(tasks)) {
                total += future.get();
            }
            assertEquals(clients * requestsPerClient, total);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private HttpResponse request(String method, String path, String connectionMode) throws IOException {
        return rawRequest(method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: "
                + connectionMode + "\r\n\r\n");
    }

    private HttpResponse rawRequest(String rawRequest) throws IOException {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readResponse(socket.getInputStream());
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        return socket;
    }

    private static HttpResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("empty response");
        }
        String[] parts = statusLine.split(" ", 3);
        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        byte[] body = new byte[0];
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            body = readExactly(in, Integer.parseInt(contentLength));
        }
        return new HttpResponse(Integer.parseInt(parts[1]), headers, body);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                break;
            }
            if (read != '\r') {
                buffer.write(read);
            }
        }
        if (read == -1 && buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("truncated body: expected " + length + " bytes, got " + offset);
            }
            offset += read;
        }
        return data;
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

    private static final class HttpResponse {

        private final int status;
        private final Map<String, String> headers;
        private final byte[] body;

        HttpResponse(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name);
        }
    }
}