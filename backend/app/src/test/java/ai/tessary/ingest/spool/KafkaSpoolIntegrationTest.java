// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.IngestSpoolProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.Props;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * The opt-in Kafka-API spool end to end against a real broker: an accepted batch is on the broker before the edge
 * answers and reaches the substrate through the same drain, once even when it is sent twice; a consumer that dies
 * before its ack is handed the same record again. How the spool answers a broker that refuses, fails or cannot be
 * reached is {@code KafkaSpoolTest}'s.
 */
@Testcontainers
@SpringBootTest(properties = {"tessary.ingest.substrate.rollup-enabled=false"})
// Own context on purpose: it swaps the ingest spool onto a Redpanda container, so its bean graph is not the shared
// one.
@TestPropertySource(properties = "test.context-group=kafka-spool")
// The Redpanda container stops after this class; a cached context would keep its consumers rebootstrapping.
@DirtiesContext
record KafkaSpoolIntegrationTest(
        SubstrateWriter writer, IngestSpool spool, TenantService tenants, JdbcClient jdbc, ObjectMapper mapper) {
    /** Docker Hub, where the Postgres image comes from too: docker.redpanda.com does not resolve from every daemon. */
    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v25.2.1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        var noRebalanceDelay =
                REDPANDA.execInContainer("rpk", "cluster", "config", "set", "group_initial_rebalance_delay", "0");
        assertEquals(0, noRebalanceDelay.getExitCode(), noRebalanceDelay.getStderr());
        // Like the rebalance delay, this container does not get dev-container mode's 10 MiB disk floor, so the broker
        // keeps the 5 GiB production one and refuses every produce once Docker's disk has less free than that.
        var smallDiskFloor = REDPANDA.execInContainer(
                "rpk", "cluster", "config", "set", "storage_min_free_bytes", String.valueOf(10L * 1024 * 1024));
        assertEquals(0, smallDiskFloor.getExitCode(), smallDiskFloor.getStderr());
        r.add("tessary.ingest.spool.mode", () -> "kafka");
        r.add("tessary.ingest.spool.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        r.add("tessary.ingest.spool.kafka.partitions", () -> "4");
        r.add("tessary.ingest.spool.kafka.consumers", () -> "2");
    }

    private static RawEntry span(String traceId, String id) {
        String now = Instant.now().toString();
        return new RawEntry(id, "span " + id, "in", "out", null, null, null, traceId, now, "llm", now, null, null);
    }

    private static List<RawEntry> batch(String traceId, int spans) {
        List<RawEntry> out = new ArrayList<>();
        for (int i = 0; i < spans; i++) out.add(span(traceId, traceId + "-" + i));
        return out;
    }

    private int spans(String projectId) {
        return jdbc.sql("SELECT count(*) FROM span WHERE project_id = :pid")
                .param("pid", projectId)
                .query(Integer.class)
                .single();
    }

    @Test
    void acceptedIsPersistedAndDrainedThroughTheSameWrite() throws Exception {
        String pid = TenantFixture.bootstrap(tenants, "kafka-spool").project().id();
        IngestSpool.Stats before = spool.stats();
        assertEquals("kafka", before.mode());
        assertTrue(before.durable());

        for (int b = 0; b < 20; b++) {
            assertTrue(writer.enqueue(pid, batch("kt-" + b, 10)), "a persisted publish is an accepted batch");
        }
        assertTrue(writer.awaitIdle(Duration.ofSeconds(60)), "the broker-backed drain reached the substrate");
        assertEquals(200, spans(pid), "every span of every accepted batch landed exactly once");
        assertEquals(0L, writer.shedBatches());
        assertEquals(0L, writer.failedBatches());
    }

    @Test
    void twoConsumersDrainFourProjectsWithoutDuplicatesOrDeadlocks() throws Exception {
        // Criterion 8: same-project batches serialise on one partition, cross-project batches spread
        // across both drainers; every span lands exactly once and both drainers are still alive.
        assertEquals(2, writer.drainerCount());
        String[] pids = new String[4];
        for (int p = 0; p < 4; p++)
            pids[p] =
                    TenantFixture.bootstrap(tenants, "kafka-par-" + p).project().id();
        for (int b = 0; b < 10; b++) {
            for (int p = 0; p < 4; p++) {
                assertTrue(writer.enqueue(pids[p], batch("kp-" + p + "-" + b, 10)));
                // The same trace again from the same project: the second write must not duplicate.
                assertTrue(writer.enqueue(pids[p], batch("kp-" + p + "-" + b, 10)));
            }
        }
        assertTrue(writer.awaitIdle(Duration.ofSeconds(120)), "eighty batches drained across two consumers");
        for (int p = 0; p < 4; p++)
            assertEquals(100, spans(pids[p]), "project " + p + ": ten traces of ten spans, once each");
        assertEquals(0L, writer.failedBatches());
        assertTrue(writer.drainerAlive(), "both drainers alive after the run");
    }

    @Test
    void aConsumerThatDiesBeforeItsAckIsRedeliveredTheSameRecord() {
        // A spool of its own, on its own topic and group, so this thread is the consumer's owner and the
        // live drainer cannot race the claims.
        IngestSpoolProperties.Kafka own = kafka(
                "bootstrap-servers",
                REDPANDA.getBootstrapServers(),
                "topic",
                "tessary.ingest.replay",
                "dead-letter-topic",
                "tessary.ingest.replay.dead-letter",
                "group-id",
                "tessary-ingest-replay",
                "partitions",
                1);
        try (KafkaSpool spool = new KafkaSpool(own, mapper)) {
            assertEquals(IngestSpool.Admission.ACCEPTED, spool.append("p", batch("kc-1", 3)));
            IngestSpool.Claimed first = claimOne(spool);
            assertEquals(3, first.entries().size());
            // Crash before the ack: the consumer goes away with the offset uncommitted.
            spool.closeOwnConsumer();
            IngestSpool.Claimed again = claimOne(spool);
            assertEquals(first.entries(), again.entries(), "the unacked batch is redelivered, not lost");
            spool.ack(again);
            assertTrue(claimNone(spool), "acked, so it is not delivered a third time");
        }
    }

    private static IngestSpool.Claimed claimOne(KafkaSpool spool) {
        try {
            for (int i = 0; i < 30; i++) {
                Optional<IngestSpool.Claimed> c = spool.claim(Duration.ofSeconds(2));
                if (c.isPresent()) return c.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("no record was delivered within the wait");
    }

    private static boolean claimNone(KafkaSpool spool) {
        try {
            return spool.claim(Duration.ofSeconds(3)).isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code keyValues} are relative to {@code tessary.ingest.spool.kafka}. */
    private static IngestSpoolProperties.Kafka kafka(Object... keyValues) {
        Object[] prefixed = keyValues.clone();
        for (int i = 0; i < prefixed.length; i += 2) prefixed[i] = "kafka." + prefixed[i];
        return Props.of(IngestSpoolProperties.class, prefixed).kafka();
    }
}
