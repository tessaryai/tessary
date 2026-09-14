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
    @DisplayName("an instance's first ping is 0, then each ping is one more, and the counter is persisted")
    void pingSeq_startsAtZeroAndRisesByOne() {
        instanceIds.get();

        assertEquals(0L, instanceIds.nextPingSeq());
        assertEquals(1L, instanceIds.nextPingSeq());
        assertEquals(2L, instanceIds.nextPingSeq());
        assertEquals(
                3L,
                jdbc.sql("SELECT ping_seq FROM telemetry_instance")
                        .query(Long.class)
                        .single(),
                "the stored value is the NEXT ping's sequence, so a restart continues from it");
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

    @Test
    @DisplayName("minting the instance id leaves ping_seq at 0")
    void get_mintsWithPingSeqZero() {
        instanceIds.get();

        assertEquals(
                0L,
                jdbc.sql("SELECT ping_seq FROM telemetry_instance")
                        .query(Long.class)
                        .single());
    }

    private static long join(Future<Long> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
