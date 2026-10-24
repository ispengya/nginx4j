package com.ispengya.nginx4j.proxy;

import com.ispengya.nginx4j.conf.UpstreamBlock;
import com.ispengya.nginx4j.conf.UpstreamServer;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for smooth weighted round-robin (PLAN.md section 9.6, M3-D4).
 */
public class LoadBalancerTest {

    @Test
    public void weightOneTwoThreeProducesTheSmoothSequence() throws Exception {
        UpstreamBlock block = block("pool", 1, 2, 3);
        LoadBalancer balancer = new LoadBalancer();

        List<String> selected = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            selected.add(balancer.select(block).host());
        }

        assertEquals(Arrays.asList("c", "b", "a", "c", "b", "c"), selected);
    }

    @Test
    public void singleNodeAlwaysWins() throws Exception {
        UpstreamBlock block = block("solo", 5);
        LoadBalancer balancer = new LoadBalancer();

        for (int i = 0; i < 4; i++) {
            assertSame(block.servers().get(0), balancer.select(block));
        }
    }

    @Test
    public void everyEventLoopThreadKeepsItsOwnCursor() throws Exception {
        final UpstreamBlock block = block("pool", 1, 2, 3);
        final LoadBalancer balancer = new LoadBalancer();
        final CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<List<String>> task = new Callable<List<String>>() {
            @Override
            public List<String> call() throws Exception {
                start.await(5, TimeUnit.SECONDS);
                List<String> selected = new ArrayList<String>();
                for (int i = 0; i < 3; i++) {
                    selected.add(balancer.select(block).host());
                }
                return selected;
            }
        };
        try {
            Future<List<String>> first = pool.submit(task);
            Future<List<String>> second = pool.submit(task);
            assertEquals(Arrays.asList("c", "b", "a"), first.get(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("c", "b", "a"), second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static UpstreamBlock block(String name, int... weights) throws Exception {
        List<InetAddress> address =
                Collections.singletonList(InetAddress.getByName("127.0.0.1"));
        List<UpstreamServer> servers = new ArrayList<UpstreamServer>();
        for (int i = 0; i < weights.length; i++) {
            servers.add(new UpstreamServer(String.valueOf((char) ('a' + i)), 9000 + i, weights[i],
                    address));
        }
        return new UpstreamBlock(name, servers);
    }
}
