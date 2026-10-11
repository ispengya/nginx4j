package com.ispengya.nginx4j.server;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders an nginx-style {@code autoindex} HTML page: entries sorted case-insensitively by name,
 * a parent link, names URL-encoded for the link target and HTML-escaped for display.
 */
public final class AutoIndexRenderer {

    private static final int NAME_COLUMN_WIDTH = 50;
    private static final DateTimeFormatter LISTING_DATE = DateTimeFormatter
            .ofPattern("dd-MMM-yyyy HH:mm", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private AutoIndexRenderer() {
    }

    /**
     * Renders the listing of a directory.
     *
     * @param directory directory to list
     * @param requestPath decoded request path, used in the title and must end with {@code /}
     * @return the complete HTML document
     * @throws IOException if the directory cannot be read at all
     */
    public static String render(Path directory, String requestPath) throws IOException {
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        }
        Collections.sort(entries, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.getFileName().toString()
                        .compareToIgnoreCase(right.getFileName().toString());
            }
        });

        StringBuilder html = new StringBuilder(2048);
        html.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>Index of ")
                .append(escapeText(requestPath))
                .append("</title>\n</head>\n<body>\n<h1>Index of ")
                .append(escapeText(requestPath))
                .append("</h1>\n<hr><pre>");
        if (!"/".equals(requestPath)) {
            html.append("\n<a href=\"../\">../</a>");
        }
        for (Path entry : entries) {
            appendEntry(html, entry);
        }
        html.append("\n</pre><hr></body>\n</html>\n");
        return html.toString();
    }

    private static void appendEntry(StringBuilder html, Path entry) {
        String name = entry.getFileName().toString();
        boolean directory = Files.isDirectory(entry);
        String display = directory ? name + '/' : name;
        String modified = "-";
        String size = "-";
        try {
            FileTime lastModified = Files.getLastModifiedTime(entry);
            modified = LISTING_DATE.format(lastModified.toInstant());
            if (!directory) {
                size = Long.toString(Files.size(entry));
            }
        } catch (IOException e) {
            // Keep the placeholders: one unreadable entry must not fail the whole listing.
        }

        html.append("\n<a href=\"").append(escapeAttribute(linkTarget(name, directory))).append("\">")
                .append(escapeText(display)).append("</a>");
        for (int i = Math.max(1, NAME_COLUMN_WIDTH - display.length()); i > 0; i--) {
            html.append(' ');
        }
        html.append(modified).append("  ").append(size);
    }

    private static String linkTarget(String name, boolean directory) {
        final String encoded;
        try {
            // URLEncoder targets query strings, so spaces must be corrected back to %20.
            encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always available", e);
        }
        return directory ? encoded + '/' : encoded;
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttribute(String value) {
        return escapeText(value).replace("\"", "&quot;").replace("'", "&#39;");
    }
}