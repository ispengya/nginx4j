package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.server.HttpServer;
import com.ispengya.nginx4j.server.RawHttpClient;
import com.ispengya.nginx4j.server.ServerOptions;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end reverse proxy tests over real sockets.
 *
 * <p>The upstream is a small raw-socket server so the test can assert things a normal HTTP client
 * hides: the exact headers that reached the upstream, how many upstream connections were opened, and
 * that the first SSE event reaches the client while the upstream is still holding the second one.
 */
public class ProxyIntegrationTest {

    private static final int BIG_SIZE = 2 * 1024 * 1024;
    private static final int SOAK_SIZE = 256 * 1024 * 1024;
    private static final byte[] BIG_BODY = new byte[BIG_SIZE];

    static {
        for (int i = 0; i < BIG_SIZE; i++) {
            BIG_BODY[i] = (byte) (i % 251);
        }
    }

    private Path workspace;
    private MockUpstream upstream;
    private HttpServer server;
    private int port;

    @Before
    public void startProxy() throws IOException {
        workspace = Files.createTempDirectory("nginx4j-proxy");
        Path root = Files.createDirectories(workspace.resolve("root"));
        Files.write(root.resolve("index.html"), "static".getBytes(StandardCharsets.UTF_8));

        upstream = new MockUpstream();

        Path conf = workspace.resolve("nginx.conf");
        write(conf, "worker_processes 2;\n"
                + "events { worker_connections 128; }\n"
                + "http {\n"
                + "  keepalive_timeout 2s;\n"
                + "  proxy_connect_timeout 500ms;\n"
                + "  proxy_read_timeout 2s;\n"
                + "  proxy_send_timeout 2s;\n"
                + "  upstream backend { server 127.0.0.1:" + upstream.port() + "; }\n"
                + "  server {\n"
                + "    listen 0;\n"
                + "    root " + slash(root) + ";\n"
                + "    location /proxy/ { proxy_pass http://127.0.0.1:" + upstream.port() + "; }\n"
                + "    location /pool/ { proxy_pass http://backend; }\n"
                + "    location /hang/ { proxy_pass http://127.0.0.1:" + upstream.port()
                + "; proxy_read_timeout 300ms; }\n"
                + "    location /soak/ { proxy_pass http://127.0.0.1:" + upstream.port()
                + "; proxy_read_timeout 30s; }\n"
                + "  }\n"
                + "}\n");

        ServerOptions options = ServerOptions.parse(new String[] {
                "-c", conf.toString(), "--worker-threads", "2"});
        server = new HttpServer(options);
        server.start();
        port = server.port();
    }

    @After
    public void stopProxy() {
        if (server != null) {
            server.stop();
        }
        if (upstream != null) {
            upstream.close();
        }
        deleteRecursivelyQuietly(workspace);
    }

    @Test
    public void proxiesABodyFromTheUpstream() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "localhost", "/proxy/hello");

        assertEquals(200, response.status);
        assertEquals("hello", response.bodyText());
        assertEquals("text/plain", response.header("content-type"));
        assertNull(response.header("keep-alive"));
        assertNull(response.header("x-hop"));
    }

    @Test
    public void rewritesRequestHeadersAndStripsHopByHopHeaders() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "localhost", "/proxy/headers",
                "X-Custom: value",
                "Connection: keep-alive, X-Drop",
                "X-Drop: secret",
                "Upgrade: websocket");

        String seen = response.bodyText();
        assertEquals(200, response.status);
        assertTrue(seen, seen.contains("x-custom: value"));
        assertTrue(seen, seen.contains("x-forwarded-for: 127.0.0.1"));
        assertTrue(seen, seen.contains("host: 127.0.0.1:" + upstream.port()));
        assertTrue(seen, seen.contains("connection: close"));
        assertFalse(seen, seen.contains("x-drop"));
        assertFalse(seen, seen.contains("upgrade"));
        assertFalse(seen, seen.contains("keep-alive"));
    }

    @Test
    public void stripsUpstreamHopByHopResponseHeaders() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "localhost", "/proxy/hop");

        assertEquals(200, response.status);
        assertEquals("ok", response.bodyText());
        assertNull(response.header("x-hop"));
        assertNull(response.header("keep-alive"));
        assertEquals("close", response.header("connection"));
    }

    @Test
    public void sseChunksReachTheClientBeforeTheUpstreamFinishes() throws Exception {
        try (Socket socket = RawHttpClient.connect(port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(("GET /proxy/sse HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String statusLine = readLine(in);
            assertNotNull(statusLine);
            assertTrue(statusLine, statusLine.contains("200"));
            assertEquals("chunked", readHeaders(in).get("transfer-encoding"));

            // The upstream sends the first event and then blocks on this gate. Receiving the first
            // chunk here proves the proxy flushed it instead of waiting for the whole response.
            String first = readChunk(in);
            assertNotNull(first);
            assertTrue(first, first.contains("data: one"));

            upstream.openSseGate();
            String second = readChunk(in);
            assertNotNull(second);
            assertTrue(second, second.contains("data: two"));
            assertNull(readChunk(in));
        }
    }

    @Test
    public void eachProxiedRequestUsesOneUpstreamConnection() throws Exception {
        try (Socket socket = RawHttpClient.connect(port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("GET /pool/hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            RawHttpClient.Response first = RawHttpClient.read(in);

            out.write("GET /pool/hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            RawHttpClient.Response second = RawHttpClient.read(in);

            assertEquals(200, first.status);
            assertEquals("hello", first.bodyText());
            assertEquals(200, second.status);
            assertEquals("hello", second.bodyText());
        }
        assertEquals("M3 never pools upstream connections", 2, upstream.connectionCount());
    }

    @Test
    public void upstreamClosingWithoutAResponseReturns502() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "localhost", "/proxy/close");

        assertEquals(502, response.status);
    }

    @Test
    public void silentUpstreamReturns504AfterTheReadTimeout() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "localhost", "/hang/hang");

        assertEquals(504, response.status);
    }

    @Test
    public void largeResponseArrivesIntact() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "localhost", "/proxy/big");

        assertEquals(200, response.status);
        assertEquals(BIG_SIZE, response.body.length);
        assertArrayEquals(BIG_BODY, response.body);
    }

    @Test
    public void streamsTheRequestBodyToTheUpstream() throws Exception {
        byte[] body = "hello world".getBytes(StandardCharsets.US_ASCII);
        try (Socket socket = RawHttpClient.connect(port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(("POST /proxy/echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            RawHttpClient.Response response = RawHttpClient.read(in);
            assertEquals(200, response.status);
            assertEquals("hello world", response.bodyText());
        }
    }

    @Test
    public void staticLocationsStillWorkNextToProxyLocations() throws IOException {
        RawHttpClient.Response response = RawHttpClient.get(port, "localhost", "/");

        assertEquals(200, response.status);
        assertEquals("static", response.bodyText());
    }

    /**
     * Opt-in soak: a 256 MB response read at roughly 64 KB/ms under a small heap. If the proxy
     * buffered the response instead of applying backpressure this would OOM. Run with:
     * {@code mvn -B test -Dtest=ProxyIntegrationTest#slowClientBackpressureSoak \
     * -Dnginx4j.soak=true -DargLine="-Xmx128m -Dio.netty.leakDetection.level=PARANOID"}.
     */
    @Test
    public void slowClientBackpressureSoak() throws Exception {
        Assume.assumeTrue("enable with -Dnginx4j.soak=true", Boolean.getBoolean("nginx4j.soak"));

        try (Socket socket = RawHttpClient.connect(port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write("GET /soak/soak HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String statusLine = readLine(in);
            assertNotNull(statusLine);
            assertTrue(statusLine, statusLine.contains("200"));
            assertEquals(Integer.toString(SOAK_SIZE), readHeaders(in).get("content-length"));

            byte[] buffer = new byte[64 * 1024];
            long offset = 0;
            while (offset < SOAK_SIZE) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, SOAK_SIZE - offset));
                if (read < 0) {
                    throw new IOException("truncated body at " + offset);
                }
                for (int i = 0; i < read; i++) {
                    if (buffer[i] != (byte) ((offset + i) % 251)) {
                        throw new AssertionError("body corrupted at " + (offset + i));
                    }
                }
                offset += read;
                // Slower than the upstream can produce, so backpressure has to kick in.
                Thread.sleep(1L);
            }
            assertEquals(SOAK_SIZE, offset);
        }
    }

    @Test
    public void idleProxiedConnectionIsClosedByTheSharedKeepAliveTimer() throws Exception {
        try (Socket socket = RawHttpClient.connect(port)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write("GET /pool/hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals(200, RawHttpClient.read(in).status);

            socket.setSoTimeout(8000);
            assertEquals("the proxy path must start the shared keep-alive clock",
                    -1, in.read());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String slash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
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

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("truncated chunk: got " + offset + " of " + length);
            }
            offset += read;
        }
        return data;
    }

    private static String readChunk(InputStream in) throws IOException {
        String sizeLine = readLine(in);
        if (sizeLine == null) {
            throw new IOException("missing chunk size");
        }
        int size = Integer.parseInt(sizeLine.trim(), 16);
        if (size == 0) {
            readLine(in);
            return null;
        }
        String data = new String(readExactly(in, size), StandardCharsets.UTF_8);
        readLine(in);
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
                    // Temp-directory cleanup must never fail a test.
                }
            }
        } catch (IOException ignored) {
            // Temp-directory cleanup must never fail a test.
        }
    }

    /** Minimal raw-socket upstream: one request per connection, exactly like the M3 proxy sends. */
    private static final class MockUpstream implements AutoCloseable {

        private final ServerSocket server;
        private final Thread acceptThread;
        private final AtomicInteger connections = new AtomicInteger();
        private final CountDownLatch sseGate = new CountDownLatch(1);
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        private volatile boolean closed;

        MockUpstream() throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "mock-upstream-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int connectionCount() {
            return connections.get();
        }

        void openSseGate() {
            sseGate.countDown();
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    final Socket socket = server.accept();
                    connections.incrementAndGet();
                    sockets.add(socket);
                    Thread handler = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handle(socket);
                        }
                    }, "mock-upstream-connection");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket socket) {
            try (Socket connection = socket) {
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                String requestLine = readLine(in);
                if (requestLine == null) {
                    return;
                }
                Map<String, String> headers = new LinkedHashMap<String, String>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                                line.substring(colon + 1).trim());
                    }
                }
                byte[] requestBody = new byte[0];
                String contentLength = headers.get("content-length");
                if (contentLength != null) {
                    requestBody = readExactly(in, Integer.parseInt(contentLength));
                }
                String path = requestLine.split(" ")[1];

                if (path.endsWith("/hello")) {
                    respond(out, "hello".getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("/headers")) {
                    respondHeaders(out, requestLine, headers);
                } else if (path.endsWith("/hop")) {
                    writeHopByHopResponse(out);
                } else if (path.endsWith("/sse")) {
                    writeSse(out);
                } else if (path.endsWith("/big")) {
                    writeBig(out);
                } else if (path.endsWith("/echo")) {
                    respond(out, requestBody);
                } else if (path.endsWith("/soak")) {
                    writeSoak(out);
                } else if (path.endsWith("/close")) {
                    // Close the upstream connection without any response: the proxy must answer 502.
                    return;
                } else if (path.startsWith("/hang/")) {
                    Thread.sleep(5000L);
                } else {
                    respond(out, "missing".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // The proxy closing a connection early is normal in timeout tests.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void respond(OutputStream out, byte[] body) throws IOException {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: keep-alive, X-Hop\r\n"
                    + "Keep-Alive: timeout=5\r\n"
                    + "X-Hop: secret\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        private void respondHeaders(OutputStream out, String requestLine,
                Map<String, String> headers) throws IOException {
            StringBuilder body = new StringBuilder(requestLine).append('\n');
            for (Map.Entry<String, String> header : headers.entrySet()) {
                body.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
            }
            respond(out, body.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void writeHopByHopResponse(OutputStream out) throws IOException {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Length: 2\r\n"
                    + "Connection: keep-alive, X-Hop\r\n"
                    + "Keep-Alive: timeout=5\r\n"
                    + "X-Hop: secret\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write("ok".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void writeSse(OutputStream out) throws IOException, InterruptedException {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            writeChunk(out, "data: one\n\n");
            if (!sseGate.await(5, TimeUnit.SECONDS)) {
                return;
            }
            writeChunk(out, "data: two\n\n");
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void writeBig(OutputStream out) throws IOException {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + BIG_SIZE + "\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            int offset = 0;
            while (offset < BIG_SIZE) {
                int length = Math.min(64 * 1024, BIG_SIZE - offset);
                out.write(BIG_BODY, offset, length);
                out.flush();
                offset += length;
            }
        }

        private void writeSoak(OutputStream out) throws IOException {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + SOAK_SIZE + "\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[64 * 1024];
            int offset = 0;
            while (offset < SOAK_SIZE) {
                int length = Math.min(chunk.length, SOAK_SIZE - offset);
                for (int i = 0; i < length; i++) {
                    chunk[i] = (byte) ((offset + i) % 251);
                }
                out.write(chunk, 0, length);
                out.flush();
                offset += length;
            }
        }

        private static void writeChunk(OutputStream out, String data) throws IOException {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            out.write((Integer.toHexString(bytes.length) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() {
            closed = true;
            try {
                server.close();
            } catch (IOException ignored) {
                // closing a test server is best effort
            }
            synchronized (sockets) {
                for (Socket socket : sockets) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // closing a test socket is best effort
                    }
                }
            }
        }
    }
}
