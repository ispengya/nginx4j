package com.ispengya.nginx4j.server;

import com.ispengya.nginx4j.util.HttpDates;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Handler-level tests for the M1 static content semantics. {@link EmbeddedChannel} keeps the
 * requests in memory, so every Range/validator edge case runs in milliseconds without sockets.
 */
public class StaticFileHandlerTest {

    private static final byte[] DATA_BYTES = new byte[256];
    private static final String INDEX_BODY = "root-index";
    private static final String SUB_INDEX_BODY = "sub-index";

    private Path workspace;
    private Path root;
    private EmbeddedChannel channel;

    @Before
    public void setUp() throws IOException {
        workspace = Files.createTempDirectory("nginx4j-static-test");
        root = Files.createDirectories(workspace.resolve("root"));
        Files.write(root.resolve("index.html"), INDEX_BODY.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("sub"));
        Files.write(root.resolve("sub/index.html"), SUB_INDEX_BODY.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("listing"));
        Files.write(root.resolve("listing/a.txt"), "aaa".getBytes(StandardCharsets.UTF_8));
        // Legal on Windows while still requiring HTML escaping and URL encoding in the listing.
        Files.write(root.resolve("listing/a & b.txt"), "escaped".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("listing/other.html"), "other".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < DATA_BYTES.length; i++) {
            DATA_BYTES[i] = (byte) i;
        }
        Files.write(root.resolve("data.bin"), DATA_BYTES);
        // Sibling of the root, used to prove the traversal guard.
        Files.write(workspace.resolve("outside.txt"), "secret".getBytes(StandardCharsets.UTF_8));
        channel = newChannel(false);
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
        deleteRecursivelyQuietly(workspace);
    }

    @Test
    public void rootPathServesConfiguredIndex() throws IOException {
        List<Object> messages = request(channel, "GET", "/");
        assertEquals(200, responseOf(messages).status().code());
        assertArrayEquals(INDEX_BODY.getBytes(StandardCharsets.UTF_8), bytesOf(regionOf(messages)));
    }

    @Test
    public void wholeFileCarriesValidatorsAndAcceptRanges() throws IOException {
        List<Object> messages = request(channel, "GET", "/index.html");
        HttpResponse response = responseOf(messages);

        assertEquals(200, response.status().code());
        assertEquals("text/html; charset=utf-8", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals("bytes", response.headers().get(HttpHeaderNames.ACCEPT_RANGES));
        assertEquals(Integer.toString(INDEX_BODY.getBytes(StandardCharsets.UTF_8).length),
                response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertNotNull(response.headers().get(HttpHeaderNames.ETAG));
        assertNotNull(response.headers().get(HttpHeaderNames.LAST_MODIFIED));
        assertArrayEquals(INDEX_BODY.getBytes(StandardCharsets.UTF_8), bytesOf(regionOf(messages)));
    }

    @Test
    public void rangeRequestsReturnExactSlices() throws IOException {
        assertRange("bytes=2-5", 2, 4);
        assertRange("bytes=250-", 250, 6);
        assertRange("bytes=-4", 252, 4);
        assertRange("bytes=0-9999", 0, 256);
    }

    @Test
    public void unsatisfiableRangeReturns416() {
        List<Object> messages = request(channel, "GET", "/data.bin", "Range", "bytes=300-400");
        HttpResponse response = responseOf(messages);

        assertEquals(416, response.status().code());
        assertEquals("bytes */256", response.headers().get(HttpHeaderNames.CONTENT_RANGE));
        assertNull(regionOf(messages));
    }

    @Test
    public void malformedAndMultiRangeFallBackToWholeEntity() throws IOException {
        for (String header : new String[] {"bytes=abc", "bytes=0-1,3-4", "items=0-5", "bytes="}) {
            EmbeddedChannel fresh = newChannel(false);
            try {
                List<Object> messages = request(fresh, "GET", "/data.bin", "Range", header);
                assertEquals(header, 200, responseOf(messages).status().code());
                assertArrayEquals(header, DATA_BYTES, bytesOf(regionOf(messages)));
            } finally {
                fresh.finishAndReleaseAll();
            }
        }
    }

    @Test
    public void headWithRangeSendsHeadersButNoBody() {
        List<Object> messages = request(channel, "HEAD", "/data.bin", "Range", "bytes=0-9");
        HttpResponse response = responseOf(messages);

        assertEquals(206, response.status().code());
        assertEquals("10", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertNull(regionOf(messages));
    }

    @Test
    public void ifNoneMatchOnCurrentTagReturns304WithoutBody() {
        String etag = responseOf(request(channel, "GET", "/index.html")).headers().get(HttpHeaderNames.ETAG);
        assertNotNull(etag);

        List<Object> messages = request(channel, "GET", "/index.html", "If-None-Match", etag);
        HttpResponse response = responseOf(messages);

        assertEquals(304, response.status().code());
        assertEquals(etag, response.headers().get(HttpHeaderNames.ETAG));
        assertNull(response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertNull(regionOf(messages));
    }

    @Test
    public void ifNoneMatchStarMatchesAndStaleTagDoesNot() {
        assertEquals(304, responseOf(request(channel, "GET", "/index.html", "If-None-Match", "*"))
                .status().code());
        assertEquals(200, responseOf(request(channel, "GET", "/index.html", "If-None-Match", "\"stale\""))
                .status().code());
    }

    @Test
    public void ifModifiedSinceIsEvaluatedAtSecondResolution() throws IOException {
        long lastModified = Files.getLastModifiedTime(root.resolve("index.html")).toMillis();

        String sameSecond = HttpDates.format(new Date(lastModified));
        assertEquals(304, responseOf(request(channel, "GET", "/index.html", "If-Modified-Since", sameSecond))
                .status().code());

        String older = HttpDates.format(new Date(lastModified - 5000L));
        assertEquals(200, responseOf(request(channel, "GET", "/index.html", "If-Modified-Since", older))
                .status().code());
    }

    @Test
    public void failedIfRangeValidatorDowngradesToFull200() throws IOException {
        List<Object> messages = request(channel, "GET", "/data.bin",
                "Range", "bytes=0-9", "If-Range", "\"stale\"");
        assertEquals(200, responseOf(messages).status().code());
        assertArrayEquals(DATA_BYTES, bytesOf(regionOf(messages)));
    }

    @Test
    public void matchingIfRangeValidatorKeepsTheRange() throws IOException {
        String etag = responseOf(request(channel, "GET", "/data.bin")).headers().get(HttpHeaderNames.ETAG);
        List<Object> messages = request(channel, "GET", "/data.bin",
                "Range", "bytes=0-9", "If-Range", etag);

        assertEquals(206, responseOf(messages).status().code());
        assertArrayEquals(Arrays.copyOfRange(DATA_BYTES, 0, 10), bytesOf(regionOf(messages)));
    }

    @Test
    public void directoryWithoutTrailingSlashRedirectsAndKeepsQuery() {
        HttpResponse redirect = responseOf(request(channel, "GET", "/sub"));
        assertEquals(301, redirect.status().code());
        assertEquals("/sub/", redirect.headers().get(HttpHeaderNames.LOCATION));

        HttpResponse withQuery = responseOf(request(channel, "GET", "/sub?a=1&b=2"));
        assertEquals(301, withQuery.status().code());
        assertEquals("/sub/?a=1&b=2", withQuery.headers().get(HttpHeaderNames.LOCATION));
    }

    @Test
    public void configuredIndexFileIsServedForDirectories() throws IOException {
        List<Object> messages = request(channel, "GET", "/sub/");
        assertEquals(200, responseOf(messages).status().code());
        assertArrayEquals(SUB_INDEX_BODY.getBytes(StandardCharsets.UTF_8), bytesOf(regionOf(messages)));
    }

    @Test
    public void indexFileListOrderIsHonoured() throws IOException {
        EmbeddedChannel custom = new EmbeddedChannel(new StaticFileHandler(root,
                Arrays.asList("other.html", "index.html"), false, 30));
        try {
            List<Object> messages = request(custom, "GET", "/listing/");
            assertEquals(200, responseOf(messages).status().code());
            assertArrayEquals("other".getBytes(StandardCharsets.UTF_8), bytesOf(regionOf(messages)));
        } finally {
            custom.finishAndReleaseAll();
        }
    }

    @Test
    public void directoryWithoutIndexIsForbiddenWhenAutoIndexIsOff() {
        assertEquals(403, responseOf(request(channel, "GET", "/listing/")).status().code());
    }

    @Test
    public void autoIndexListsEscapedAndEncodedEntries() {
        EmbeddedChannel listing = newChannel(true);
        try {
            List<Object> messages = request(listing, "GET", "/listing/");
            HttpResponse response = responseOf(messages);
            String html = bodyOf(messages);

            assertEquals(200, response.status().code());
            assertEquals("text/html; charset=utf-8", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            assertTrue(html, html.contains("a.txt"));
            assertTrue(html, html.contains("a &amp; b.txt"));
            assertTrue(html, html.contains("href=\"a%20%26%20b.txt\""));
            assertTrue(html, html.contains("href=\"../\""));
        } finally {
            listing.finishAndReleaseAll();
        }
    }

    @Test
    public void traversalAndUnknownPathsReturn404() {
        assertEquals(404, responseOf(request(channel, "GET", "/../outside.txt")).status().code());
        assertEquals(404, responseOf(request(channel, "GET", "/nope.txt")).status().code());
    }

    @Test
    public void nonGetMethodsAreRejectedWithClose() {
        HttpResponse response = responseOf(request(channel, "POST", "/index.html"));
        assertEquals(405, response.status().code());
        assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
    }

    private void assertRange(String headerValue, long expectedStart, long expectedLength)
            throws IOException {
        EmbeddedChannel fresh = newChannel(false);
        try {
            List<Object> messages = request(fresh, "GET", "/data.bin", "Range", headerValue);
            HttpResponse response = responseOf(messages);
            FileRegion region = regionOf(messages);

            assertEquals(headerValue, 206, response.status().code());
            assertEquals(headerValue, "bytes " + expectedStart + "-"
                    + (expectedStart + expectedLength - 1) + "/256",
                    response.headers().get(HttpHeaderNames.CONTENT_RANGE));
            assertEquals(headerValue, Long.toString(expectedLength),
                    response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
            assertNotNull(headerValue, region);
            assertEquals(headerValue, expectedLength, region.count());
            assertArrayEquals(headerValue,
                    Arrays.copyOfRange(DATA_BYTES, (int) expectedStart, (int) (expectedStart + expectedLength)),
                    bytesOf(region));
        } finally {
            fresh.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel newChannel(boolean autoIndex) {
        return new EmbeddedChannel(new StaticFileHandler(root,
                Collections.singletonList("index.html"), autoIndex, 30));
    }

    private static List<Object> request(EmbeddedChannel channel, String method, String uri,
            String... headers) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.valueOf(method), uri);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.headers().set(headers[i], headers[i + 1]);
        }
        channel.writeInbound(request);

        List<Object> messages = new ArrayList<Object>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            messages.add(message);
        }
        return messages;
    }

    private static HttpResponse responseOf(List<Object> messages) {
        for (Object message : messages) {
            if (message instanceof HttpResponse) {
                return (HttpResponse) message;
            }
        }
        throw new AssertionError("no HTTP response in " + messages);
    }

    private static FileRegion regionOf(List<Object> messages) {
        for (Object message : messages) {
            if (message instanceof FileRegion) {
                return (FileRegion) message;
            }
        }
        return null;
    }

    private static String bodyOf(List<Object> messages) {
        for (Object message : messages) {
            if (message instanceof FullHttpResponse) {
                return ((FullHttpResponse) message).content().toString(CharsetUtil.UTF_8);
            }
        }
        throw new AssertionError("no full response in " + messages);
    }

    /**
     * Reads the bytes a {@link FileRegion} would put on the wire, which proves both the slice offset
     * and its length without touching a socket.
     */
    private static byte[] bytesOf(FileRegion region) throws IOException {
        assertNotNull("expected a file region", region);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(out);
        try {
            long position = 0;
            long transferred;
            while ((transferred = region.transferTo(target, position)) > 0) {
                position += transferred;
            }
        } finally {
            target.close();
            ReferenceCountUtil.release(region);
        }
        return out.toByteArray();
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
        } catch (IOException e) {
            fail("cannot clean up " + directory + ": " + e.getMessage());
        }
    }
}