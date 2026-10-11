package com.ispengya.nginx4j;

import com.ispengya.nginx4j.server.HttpServer;
import com.ispengya.nginx4j.server.ServerOptions;

/**
 * nginx4j entry point: parse bootstrap options, start the server, stop it on JVM shutdown.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        configureLogging();

        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println(ServerOptions.usage());
            return;
        }

        final ServerOptions options;
        try {
            options = ServerOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("invalid argument: " + e.getMessage());
            System.err.println(ServerOptions.usage());
            System.exit(2);
            return;
        }

        final HttpServer server = new HttpServer(options);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "nginx4j-shutdown"));
        server.startAndBlock();
    }

    private static void configureLogging() {
        // One log record per line, kept dependency-free (java.util.logging only).
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tY-%1$tm-%1$td %1$tT.%1$tL %4$-7s [%3$s] %5$s%6$s%n");
    }
}