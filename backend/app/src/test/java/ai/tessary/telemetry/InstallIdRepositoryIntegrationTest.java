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
class InstallIdRepositoryIntegrationTest {

    @Autowired
    InstallIdRepository installIds;

    @Autowired
    JdbcClient jdbc;

    /** The schema is cleaned per test class, not per test, and every test here counts from a fresh install. */
    @BeforeEach
    void freshInstall() {
        jdbc.sql("DELETE FROM telemetry_install").update();
    }

    @Test
    @DisplayName("an install's first ping is 0, then each ping is one more, and the counter is persisted")
    void pingSeq_startsAtZeroAndRisesByOne() {
        installIds.get();

        assertEquals(0L, installIds.nextPingSeq());
        assertEquals(1L, installIds.nextPingSeq());
        assertEquals(2L, installIds.nextPingSeq());
        assertEquals(
                3L,
                jdbc.sql("SELECT ping_seq FROM telemetry_install")
                        .query(Long.class)
                        .single(),
                "the stored value is the NEXT ping's sequence, so a restart continues from it");
    }

    @Test
    @DisplayName("replicas pinging at once are never handed the same ping_seq")
    void pingSeq_concurrentCallsGetDistinctValues() throws Exception {
        installIds.get();
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Long>> calls = IntStream.range(0, callers)
                    .<Callable<Long>>mapToObj(i -> installIds::nextPingSeq)
                    .toList();
            List<Long> seqs = pool.invokeAll(calls).stream()
                    .map(InstallIdRepositoryIntegrationTest::join)
                    .sorted()
                    .toList();

            assertEquals(IntStream.range(0, callers).mapToObj(i -> (long) i).toList(), seqs);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("minting the install id leaves ping_seq at 0")
    void get_mintsWithPingSeqZero() {
        installIds.get();

        assertEquals(
                0L,
                jdbc.sql("SELECT ping_seq FROM telemetry_install")
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
