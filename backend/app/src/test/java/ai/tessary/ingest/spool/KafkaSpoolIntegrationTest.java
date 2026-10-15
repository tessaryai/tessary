// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.IngestSpoolProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The opt-in Kafka-API spool end to end against a real broker: an accepted
 * batch is on the broker before the edge answers and reaches the substrate through the same drain;
 * a consumer that dies before its ack is handed the same record again and a redelivered batch is a
 * no-op on the idempotent write; a broker that cannot be reached sheds rather than acknowledges; an
 * oversize batch is refused for the producer to split.
 */
@Testcontainers
// CI only: against a local Docker the drain never reaches the substrate inside the test's wait, so it fails on every
// branch there. GitHub Actions sets CI=true.
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
@SpringBootTest(properties = {"tessary.ingest.substrate.rollup-enabled=false"})
// Own context on purpose: it swaps the ingest spool onto a Redpanda container, so its bean graph is not the shared
// one.
@TestPropertySource(properties = "test.context-group=kafka-spool")
// The Redpanda container stops after this class; a cached context would keep its consumers rebootstrapping.
@DirtiesContext
class KafkaSpoolIntegrationTest {
    @Container
    static final RedpandaContainer REDPANDA =
            new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v25.2.1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        var noRebalanceDelay =
                REDPANDA.execInContainer("rpk", "cluster", "config", "set", "group_initial_rebalance_delay", "0");
        assertEquals(0, noRebalanceDelay.getExitCode(), noRebalanceDelay.getStderr());
        r.add("tessary.ingest.spool.mode", () -> "kafka");
        r.add("tessary.ingest.spool.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        r.add("tessary.ingest.spool.kafka.partitions", () -> "4");
        r.add("tessary.ingest.spool.kafka.consumers", () -> "2");
    }

    @Autowired
    SubstrateWriter writer;

    @Autowired
    IngestSpool spool;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

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
    void aRedeliveredBatchIsANoOpOnTheIdempotentWrite() throws Exception {
        String pid = TenantFixture.bootstrap(tenants, "kafka-replay").project().id();
        assertTrue(writer.enqueue(pid, batch("kr-1", 5)));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(60)), "the drainer wrote the batch");
        assertEquals(5, spans(pid));
        // What a consumer restarted before its ack would see: the same message again.
        assertTrue(writer.enqueue(pid, batch("kr-1", 5)));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(60)));
        assertEquals(5, spans(pid), "a replayed batch changes nothing");
    }

    @Test
    void aConsumerThatDiesBeforeItsAckIsRedeliveredTheSameRecord() {
        // A spool of its own, on its own topic and group, so this thread is the consumer's owner and the
        // live drainer cannot race the claims.
        IngestSpoolProperties.Kafka own = new IngestSpoolProperties.Kafka();
        own.setBootstrapServers(REDPANDA.getBootstrapServers());
        own.setTopic("tessary.ingest.replay");
        own.setDeadLetterTopic("tessary.ingest.replay.dead-letter");
        own.setGroupId("tessary-ingest-replay");
        own.setPartitions(1);
        try (KafkaSpool spool = new KafkaSpool(own, mapper)) {
            assertEquals(IngestSpool.Admission.ACCEPTED, spool.append("p", batch("kc-1", 3)));
            IngestSpool.Claimed first = claimOne(spool);
            assertEquals(3, first.entries().size());
            // Crash before the ack: the consumer goes away with the offset uncommitted.
            spool.resetConsumerForTest();
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

    @Test
    void anUnreachableBrokerShedsInsteadOfAcknowledging() {
        IngestSpoolProperties.Kafka bogus = new IngestSpoolProperties.Kafka();
        bogus.setBootstrapServers("127.0.0.1:1");
        bogus.setPublishTimeoutMs(1_500);
        try (KafkaSpool down = new KafkaSpool(bogus, mapper)) {
            assertEquals(IngestSpool.Admission.SHED, down.append("p", batch("kd-1", 1)), "no ack, no 200");
        }
    }

    @Test
    void anOversizeBatchIsRefusedForTheProducerToSplit() {
        IngestSpoolProperties.Kafka tiny = new IngestSpoolProperties.Kafka();
        tiny.setBootstrapServers(REDPANDA.getBootstrapServers());
        tiny.setMaxMessageBytes(256);
        tiny.setTopic("tessary.ingest.tiny");
        tiny.setDeadLetterTopic("tessary.ingest.tiny.dead-letter");
        try (KafkaSpool small = new KafkaSpool(tiny, mapper)) {
            assertEquals(IngestSpool.Admission.OVERSIZE, small.append("p", batch("ko-1", 10)));
        }
    }
}
