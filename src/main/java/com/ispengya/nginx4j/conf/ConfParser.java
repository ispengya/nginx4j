package com.ispengya.nginx4j.conf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Recursive-descent parser for the nginx.conf subset documented in PLAN.md section 8.1.
 *
 * <p>The parser is deliberately strict: a directive outside the supported table is an error that
 * names the milestone which will implement it, never a silent no-op. A configuration file therefore
 * never promises behaviour the server does not have.
 *
 * <p>Inheritance is folded at parse time (PLAN.md section 8.2): every block stores the value that is
 * in effect at runtime, so the router never walks a parent chain and cannot get inheritance wrong.
 *
 * <p>All mutable state lives in the parser instance created by one {@code parse*} call. The returned
 * tree is immutable and is exactly the snapshot a later reload will swap in atomically.
 */
public final class ConfParser {

    /** nginx default for {@code index} when the directive is absent. */
    public static final String DEFAULT_INDEX_FILE = "index.html";
    /** Default {@code keepalive_timeout}, matching the M0/M1 command line default of 65 seconds. */
    public static final long DEFAULT_KEEP_ALIVE_TIMEOUT_MILLIS = 65000L;

    /** Used when the configuration has no {@code events} block. */
    private static final int DEFAULT_WORKER_CONNECTIONS = 1024;
    /** nginx's conventional "this server answers anything" name. */
    private static final String CATCH_ALL_SERVER_NAME = "_";

    private final String source;
    private final Path baseDir;
    private final List<ConfTokenizer.Token> tokens;
    private int position;

    private ConfParser(String source, Path baseDir, List<ConfTokenizer.Token> tokens) {
        this.source = source;
        this.baseDir = baseDir;
        this.tokens = tokens;
    }

    /**
     * Parses a configuration file.
     *
     * @param confFile file to read, resolved against the current directory
     * @return the immutable configuration tree
     * @throws ConfException on any syntax or semantic error
     * @throws IllegalArgumentException when the file cannot be read at all
     */
    public static MainBlock parseFile(Path confFile) {
        final Path absolute = confFile.toAbsolutePath().normalize();
        final String text;
        try {
            text = new String(Files.readAllBytes(absolute), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "cannot read configuration file " + absolute + ": " + e.getMessage(), e);
        }
        Path parent = absolute.getParent();
        return parseString(absolute.toString(), text, parent == null ? Paths.get(".") : parent);
    }

    /**
     * Parses configuration text.
     *
     * <p>Exists so that tests, and any future inline directive support, do not need a temporary file.
     *
     * @param source name used in error messages, normally the file path
     * @param text configuration text, with or without a UTF-8 byte order mark
     * @param baseDir directory that relative {@code root} values resolve against
     */
    public static MainBlock parseString(String source, String text, Path baseDir) {
        String content = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<ConfTokenizer.Token> tokens = new ConfTokenizer(source, content).tokenize();
        Path resolvedBase = baseDir.toAbsolutePath().normalize();
        return new ConfParser(source, resolvedBase, tokens).parseMain();
    }

    // ------------------------------------------------------------------ contexts

    private MainBlock parseMain() {
        int workerProcesses = Runtime.getRuntime().availableProcessors();
        boolean workerProcessesSeen = false;
        EventsBlock events = new EventsBlock(DEFAULT_WORKER_CONNECTIONS);
        HttpBlock http = null;

        while (!atEnd()) {
            Directive directive = nextDirective();
            if ("worker_processes".equals(directive.name)) {
                requireSimple(directive);
                requireFirstOccurrence(workerProcessesSeen, directive);
                workerProcesses = parseWorkerProcesses(directive);
                workerProcessesSeen = true;
            } else if ("events".equals(directive.name)) {
                requireBlock(directive);
                requireNoArguments(directive);
                events = parseEvents();
            } else if ("http".equals(directive.name)) {
                requireBlock(directive);
                requireNoArguments(directive);
                if (http != null) {
                    throw error(directive.nameToken,
                            "a second 'http' block is not supported in M2");
                }
                http = parseHttp(directive);
            } else {
                throw unknownDirective(directive, "worker_processes", "events", "http");
            }
        }
        if (http == null) {
            throw error(peek(), "missing 'http { ... }' block");
        }
        return new MainBlock(workerProcesses, events, http);
    }

    private EventsBlock parseEvents() {
        int workerConnections = DEFAULT_WORKER_CONNECTIONS;
        boolean workerConnectionsSeen = false;

        while (!atBlockEnd()) {
            Directive directive = nextDirective();
            if (!"worker_connections".equals(directive.name)) {
                throw unknownDirective(directive, "worker_connections");
            }
            requireSimple(directive);
            requireFirstOccurrence(workerConnectionsSeen, directive);
            requireArgumentCount(directive, 1);
            workerConnections = positiveInt(requireArgument(directive, 0));
            workerConnectionsSeen = true;
        }
        expectBlockEnd("events");
        return new EventsBlock(workerConnections);
    }

    private HttpBlock parseHttp(Directive httpDirective) {
        Overrides httpOverrides = new Overrides();
        List<ServerBuilder> serverBuilders = new ArrayList<ServerBuilder>();

        while (!atBlockEnd()) {
            Directive directive = nextDirective();
            if (applyInheritable(httpOverrides, directive)) {
                continue;
            }
            if ("server".equals(directive.name)) {
                requireBlock(directive);
                requireNoArguments(directive);
                serverBuilders.add(parseServer(directive));
                continue;
            }
            throw unknownDirective(directive, "keepalive_timeout", "root", "index", "autoindex",
                    "server");
        }
        expectBlockEnd("http");

        if (serverBuilders.isEmpty()) {
            throw error(httpDirective.nameToken,
                    "'http' block needs at least one 'server' block");
        }
        return buildHttpBlock(httpDirective, httpOverrides, serverBuilders);
    }

    private ServerBuilder parseServer(Directive serverDirective) {
        ServerBuilder server = new ServerBuilder(serverDirective.nameToken);

        while (!atBlockEnd()) {
            Directive directive = nextDirective();
            if (applyInheritable(server.overrides, directive)) {
                continue;
            }
            if ("listen".equals(directive.name)) {
                requireSimple(directive);
                server.listeners.add(parseListen(directive));
                continue;
            }
            if ("server_name".equals(directive.name)) {
                requireSimple(directive);
                server.serverNames.addAll(parseServerNames(directive));
                continue;
            }
            if ("location".equals(directive.name)) {
                requireBlock(directive);
                server.locations.add(parseLocation(directive));
                continue;
            }
            throw unknownDirective(directive, "listen", "server_name", "root", "index", "autoindex",
                    "keepalive_timeout", "location");
        }
        expectBlockEnd("server");
        return server;
    }

    private LocationBuilder parseLocation(Directive locationDirective) {
        LocationBuilder location = parseLocationHeader(locationDirective);

        while (!atBlockEnd()) {
            Directive directive = nextDirective();
            if (applyInheritable(location.overrides, directive)) {
                continue;
            }
            if ("location".equals(directive.name)) {
                throw error(directive.nameToken,
                        "nested 'location' blocks are not supported in M2");
            }
            throw unknownDirective(directive, "root", "index", "autoindex", "keepalive_timeout");
        }
        expectBlockEnd("location");
        return location;
    }

    // ------------------------------------------------------------------ directives

    /** Applies one of the four inheritable directives, or reports that the name is something else. */
    private boolean applyInheritable(Overrides overrides, Directive directive) {
        if ("root".equals(directive.name)) {
            requireSimple(directive);
            requireFirstOccurrence(overrides.rootSeen, directive);
            requireArgumentCount(directive, 1);
            overrides.root = parseRoot(requireArgument(directive, 0));
            overrides.rootSeen = true;
            return true;
        }
        if ("index".equals(directive.name)) {
            requireSimple(directive);
            requireFirstOccurrence(overrides.indexSeen, directive);
            overrides.indexFiles = parseIndexFiles(directive);
            overrides.indexSeen = true;
            return true;
        }
        if ("autoindex".equals(directive.name)) {
            requireSimple(directive);
            requireFirstOccurrence(overrides.autoIndexSeen, directive);
            requireArgumentCount(directive, 1);
            overrides.autoIndex = Boolean.valueOf(parseOnOff(requireArgument(directive, 0)));
            overrides.autoIndexSeen = true;
            return true;
        }
        if ("keepalive_timeout".equals(directive.name)) {
            requireSimple(directive);
            requireFirstOccurrence(overrides.keepAliveSeen, directive);
            overrides.keepAliveTimeoutMillis = Long.valueOf(parseKeepAliveTimeout(directive));
            overrides.keepAliveSeen = true;
            return true;
        }
        return false;
    }

    private LocationBuilder parseLocationHeader(Directive directive) {
        List<ConfTokenizer.Token> arguments = directive.args;
        if (arguments.isEmpty()) {
            throw error(directive.nameToken, "'location' needs a modifier and/or a pattern");
        }

        LocationModifier modifier = LocationModifier.PREFIX;
        ConfTokenizer.Token patternToken;
        if (arguments.size() == 1) {
            patternToken = arguments.get(0);
        } else if (arguments.size() == 2) {
            ConfTokenizer.Token modifierToken = arguments.get(0);
            String spelling = modifierToken.text;
            if ("=".equals(spelling)) {
                modifier = LocationModifier.EXACT;
            } else if ("^~".equals(spelling)) {
                modifier = LocationModifier.PREFIX_PRIORITY;
            } else if ("~".equals(spelling)) {
                modifier = LocationModifier.REGEX;
            } else if ("~*".equals(spelling)) {
                modifier = LocationModifier.REGEX_IGNORE_CASE;
            } else {
                throw error(modifierToken, "unknown location modifier '" + spelling
                        + "'; expected one of =, ^~, ~, ~*");
            }
            patternToken = arguments.get(1);
        } else {
            throw error(directive.nameToken,
                    "'location' takes at most a modifier and a pattern");
        }

        String pattern = patternToken.text;
        if (pattern.startsWith("@")) {
            throw error(patternToken, "named locations ('@name') are not supported in M2");
        }

        LocationBuilder location = new LocationBuilder(patternToken, modifier, pattern);
        if (modifier.isRegex()) {
            int flags = modifier == LocationModifier.REGEX_IGNORE_CASE ? Pattern.CASE_INSENSITIVE : 0;
            try {
                location.regex = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                throw error(patternToken, "invalid regular expression: " + e.getDescription());
            }
        } else {
            checkNoVariables(patternToken);
            if (!pattern.startsWith("/")) {
                throw error(patternToken, "location pattern must start with '/': " + pattern);
            }
        }
        return location;
    }

    private ListenEndpoint parseListen(Directive directive) {
        if (directive.args.isEmpty()) {
            throw error(directive.nameToken, "'listen' needs an address or a port");
        }
        boolean defaultServer = false;
        for (int i = 1; i < directive.args.size(); i++) {
            ConfTokenizer.Token flag = directive.args.get(i);
            String text = flag.text;
            if ("default_server".equals(text) || "default".equals(text)) {
                defaultServer = true;
            } else if ("ssl".equals(text) || "http2".equals(text)) {
                throw error(flag, "'" + text + "' on 'listen' arrives with TLS/HTTP2 in M5");
            } else {
                throw error(flag, "unsupported 'listen' parameter: " + text);
            }
        }
        return parseListenAddress(requireArgument(directive, 0), defaultServer);
    }

    private ListenEndpoint parseListenAddress(ConfTokenizer.Token token, boolean defaultServer) {
        checkNoVariables(token);
        String value = token.text;
        if (value.startsWith("unix:")) {
            throw error(token, "unix domain sockets are not supported in M2");
        }

        String host = null;
        String portText;
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing < 0) {
                throw error(token, "malformed IPv6 listen address: " + value);
            }
            String literal = value.substring(1, closing);
            if (value.length() == closing + 1) {
                portText = "80";
            } else if (value.charAt(closing + 1) == ':') {
                portText = value.substring(closing + 2);
            } else {
                throw error(token, "malformed listen address: " + value);
            }
            if ("::".equals(literal)) {
                host = null;
            } else {
                // Keep the brackets: InetSocketAddress accepts "[::1]" as a host name for binding.
                host = value.substring(0, closing + 1);
            }
        } else if (value.indexOf(':') < 0) {
            if (isAllDigits(value)) {
                portText = value;
            } else {
                host = value;
                portText = "80";
            }
        } else {
            host = value.substring(0, value.lastIndexOf(':'));
            portText = value.substring(value.lastIndexOf(':') + 1);
        }
        if (host != null && (host.isEmpty() || "*".equals(host) || "0.0.0.0".equals(host))) {
            host = null;
        }
        return new ListenEndpoint(host, parsePort(portText, token), defaultServer);
    }

    private List<String> parseServerNames(Directive directive) {
        List<String> names = new ArrayList<String>();
        for (ConfTokenizer.Token argument : directive.args) {
            String name = argument.text;
            if (name.startsWith("~")) {
                // A server_name regular expression may legitimately contain '$' anchors, so the
                // variable check is skipped for it.
                if (name.length() == 1) {
                    throw error(argument, "server_name regular expression is empty");
                }
                names.add(name);
                continue;
            }
            checkNoVariables(argument);
            if (name.isEmpty()) {
                // The empty name matches a request that carries no Host header at all.
                names.add(name);
                continue;
            }
            if (CATCH_ALL_SERVER_NAME.equals(name)) {
                names.add(name);
                continue;
            }
            if (name.startsWith(".")) {
                if (name.length() == 1) {
                    throw error(argument, "malformed server_name: " + name);
                }
                // nginx reads ".example.com" as the exact name plus its wildcard form.
                names.add(name.substring(1));
                names.add("*" + name);
                continue;
            }
            validateWildcardName(argument, name);
            names.add(name);
        }
        return names;
    }

    private void validateWildcardName(ConfTokenizer.Token token, String name) {
        if (name.indexOf('*') < 0) {
            return;
        }
        boolean singleAsterisk = name.indexOf('*') == name.lastIndexOf('*');
        boolean headWildcard = name.startsWith("*.");
        boolean tailWildcard = name.endsWith(".*");
        if (!singleAsterisk || headWildcard == tailWildcard) {
            throw error(token, "wildcard server_name is only supported as '*.example.com' or "
                    + "'mail.*': " + name);
        }
    }

    // ------------------------------------------------------------------ building

    private HttpBlock buildHttpBlock(Directive httpDirective, Overrides httpOverrides,
            List<ServerBuilder> serverBuilders) {
        long keepAliveTimeoutMillis = httpOverrides.keepAliveTimeoutMillis != null
                ? httpOverrides.keepAliveTimeoutMillis.longValue()
                : DEFAULT_KEEP_ALIVE_TIMEOUT_MILLIS;
        List<String> indexFiles = httpOverrides.indexFiles != null
                ? httpOverrides.indexFiles
                : Collections.singletonList(DEFAULT_INDEX_FILE);
        boolean autoIndex = httpOverrides.autoIndex != null && httpOverrides.autoIndex.booleanValue();
        Path httpRoot = httpOverrides.root;

        ListenEndpoint sharedEndpoint = null;
        int defaultServerCount = 0;
        List<ServerBlock> servers = new ArrayList<ServerBlock>(serverBuilders.size());

        for (ServerBuilder builder : serverBuilders) {
            if (builder.listeners.isEmpty()) {
                throw error(builder.token, "'server' block needs a 'listen' directive");
            }
            for (ListenEndpoint endpoint : builder.listeners) {
                if (sharedEndpoint == null) {
                    sharedEndpoint = endpoint;
                } else if (!sharedEndpoint.sameAddressAs(endpoint)) {
                    throw error(builder.token, "M2 binds a single address: every 'listen' must "
                            + "agree, found " + endpoint + " and " + sharedEndpoint);
                }
            }
            if (hasDefaultEndpoint(builder.listeners)) {
                defaultServerCount++;
                if (defaultServerCount > 1) {
                    throw error(builder.token,
                            "a second 'default_server' server is not supported in M2");
                }
            }

            Path serverRoot = builder.overrides.root != null ? builder.overrides.root : httpRoot;
            if (serverRoot == null) {
                throw error(builder.token, "no 'root' directive is in effect for this 'server'; "
                        + "set 'root' in the 'http' or 'server' block");
            }
            List<String> serverIndexFiles = builder.overrides.indexFiles != null
                    ? builder.overrides.indexFiles : indexFiles;
            boolean serverAutoIndex = builder.overrides.autoIndex != null
                    ? builder.overrides.autoIndex.booleanValue() : autoIndex;
            long serverKeepAlive = builder.overrides.keepAliveTimeoutMillis != null
                    ? builder.overrides.keepAliveTimeoutMillis.longValue() : keepAliveTimeoutMillis;

            List<LocationBlock> locations = new ArrayList<LocationBlock>(builder.locations.size());
            for (LocationBuilder location : builder.locations) {
                Path locationRoot = location.overrides.root != null
                        ? location.overrides.root : serverRoot;
                List<String> locationIndexFiles = location.overrides.indexFiles != null
                        ? location.overrides.indexFiles : serverIndexFiles;
                boolean locationAutoIndex = location.overrides.autoIndex != null
                        ? location.overrides.autoIndex.booleanValue() : serverAutoIndex;
                long locationKeepAlive = location.overrides.keepAliveTimeoutMillis != null
                        ? location.overrides.keepAliveTimeoutMillis.longValue() : serverKeepAlive;
                locations.add(new LocationBlock(location.modifier, location.pattern, location.regex,
                        locationRoot, locationIndexFiles, locationAutoIndex, locationKeepAlive));
            }
            servers.add(new ServerBlock(builder.listeners, builder.serverNames, serverRoot,
                    serverIndexFiles, serverAutoIndex, serverKeepAlive, locations));
        }

        if (sharedEndpoint == null) {
            throw error(httpDirective.nameToken, "'http' block has no listening server");
        }
        return new HttpBlock(sharedEndpoint, servers);
    }

    private static boolean hasDefaultEndpoint(List<ListenEndpoint> endpoints) {
        for (ListenEndpoint endpoint : endpoints) {
            if (endpoint.defaultServer()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ values

    private int parseWorkerProcesses(Directive directive) {
        requireArgumentCount(directive, 1);
        ConfTokenizer.Token value = requireArgument(directive, 0);
        if ("auto".equals(value.text)) {
            return Runtime.getRuntime().availableProcessors();
        }
        return positiveInt(value);
    }

    private Path parseRoot(ConfTokenizer.Token token) {
        checkNoVariables(token);
        final Path value;
        try {
            value = Paths.get(token.text);
        } catch (InvalidPathException e) {
            throw error(token, "invalid path: " + token.text);
        }
        return value.isAbsolute() ? value.normalize() : baseDir.resolve(value).normalize();
    }

    private List<String> parseIndexFiles(Directive directive) {
        if (directive.args.isEmpty()) {
            throw error(directive.nameToken, "'index' needs at least one file name");
        }
        List<String> names = new ArrayList<String>(directive.args.size());
        for (ConfTokenizer.Token argument : directive.args) {
            checkNoVariables(argument);
            if (argument.text.isEmpty()) {
                throw error(argument, "'index' contains an empty file name");
            }
            names.add(argument.text);
        }
        return Collections.unmodifiableList(names);
    }

    private boolean parseOnOff(ConfTokenizer.Token token) {
        if ("on".equalsIgnoreCase(token.text)) {
            return true;
        }
        if ("off".equalsIgnoreCase(token.text)) {
            return false;
        }
        throw error(token, "expected 'on' or 'off', got: " + token.text);
    }

    private long parseKeepAliveTimeout(Directive directive) {
        if (directive.args.isEmpty() || directive.args.size() > 2) {
            throw error(directive.nameToken,
                    "'keepalive_timeout' takes a timeout and an optional header timeout");
        }
        long timeoutMillis = parseTimeMillis(directive.args.get(0));
        if (directive.args.size() == 2) {
            // The second value is the keep-alive header timeout, which only matters for HTTP/1.0
            // clients. Validate it so that typos still fail fast, then ignore it.
            parseTimeMillis(directive.args.get(1));
        }
        return timeoutMillis;
    }

    /** Parses an nginx time value; a missing suffix means seconds. */
    private long parseTimeMillis(ConfTokenizer.Token token) {
        checkNoVariables(token);
        String value = token.text;
        String digits = value;
        long multiplier = 1000L;
        if (value.endsWith("ms")) {
            digits = value.substring(0, value.length() - 2);
            multiplier = 1L;
        } else if (value.endsWith("s")) {
            digits = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            digits = value.substring(0, value.length() - 1);
            multiplier = 60000L;
        } else if (value.endsWith("h")) {
            digits = value.substring(0, value.length() - 1);
            multiplier = 3600000L;
        } else if (value.endsWith("d")) {
            digits = value.substring(0, value.length() - 1);
            multiplier = 86400000L;
        }
        long number;
        try {
            number = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw error(token, "expected a time such as 65s, 1m or 500ms, got: " + value);
        }
        if (number < 0) {
            throw error(token, "time must not be negative: " + value);
        }
        return number * multiplier;
    }

    private int positiveInt(ConfTokenizer.Token token) {
        checkNoVariables(token);
        int parsed;
        try {
            parsed = Integer.parseInt(token.text);
        } catch (NumberFormatException e) {
            throw error(token, "expected a positive integer, got: " + token.text);
        }
        if (parsed <= 0) {
            throw error(token, "expected a positive integer, got: " + token.text);
        }
        return parsed;
    }

    private int parsePort(String text, ConfTokenizer.Token token) {
        int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw error(token, "malformed port in listen address: " + token.text);
        }
        if (port < 0 || port > 65535) {
            throw error(token, "port out of range (0-65535): " + port);
        }
        return port;
    }

    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ tokens

    private Directive nextDirective() {
        ConfTokenizer.Token nameToken = expect(ConfTokenizer.Kind.WORD, "a directive name");
        List<ConfTokenizer.Token> arguments = new ArrayList<ConfTokenizer.Token>();
        while (true) {
            ConfTokenizer.Token token = peek();
            switch (token.kind) {
                case LBRACE:
                    position++;
                    return new Directive(nameToken.text, arguments, nameToken, true);
                case SEMICOLON:
                    position++;
                    return new Directive(nameToken.text, arguments, nameToken, false);
                case RBRACE:
                    throw error(token, "'" + nameToken.text + "' must end with ';'");
                default:
                    position++;
                    arguments.add(token);
            }
        }
    }

    private boolean atEnd() {
        return peek().kind == ConfTokenizer.Kind.EOF;
    }

    /** @return whether the innermost block ends here; a missing {@code '}'} is reported as an error */
    private boolean atBlockEnd() {
        ConfTokenizer.Token token = peek();
        if (token.kind == ConfTokenizer.Kind.RBRACE) {
            return true;
        }
        if (token.kind == ConfTokenizer.Kind.EOF) {
            throw error(token, "unexpected end of file: a block is not closed with '}'");
        }
        return false;
    }

    private void expectBlockEnd(String context) {
        ConfTokenizer.Token token = peek();
        if (token.kind != ConfTokenizer.Kind.RBRACE) {
            throw error(token, "expected '}' closing '" + context + "', found " + token);
        }
        position++;
    }

    private ConfTokenizer.Token peek() {
        return tokens.get(position);
    }

    private ConfTokenizer.Token expect(ConfTokenizer.Kind kind, String description) {
        ConfTokenizer.Token token = peek();
        if (token.kind != kind) {
            throw error(token, "expected " + description + ", found " + token);
        }
        position++;
        return token;
    }

    // ------------------------------------------------------------------ errors

    private ConfException error(ConfTokenizer.Token token, String message) {
        return new ConfException(source, token.line, token.column, message);
    }

    private ConfException unknownDirective(Directive directive, String... supported) {
        StringBuilder message = new StringBuilder();
        message.append("unknown directive '").append(directive.name).append("'");
        String planned = plannedMilestone(directive.name);
        if (planned != null) {
            message.append(" (planned for ").append(planned).append(")");
        }
        message.append("; supported here: ").append(String.join(", ", supported));
        return error(directive.nameToken, message.toString());
    }

    /** @return where a known nginx directive is scheduled, or {@code null} when it is unknown */
    private static String plannedMilestone(String name) {
        if ("proxy_pass".equals(name) || "upstream".equals(name) || "proxy_set_header".equals(name)
                || "proxy_http_version".equals(name) || "proxy_connect_timeout".equals(name)
                || "proxy_read_timeout".equals(name) || "proxy_send_timeout".equals(name)
                || "proxy_next_upstream".equals(name)) {
            return "M3 reverse proxy";
        }
        if ("least_conn".equals(name) || "ip_hash".equals(name) || "max_fails".equals(name)
                || "fail_timeout".equals(name) || "keepalive".equals(name)) {
            return "M4 load balancing";
        }
        if ("ssl_certificate".equals(name) || "ssl_certificate_key".equals(name)
                || "gzip".equals(name) || "gzip_types".equals(name) || "access_log".equals(name)
                || "log_format".equals(name) || "limit_req".equals(name)
                || "limit_req_zone".equals(name) || "ssl_protocols".equals(name)) {
            return "M5";
        }
        if ("rewrite".equals(name) || "map".equals(name) || "proxy_cache".equals(name)
                || "alias".equals(name) || "try_files".equals(name) || "if".equals(name)) {
            return "P2";
        }
        if ("include".equals(name) || "types".equals(name) || "default_type".equals(name)
                || "sendfile".equals(name) || "tcp_nopush".equals(name) || "tcp_nodelay".equals(name)
                || "user".equals(name) || "pid".equals(name) || "error_log".equals(name)) {
            return "a later milestone (not part of the M2 subset)";
        }
        return null;
    }

    private void checkNoVariables(ConfTokenizer.Token token) {
        if (token.text.indexOf('$') >= 0) {
            throw error(token, "variables are not supported yet: " + token.text);
        }
    }

    private void requireSimple(Directive directive) {
        if (directive.block) {
            throw error(directive.nameToken, "'" + directive.name + "' does not take a block");
        }
    }

    private void requireBlock(Directive directive) {
        if (!directive.block) {
            throw error(directive.nameToken, "'" + directive.name + "' requires a '{ ... }' block");
        }
    }

    private void requireNoArguments(Directive directive) {
        if (!directive.args.isEmpty()) {
            throw error(directive.args.get(0), "'" + directive.name + "' does not take arguments");
        }
    }

    private void requireFirstOccurrence(boolean alreadySeen, Directive directive) {
        if (alreadySeen) {
            throw error(directive.nameToken, "duplicate '" + directive.name + "' directive");
        }
    }

    private ConfTokenizer.Token requireArgument(Directive directive, int index) {
        if (directive.args.size() <= index) {
            throw error(directive.nameToken, "'" + directive.name + "' needs a value");
        }
        return directive.args.get(index);
    }

    private void requireArgumentCount(Directive directive, int expected) {
        if (directive.args.size() != expected) {
            throw error(directive.nameToken, "'" + directive.name + "' takes exactly " + expected
                    + " argument(s), got " + directive.args.size());
        }
    }

    // ------------------------------------------------------------------ internal state

    private static final class Directive {

        private final String name;
        private final List<ConfTokenizer.Token> args;
        private final ConfTokenizer.Token nameToken;
        private final boolean block;

        Directive(String name, List<ConfTokenizer.Token> args, ConfTokenizer.Token nameToken,
                boolean block) {
            this.name = name;
            this.args = args;
            this.nameToken = nameToken;
            this.block = block;
        }
    }

    /** Values written directly in one context, before inheritance is folded in. */
    private static final class Overrides {

        private Path root;
        private List<String> indexFiles;
        private Boolean autoIndex;
        private Long keepAliveTimeoutMillis;
        private boolean rootSeen;
        private boolean indexSeen;
        private boolean autoIndexSeen;
        private boolean keepAliveSeen;
    }

    private static final class ServerBuilder {

        private final ConfTokenizer.Token token;
        private final List<ListenEndpoint> listeners = new ArrayList<ListenEndpoint>();
        private final List<String> serverNames = new ArrayList<String>();
        private final Overrides overrides = new Overrides();
        private final List<LocationBuilder> locations = new ArrayList<LocationBuilder>();

        ServerBuilder(ConfTokenizer.Token token) {
            this.token = token;
        }
    }

    private static final class LocationBuilder {

        private final ConfTokenizer.Token patternToken;
        private final LocationModifier modifier;
        private final String pattern;
        private final Overrides overrides = new Overrides();
        private Pattern regex;

        LocationBuilder(ConfTokenizer.Token patternToken, LocationModifier modifier, String pattern) {
            this.patternToken = patternToken;
            this.modifier = modifier;
            this.pattern = pattern;
        }
    }
}