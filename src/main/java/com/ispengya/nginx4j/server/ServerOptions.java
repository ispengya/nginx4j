package com.ispengya.nginx4j.server;

import com.ispengya.nginx4j.conf.ConfParser;
import com.ispengya.nginx4j.conf.HttpBlock;
import com.ispengya.nginx4j.conf.LocationBlock;
import com.ispengya.nginx4j.conf.MainBlock;
import com.ispengya.nginx4j.conf.ServerBlock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable bootstrap options.
 *
 * <p>Two sources feed one model. From M2 on, {@code -c/--conf} parses the nginx.conf subset into an
 * immutable {@link HttpBlock} that the runtime reads; without it the command line builds the
 * equivalent single-server configuration, which is what M0/M1 used and what tests and quick runs
 * still use.
 *
 * <p>The command line options that the configuration file owns are mutually exclusive with
 * {@code --conf}, so there is never a second truth to debug. {@code --worker-threads} stays
 * available because the threading model is a deployment concern, not a routing one.
 */
public final class ServerOptions {

    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_ROOT = "webroot";
    private static final int DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS = 65;
    private static final String DEFAULT_INDEX_FILES = "index.html";
    /** nginx defaults {@code autoindex} to off. */
    private static final boolean DEFAULT_AUTO_INDEX = false;

    private final int port;
    private final String host;
    private final Path root;
    private final int workerThreads;
    private final int keepAliveTimeoutSeconds;
    private final List<String> indexFiles;
    private final boolean autoIndex;
    private final Path confFile;
    private final HttpBlock http;

    private ServerOptions(int port, String host, Path root, int workerThreads,
            int keepAliveTimeoutSeconds, List<String> indexFiles, boolean autoIndex, Path confFile,
            HttpBlock http) {
        this.port = port;
        this.host = host;
        this.root = root;
        this.workerThreads = workerThreads;
        this.keepAliveTimeoutSeconds = keepAliveTimeoutSeconds;
        this.indexFiles = indexFiles;
        this.autoIndex = autoIndex;
        this.confFile = confFile;
        this.http = http;
    }

    /**
     * Parses command line arguments, failing fast on unknown, malformed or contradictory input.
     *
     * @param args raw arguments, may be empty
     * @return immutable options plus the configuration tree the server will serve
     * @throws IllegalArgumentException if an argument is unknown, malformed, conflicts with
     *         {@code --conf}, or names a directory that does not exist
     */
    public static ServerOptions parse(String[] args) {
        int port = DEFAULT_PORT;
        boolean portSeen = false;
        String root = DEFAULT_ROOT;
        boolean rootSeen = false;
        int workerThreads = Runtime.getRuntime().availableProcessors();
        boolean workerThreadsSeen = false;
        int keepAliveTimeoutSeconds = DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS;
        boolean keepAliveSeen = false;
        List<String> indexFiles = Collections.singletonList(DEFAULT_INDEX_FILES);
        boolean indexSeen = false;
        boolean autoIndex = DEFAULT_AUTO_INDEX;
        boolean autoIndexSeen = false;
        Path confFile = null;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--port":
                    i = requireValue(args, i, argument);
                    port = nonNegativeInt(args[i], argument);
                    portSeen = true;
                    break;
                case "--root":
                    i = requireValue(args, i, argument);
                    root = args[i];
                    rootSeen = true;
                    break;
                case "--worker-threads":
                    i = requireValue(args, i, argument);
                    workerThreads = positiveInt(args[i], argument);
                    workerThreadsSeen = true;
                    break;
                case "--keep-alive-timeout":
                    i = requireValue(args, i, argument);
                    keepAliveTimeoutSeconds = positiveInt(args[i], argument);
                    keepAliveSeen = true;
                    break;
                case "--index":
                    i = requireValue(args, i, argument);
                    indexFiles = parseIndexFiles(args[i], argument);
                    indexSeen = true;
                    break;
                case "--autoindex":
                    i = requireValue(args, i, argument);
                    autoIndex = parseOnOff(args[i], argument);
                    autoIndexSeen = true;
                    break;
                case "-c":
                case "--conf":
                    i = requireValue(args, i, argument);
                    if (confFile != null) {
                        throw new IllegalArgumentException(argument + " was given more than once");
                    }
                    confFile = Paths.get(args[i]).toAbsolutePath().normalize();
                    break;
                default:
                    throw new IllegalArgumentException("unknown argument: " + argument);
            }
        }

        if (confFile != null) {
            rejectConflicts(confFile, portSeen, rootSeen, indexSeen, autoIndexSeen, keepAliveSeen);
            MainBlock main = ConfParser.parseFile(confFile);
            HttpBlock http = main.http();
            validateRoots(http, confFile);
            ServerBlock firstServer = http.servers().get(0);
            int effectiveWorkerThreads = workerThreadsSeen ? workerThreads : main.workerProcesses();
            return new ServerOptions(http.listen().port(), http.listen().host(), firstServer.root(),
                    effectiveWorkerThreads, toSeconds(firstServer.keepAliveTimeoutMillis()),
                    firstServer.indexFiles(), firstServer.autoIndex(), confFile, http);
        }

        Path rootPath = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("--root directory does not exist: " + rootPath);
        }
        HttpBlock singleServer = HttpBlock.singleServer(port, rootPath, indexFiles, autoIndex,
                keepAliveTimeoutSeconds * 1000L);
        return new ServerOptions(port, null, rootPath, workerThreads, keepAliveTimeoutSeconds,
                indexFiles, autoIndex, null, singleServer);
    }

    public static String usage() {
        return "usage: java -jar nginx4j.jar [options]\n"
                + "  -c, --conf <file>            nginx.conf subset to load; mutually exclusive with\n"
                + "                               --port/--root/--index/--autoindex/--keep-alive-timeout\n"
                + "  --port <port>                listen port, 0 picks a free port (default " + DEFAULT_PORT + ")\n"
                + "  --root <dir>                 static file root (default " + DEFAULT_ROOT + ")\n"
                + "  --index <names>              comma separated index files (default " + DEFAULT_INDEX_FILES + ")\n"
                + "  --autoindex <on|off>         enable directory listings (default off)\n"
                + "  --worker-threads <n>         worker EventLoop threads (default: CPU cores)\n"
                + "  --keep-alive-timeout <sec>   idle keep-alive timeout (default "
                + DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS + ")\n"
                + "  -h, --help                   print this message";
    }

    private static void rejectConflicts(Path confFile, boolean portSeen, boolean rootSeen,
            boolean indexSeen, boolean autoIndexSeen, boolean keepAliveSeen) {
        if (portSeen) {
            throw conflict("--port", "listen", confFile);
        }
        if (rootSeen) {
            throw conflict("--root", "root", confFile);
        }
        if (indexSeen) {
            throw conflict("--index", "index", confFile);
        }
        if (autoIndexSeen) {
            throw conflict("--autoindex", "autoindex", confFile);
        }
        if (keepAliveSeen) {
            throw conflict("--keep-alive-timeout", "keepalive_timeout", confFile);
        }
    }

    private static IllegalArgumentException conflict(String argument, String directive,
            Path confFile) {
        return new IllegalArgumentException(argument + " cannot be combined with " + confFile
                + ": the '" + directive + "' directive owns this value");
    }

    /** Fails fast on a root that cannot serve anything, whichever block declared it. */
    private static void validateRoots(HttpBlock http, Path confFile) {
        Set<Path> roots = new LinkedHashSet<Path>();
        for (ServerBlock server : http.servers()) {
            roots.add(server.root());
            for (LocationBlock location : server.locations()) {
                roots.add(location.root());
            }
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("root directory declared in " + confFile
                        + " does not exist: " + root);
            }
        }
    }

    /** Rounds up to whole seconds; only used for the startup summary of a configuration file. */
    private static int toSeconds(long millis) {
        return (int) Math.max(1L, (millis + 999L) / 1000L);
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

    /** @return the requested port; {@code 0} means "pick a free port", which tests rely on */
    public int port() {
        return port;
    }

    /** @return the bind address of the configuration, or {@code null} for every local interface */
    public String host() {
        return host;
    }

    /** @return the effective root of the first server, used for the startup summary */
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

    /** @return the loaded configuration file, or {@code null} when the command line built the tree */
    public Path confFile() {
        return confFile;
    }

    /** @return the immutable server/location tree every request is routed through */
    public HttpBlock http() {
        return http;
    }
}