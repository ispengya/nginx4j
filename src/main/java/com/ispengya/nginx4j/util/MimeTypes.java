package com.ispengya.nginx4j.util;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * File extension to {@code Content-Type} mapping, mirroring nginx's bundled mime.types for the
 * common web formats. Textual types carry an explicit UTF-8 charset.
 */
public final class MimeTypes {

    private static final String DEFAULT_TYPE = "application/octet-stream";
    private static final Map<String, String> TYPES;

    static {
        Map<String, String> types = new HashMap<String, String>();
        types.put("html", "text/html; charset=utf-8");
        types.put("htm", "text/html; charset=utf-8");
        types.put("css", "text/css; charset=utf-8");
        types.put("js", "application/javascript; charset=utf-8");
        types.put("mjs", "application/javascript; charset=utf-8");
        types.put("json", "application/json; charset=utf-8");
        types.put("xml", "application/xml; charset=utf-8");
        types.put("txt", "text/plain; charset=utf-8");
        types.put("md", "text/plain; charset=utf-8");
        types.put("csv", "text/csv; charset=utf-8");
        types.put("png", "image/png");
        types.put("jpg", "image/jpeg");
        types.put("jpeg", "image/jpeg");
        types.put("gif", "image/gif");
        types.put("webp", "image/webp");
        types.put("bmp", "image/bmp");
        types.put("ico", "image/x-icon");
        types.put("svg", "image/svg+xml");
        types.put("pdf", "application/pdf");
        types.put("zip", "application/zip");
        types.put("gz", "application/gzip");
        types.put("tar", "application/x-tar");
        types.put("mp4", "video/mp4");
        types.put("webm", "video/webm");
        types.put("mp3", "audio/mpeg");
        types.put("wav", "audio/wav");
        types.put("woff", "font/woff");
        types.put("woff2", "font/woff2");
        types.put("ttf", "font/ttf");
        types.put("otf", "font/otf");
        types.put("wasm", "application/wasm");
        TYPES = Collections.unmodifiableMap(types);
    }

    private MimeTypes() {
    }

    /**
     * Resolves the content type of a file by extension.
     *
     * @param file file whose name is inspected; the file is not read
     * @return the content type, defaulting to {@code application/octet-stream}
     */
    public static String of(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return DEFAULT_TYPE;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = TYPES.get(extension);
        return type == null ? DEFAULT_TYPE : type;
    }
}