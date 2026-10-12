package com.ispengya.nginx4j.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 client for the integration tests.
 *
 * <p>Raw sockets are deliberate: the JDK clients refuse to let a test set the {@code Host} header,
 * and virtual-host routing is exactly what these tests need to exercise.
 */
final class RawHttpClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private RawHttpClient() {
    }

    /** Sends {@code GET path} with an explicit {@code Host} header and reads the response. */
    static Response get(int port, String host, String path, String... extraHeaders) throws IOException {
        StringBuilder request = new StringBuilder();
        request.append("GET ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host).append("\r\n");
        for (String header : extraHeaders) {
            request.append(header).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        return send(port, request.toString());
    }

    /** Sends a verbatim request, for keep-alive tests that need one socket across two requests. */
    static Response send(int port, String rawRequest) throws IOException {
        try (Socket socket = connect(port)) {
            OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return read(socket.getInputStream());
        }
    }

    static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        return socket;
    }

    static Response read(InputStream in) throws IOException {
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
        return new Response(Integer.parseInt(parts[1]), headers, body);
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

    static final class Response {

        final int status;
        final Map<String, String> headers;
        final byte[] body;

        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}