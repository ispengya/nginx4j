package com.ispengya.nginx4j.conf;

/**
 * Root of the configuration tree: the nginx "main" context.
 *
 * <p>Only the directives needed by the frozen threading model plus the {@code http} block live here.
 * {@code worker_processes} maps to the worker EventLoop count, which is why it is kept as a plain
 * integer rather than the string nginx accepts.
 */
public final class MainBlock {

    private final int workerProcesses;
    private final EventsBlock events;
    private final HttpBlock http;

    public MainBlock(int workerProcesses, EventsBlock events, HttpBlock http) {
        this.workerProcesses = workerProcesses;
        this.events = events;
        this.http = http;
    }

    /** @return the parsed or defaulted {@code worker_processes} value */
    public int workerProcesses() {
        return workerProcesses;
    }

    public EventsBlock events() {
        return events;
    }

    public HttpBlock http() {
        return http;
    }
}