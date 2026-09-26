// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The heartbeat's {@code ping_seq} against the real Postgres: it starts at 0, rises by one, and never repeats. */
@SpringBootTest
class InstanceIdRepositoryIntegrationTest {

    @Autowired
    InstanceIdRepository instanceIds;

    @Autowired
    JdbcClient jdbc;

    /** The schema is cleaned per test class, not per test, and every test here counts from a fresh install. */
    @BeforeEach
    void freshInstall() {
        jdbc.sql("DELETE FROM telemetry_instance").update();
    }

    @Test
    @DisplayName("replicas pinging at once are never handed the same ping_seq")
    void pingSeq_concurrentCallsGetDistinctValues() throws Exception {
        instanceIds.get();
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Long>> calls = IntStream.range(0, callers)
                    .<Callable<Long>>mapToObj(i -> instanceIds::nextPingSeq)
                    .toList();
            List<Long> seqs = pool.invokeAll(calls).stream()
                    .map(InstanceIdRepositoryIntegrationTest::join)
                    .sorted()
                    .toList();

            assertEquals(IntStream.range(0, callers).mapToObj(i -> (long) i).toList(), seqs);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The bug: every boot mints a new instance id, so one install is counted as a new install on every
     * restart. The second call must return the id the first one stored.
     */
    @Test
    @DisplayName("the instance id is minted once and returned on every later call")
    void get_returnsTheStoredIdOnLaterCalls() {
        String first = instanceIds.get();

        assertEquals(first, instanceIds.get());
        assertEquals(
                List.of(first),
                jdbc.sql("SELECT instance_id FROM telemetry_instance")
                        .query(String.class)
                        .list());
    }

    private static long join(Future<Long> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
