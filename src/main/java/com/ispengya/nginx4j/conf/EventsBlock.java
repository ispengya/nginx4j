package com.ispengya.nginx4j.conf;

/**
 * The {@code events} block.
 *
 * <p>Only {@code worker_connections} is part of the subset. The value is parsed and range checked so
 * that a bad configuration fails at startup rather than during M6 tuning, but it has no effect yet:
 * JDK 8 NIO selects connections per EventLoop and the accept path is bounded by the backlog.
 */
public final class EventsBlock {

    private final int workerConnections;

    public EventsBlock(int workerConnections) {
        this.workerConnections = workerConnections;
    }

    public int workerConnections() {
        return workerConnections;
    }
}