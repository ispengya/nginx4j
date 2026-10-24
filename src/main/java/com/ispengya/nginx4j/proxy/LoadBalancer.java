package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.conf.UpstreamBlock;
import com.ispengya.nginx4j.conf.UpstreamServer;
import io.netty.util.concurrent.FastThreadLocal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smooth weighted round-robin over one {@code upstream} block, with nginx's algorithm:
 * add each weight to its running total, pick the largest total, then subtract the sum of all weights
 * from the winner. The resulting sequence interleaves nodes instead of emitting long runs.
 *
 * <p>The cursors live in a {@link FastThreadLocal}, so every EventLoop (one thread) keeps its own
 * independent view, exactly like nginx workers with {@code worker_processes auto}. No locking or
 * atomics are involved.
 */
public final class LoadBalancer {

    private final FastThreadLocal<Map<String, Cursor>> cursors =
            new FastThreadLocal<Map<String, Cursor>>() {
                @Override
                protected Map<String, Cursor> initialValue() {
                    return new HashMap<String, Cursor>();
                }
            };

    /**
     * @param upstream group with at least one server
     * @return the server selected for this request on the current EventLoop
     */
    public UpstreamServer select(UpstreamBlock upstream) {
        List<UpstreamServer> servers = upstream.servers();
        Map<String, Cursor> byName = cursors.get();
        Cursor cursor = byName.get(upstream.name());
        if (cursor == null || cursor.size != servers.size()) {
            cursor = new Cursor(servers.size());
            byName.put(upstream.name(), cursor);
        }

        int total = 0;
        int selected = 0;
        int selectedWeight = Integer.MIN_VALUE;
        for (int i = 0; i < servers.size(); i++) {
            int weight = servers.get(i).weight();
            cursor.current[i] += weight;
            total += weight;
            if (cursor.current[i] > selectedWeight) {
                selectedWeight = cursor.current[i];
                selected = i;
            }
        }
        cursor.current[selected] -= total;
        return servers.get(selected);
    }

    /** Running state for one upstream name; only ever touched by its owning EventLoop thread. */
    private static final class Cursor {

        private final int size;
        private final int[] current;

        Cursor(int size) {
            this.size = size;
            this.current = new int[size];
        }
    }
}
