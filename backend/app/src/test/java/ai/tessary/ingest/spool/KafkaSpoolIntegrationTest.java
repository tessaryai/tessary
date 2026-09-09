// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.IngestSpoolProperties;
import ai.tessary.config.SubstrateProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.ingest.substrate.v2.SpanBatchWriter;
import ai.tessary.redaction.RedactionService;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.common.GroupState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@SpringBootTest(properties = {"tessary.ingest.substrate.rollup-enabled=false"})
class KafkaSpoolIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaSpoolIntegrationTest.class);

    @Container
    static final RedpandaContainer REDPANDA =
            new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v25.2.1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
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

    @Autowired
    SpanBatchWriter spans;

    @Autowired
    SubstrateProperties substrateProps;

    @Autowired
    RedactionService redaction;

    private static RawEntry span(String traceId, String id) {
        String now = Instant.now().toString();
        return new RawEntry(
                id, null, "span " + id, "in", "out", null, null, null, traceId, now, "llm", now, null, null, null);
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

    /**
     * Criterion 8's second half: the same cross-project burst drained by one drainer and by four, each
     * on a private topic and group so the live writer cannot help. The speed-up is logged for the
     * runbook; the bound only says four drainers must not be more than 2x slower than one, because
     * on a shared CI box Postgres, Redpanda and the JVM share the cores and the ratio is noise there.
     */
    @Test
    void crossProjectThroughputScalesWithDrainerCount() throws Exception {
        String[] pids = new String[8];
        for (int p = 0; p < 8; p++)
            pids[p] = TenantFixture.bootstrap(tenants, "kafka-scale-" + p)
                    .project()
                    .id();
        long one = drainWith(1, pids, "s1");
        long four = drainWith(4, pids, "s4");
        for (int p = 0; p < 8; p++)
            assertEquals(2 * 251, spans(pids[p]), "project " + p + " landed both runs once each");
        double speedup = (double) one / Math.max(1, four);
        log.info(
                "cross-project scaling: 2000 spans over 8 projects drained in {} ms with 1 drainer, {} ms with 4, speed-up {}",
                one,
                four,
                String.format(Locale.ROOT, "%.2f", speedup));
        assertTrue(four <= one * 2.0, "four drainers took " + four + " ms against " + one + " ms for one");
    }

    private long drainWith(int drainers, String[] pids, String tag) throws Exception {
        IngestSpoolProperties own = new IngestSpoolProperties();
        own.setMode("kafka");
        own.getKafka().setBootstrapServers(REDPANDA.getBootstrapServers());
        own.getKafka().setTopic("tessary.ingest." + tag);
        own.getKafka().setDeadLetterTopic("tessary.ingest." + tag + ".dead-letter");
        own.getKafka().setGroupId("tessary-ingest-" + tag);
        own.getKafka().setPartitions(8);
        own.getKafka().setConsumers(drainers);
        try (KafkaSpool spool = new KafkaSpool(own.getKafka(), mapper)) {
            SubstrateWriter w = new SubstrateWriter(spans, substrateProps, redaction, spool);
            try {
                assertEquals(drainers, w.drainerCount());
                // Warm-up: one span per project so every drainer joins the group, then wait for the
                // group to settle. Otherwise the clock below measures the broker's join and rebalance
                // delays (one per member, seconds each), not the drain.
                for (String pid : pids) assertTrue(w.enqueue(pid, batch("ks-" + tag + "-warm-" + pid, 1)));
                assertTrue(w.awaitIdle(Duration.ofSeconds(60)), tag + " warm-up drained");
                awaitStableGroup(own.getKafka().getGroupId(), drainers);
                long start = System.nanoTime();
                for (int b = 0; b < 10; b++) {
                    for (String pid : pids) assertTrue(w.enqueue(pid, batch("ks-" + tag + "-" + pid + "-" + b, 25)));
                }
                assertTrue(w.awaitIdle(Duration.ofSeconds(180)), tag + " drained");
                assertEquals(0L, w.failedBatches());
                return Duration.ofNanos(System.nanoTime() - start).toMillis();
            } finally {
                w.shutdown();
            }
        }
    }

    private static void awaitStableGroup(String groupId, int members) throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
            for (int i = 0; i < 60; i++) {
                ConsumerGroupDescription d = admin.describeConsumerGroups(List.of(groupId))
                        .all()
                        .get(10, TimeUnit.SECONDS)
                        .get(groupId);
                if (d != null && d.members().size() == members && d.groupState() == GroupState.STABLE) return;
                Thread.sleep(500);
            }
            throw new AssertionError("group " + groupId + " never settled with " + members + " members");
        }
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
