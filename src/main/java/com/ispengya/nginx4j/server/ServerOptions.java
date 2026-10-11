package com.ispengya.nginx4j.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable bootstrap options parsed from the command line.
 *
 * <p>M0/M1 stand-in for the nginx.conf parser that arrives in M2; the field set mirrors the subset
 * of directives implemented so far ({@code root}, {@code index}, {@code autoindex},
 * {@code keepalive_timeout}, plus the worker count of the frozen threading model).
 */
public final class ServerOptions {

    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_ROOT = "webroot";
    private static final int DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS = 65;
    private static final String DEFAULT_INDEX_FILES = "index.html";
    /** nginx defaults {@code autoindex} to off. */
    private static final boolean DEFAULT_AUTO_INDEX = false;

    private final int port;
    private final Path root;
    private final int workerThreads;
    private final int keepAliveTimeoutSeconds;
    private final List<String> indexFiles;
    private final boolean autoIndex;

    private ServerOptions(int port, Path root, int workerThreads, int keepAliveTimeoutSeconds,
            List<String> indexFiles, boolean autoIndex) {
        this.port = port;
        this.root = root;
        this.workerThreads = workerThreads;
        this.keepAliveTimeoutSeconds = keepAliveTimeoutSeconds;
        this.indexFiles = indexFiles;
        this.autoIndex = autoIndex;
    }

    /**
     * Parses command line arguments, failing fast on unknown or malformed input.
     *
     * @param args raw arguments, may be empty
     * @return immutable options
     * @throws IllegalArgumentException if an argument is unknown, malformed, or the root is missing
     */
    public static ServerOptions parse(String[] args) {
        int port = DEFAULT_PORT;
        String root = DEFAULT_ROOT;
        int workerThreads = Runtime.getRuntime().availableProcessors();
        int keepAliveTimeoutSeconds = DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS;
        List<String> indexFiles = Collections.singletonList(DEFAULT_INDEX_FILES);
        boolean autoIndex = DEFAULT_AUTO_INDEX;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--port":
                    i = requireValue(args, i, argument);
                    port = nonNegativeInt(args[i], argument);
                    break;
                case "--root":
                    i = requireValue(args, i, argument);
                    root = args[i];
                    break;
                case "--worker-threads":
                    i = requireValue(args, i, argument);
                    workerThreads = positiveInt(args[i], argument);
                    break;
                case "--keep-alive-timeout":
                    i = requireValue(args, i, argument);
                    keepAliveTimeoutSeconds = positiveInt(args[i], argument);
                    break;
                case "--index":
                    i = requireValue(args, i, argument);
                    indexFiles = parseIndexFiles(args[i], argument);
                    break;
                case "--autoindex":
                    i = requireValue(args, i, argument);
                    autoIndex = parseOnOff(args[i], argument);
                    break;
                default:
                    throw new IllegalArgumentException("unknown argument: " + argument);
            }
        }

        Path rootPath = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("--root directory does not exist: " + rootPath);
        }
        return new ServerOptions(port, rootPath, workerThreads, keepAliveTimeoutSeconds, indexFiles,
                autoIndex);
    }

    public static String usage() {
        return "usage: java -jar nginx4j.jar [options]\n"
                + "  --port <port>                listen port, 0 picks a free port (default " + DEFAULT_PORT + ")\n"
                + "  --root <dir>                 static file root (default " + DEFAULT_ROOT + ")\n"
                + "  --index <names>              comma separated index files (default " + DEFAULT_INDEX_FILES + ")\n"
                + "  --autoindex <on|off>         enable directory listings (default off)\n"
                + "  --worker-threads <n>         worker EventLoop threads (default: CPU cores)\n"
                + "  --keep-alive-timeout <sec>   idle keep-alive timeout (default "
                + DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS + ")\n"
                + "  -h, --help                   print this message";
    }

    private static int requireValue(String[] args, int currentIndex, String argument) {
        if (currentIndex + 1 >= args.length) {
            throw new IllegalArgumentException(argument + " requires a value");
        }
        return currentIndex + 1;
    }

    private static List<String> parseIndexFiles(String value, String argument) {
        List<String> names = new ArrayList<String>();
        // Keep trailing empty entries so that "index.html," is reported as a config error.
        for (String candidate : value.split(",", -1)) {
            String name = candidate.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(argument + " contains an empty file name: " + value);
            }
            names.add(name);
        }
        return Collections.unmodifiableList(names);
    }

    private static boolean parseOnOff(String value, String argument) {
        if ("on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("off".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(argument + " must be on or off, got: " + value);
    }

    private static int positiveInt(String value, String argument) {
        int parsed = parseNumber(value, argument);
        if (parsed <= 0) {
            throw new IllegalArgumentException(argument + " must be positive, got: " + value);
        }
        return parsed;
    }

    private static int nonNegativeInt(String value, String argument) {
        int parsed = parseNumber(value, argument);
        if (parsed < 0) {
            throw new IllegalArgumentException(argument + " must not be negative, got: " + value);
        }
        return parsed;
    }

    private static int parseNumber(String value, String argument) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(argument + " must be a number, got: " + value);
        }
    }

    public int port() {
        return port;
    }

    public Path root() {
        return root;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int keepAliveTimeoutSeconds() {
        return keepAliveTimeoutSeconds;
    }

    public List<String> indexFiles() {
        return indexFiles;
    }

    public boolean autoIndex() {
        return autoIndex;
    }
}