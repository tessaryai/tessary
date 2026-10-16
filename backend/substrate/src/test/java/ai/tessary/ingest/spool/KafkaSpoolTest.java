// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.config.IngestSpoolProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.testsupport.Props;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigsResult;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The Kafka spool's answer to each way a broker, a record or a thread misbehaves, over Kafka's mock clients. What a
 * real broker does with an accepted batch is {@code KafkaSpoolIntegrationTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class KafkaSpoolTest {

    private static final TopicPartition TP = new TopicPartition("tessary.ingest", 0);
    private static final String DEAD_LETTER = "tessary.ingest.dead-letter";
    private static final List<RawEntry> ENTRIES =
            List.of(new RawEntry(null, "span", "in", "out", null, null, null, "t", null, "llm", null, null, null));

    /** Deep stubs answer every admin call with a future that completes at once: a broker that says yes. */
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    Admin admin;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    private ObjectMapper mapper = new ObjectMapper();
    private MockProducer<String, byte[]> producer = producer(true);
    private MockConsumer<String, byte[]> consumer = new MockConsumer<>("earliest");

    private KafkaSpool spool(Object... keyValues) {
        Object[] prefixed = keyValues.clone();
        for (int i = 0; i < prefixed.length; i += 2) prefixed[i] = "kafka." + prefixed[i];
        IngestSpoolProperties.Kafka props =
                Props.of(IngestSpoolProperties.class, prefixed).kafka();
        return new KafkaSpool(props, mapper, producer, admin, () -> consumer, nanos::get);
    }

    private static MockProducer<String, byte[]> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, null, new StringSerializer(), new ByteArraySerializer());
    }

    /** The next poll hands over one record at {@code offset}; the first also assigns the partition. */
    private void deliver(long offset, byte[] value) {
        consumer.schedulePollTask(() -> {
            if (offset == 0) {
                consumer.rebalance(List.of(TP));
                consumer.updateBeginningOffsets(Map.of(TP, 0L));
            }
            consumer.addRecord(new ConsumerRecord<>(TP.topic(), TP.partition(), offset, "p", value));
        });
    }

    private byte[] batch() throws JsonProcessingException {
        return mapper.writeValueAsBytes(new KafkaSpool.Envelope("p", ENTRIES));
    }

    private static <T> KafkaFuture<T> failed(Throwable cause) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(cause);
        return future;
    }

    private List<String> published() {
        return producer.history().stream().map(ProducerRecord::topic).toList();
    }

    /**
     * Topics left by an earlier boot, and a broker that is not Redpanda and refuses its write-caching switch, still
     * make a ready spool. The bug is a spool that sheds every push after a restart.
     */
    @Test
    void existingTopicsAndARefusedWriteCachingSwitchStillMakeAReadySpool() {
        AlterConfigsResult refused = mock(AlterConfigsResult.class);
        when(refused.all()).thenReturn(failed(new InvalidConfigurationException("write.caching")));
        when(admin.createTopics(anyCollection()).all()).thenReturn(failed(new TopicExistsException("exists")));
        when(admin.incrementalAlterConfigs(anyMap())).thenReturn(refused).thenThrow(new TimeoutException("slow"));

        KafkaSpool spool = spool();

        assertEquals(IngestSpool.Admission.ACCEPTED, spool.append("p", ENTRIES));
        assertEquals(List.of("tessary.ingest"), published());
        assertEquals(Optional.empty(), spool.unavailable());
    }

    /** A broker that cannot be reached, or refuses the topic, sheds the push and says why on the health check. */
    @Test
    void aBrokerThatCannotBeReachedShedsAndSaysWhy() {
        when(admin.createTopics(anyCollection())).thenThrow(new TimeoutException("no broker"));

        KafkaSpool spool = spool();

        assertEquals(IngestSpool.Admission.SHED, spool.append("p", ENTRIES), "no ack, no 200");
        assertEquals(Optional.of("broker not reachable at redpanda:9092"), spool.unavailable());
        assertEquals(List.of(), published());
    }

    @Test
    void aBrokerThatRefusesTheTopicShedsAndSaysWhy() {
        when(admin.createTopics(anyCollection()).all()).thenReturn(failed(new PolicyViolationException("no")));

        KafkaSpool spool = spool();

        assertEquals(IngestSpool.Admission.SHED, spool.append("p", ENTRIES));
        assertEquals(Optional.of("broker refused topic tessary.ingest: PolicyViolationException"), spool.unavailable());
    }

    /**
     * A batch over the broker's message limit is refused for the producer to split, and one that cannot be encoded is
     * shed; neither reaches the broker. The broker bounds admission, so the pre-decode gate never asks the edge to
     * refuse.
     */
    @Test
    void anOversizeOrUnencodableBatchNeverReachesTheBroker() {
        KafkaSpool small = spool("max-message-bytes", 64);
        assertEquals(IngestSpool.Admission.OVERSIZE, small.append("p", ENTRIES));
        assertEquals(0, small.pressure());

        mapper = new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                throw new JsonMappingException(null, "unencodable");
            }
        };
        assertEquals(IngestSpool.Admission.SHED, spool().append("p", ENTRIES));
        assertEquals(List.of(), published());
    }

    /** Not persisted, so not accepted: the edge answers 503 and the health check names the failure. */
    @Test
    void aPublishTheBrokerDoesNotAcknowledgeIsShed() {
        KafkaSpool spool = spool();
        producer.sendException = new TimeoutException("expired");

        assertEquals(IngestSpool.Admission.SHED, spool.append("p", ENTRIES));
        assertEquals(Optional.of("publish failed: TimeoutException"), spool.unavailable());
    }

    /**
     * A poison record is parked on the dead-letter topic and committed past, as is a batch the writer gave up on. The
     * bug is a partition blocked forever behind one record.
     */
    @Test
    void anUndecodableRecordAndANackedBatchAreDeadLetteredAndCommittedPast() throws Exception {
        KafkaSpool spool = spool();
        deliver(0, "not json".getBytes(UTF_8));
        assertEquals(Optional.empty(), spool.claim(Duration.ZERO));

        deliver(1, batch());
        spool.nack(spool.claim(Duration.ZERO).orElseThrow());

        assertEquals(List.of(DEAD_LETTER, DEAD_LETTER), published());
        assertEquals(Map.of(TP, new OffsetAndMetadata(2)), consumer.committed(Set.of(TP)));
    }

    /**
     * A batch the dead-letter topic will not take is sought back and redelivered, never committed past, whether the
     * publish fails, is interrupted, or the partition has moved to another consumer.
     */
    @Test
    void aBatchThatCannotBeDeadLetteredIsNeverCommittedPast() throws Exception {
        producer = producer(false);
        KafkaSpool spool = spool();
        deliver(0, batch());
        IngestSpool.Claimed claimed = spool.claim(Duration.ZERO).orElseThrow();

        producer.sendException = new TimeoutException("expired");
        spool.nack(claimed);
        assertEquals(0, consumer.position(TP), "sought back to the batch");

        producer.sendException = null;
        Thread.currentThread().interrupt();
        spool.nack(claimed);
        assertTrue(Thread.interrupted(), "the interrupt is kept for the drainer");

        consumer.rebalance(List.of());
        producer.sendException = new TimeoutException("expired");
        assertDoesNotThrow(() -> spool.nack(claimed));
        assertEquals(Map.of(), consumer.committed(Set.of(TP)));
    }

    /** A commit refused after a rebalance leaves the batch for the partition's new owner; the ack does not throw. */
    @Test
    void aFailedCommitDoesNotEscapeTheAck() throws Exception {
        consumer = new MockConsumer<>("earliest") {
            @Override
            public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
                throw new CommitFailedException();
            }
        };
        KafkaSpool spool = spool();
        deliver(0, batch());
        IngestSpool.Claimed claimed = spool.claim(Duration.ZERO).orElseThrow();

        assertDoesNotThrow(() -> spool.ack(claimed));
    }

    /**
     * A poll that is woken or fails is an empty claim; the bug is an exception that kills the drainer. An interrupted
     * one is the drainer's signal to stop.
     */
    @Test
    void aWokenOrFailedPollIsAnEmptyClaim() throws Exception {
        KafkaSpool spool = spool();
        consumer.wakeup();
        assertEquals(Optional.empty(), spool.claim(Duration.ZERO));

        consumer.setPollException(new KafkaException("fetch failed"));
        assertEquals(Optional.empty(), spool.claim(Duration.ZERO));

        consumer.setPollException(new InterruptException("stopping"));
        assertThrows(InterruptedException.class, () -> spool.claim(Duration.ZERO), "the drainer stops on it");
        assertTrue(Thread.interrupted());
    }

    /** After close a claim finds nothing and a settlement is refused, rather than touching a closed consumer. */
    @Test
    void afterCloseAClaimIsEmptyAndASettlementIsRefused() throws Exception {
        KafkaSpool spool = spool();
        deliver(0, batch());
        IngestSpool.Claimed claimed = spool.claim(Duration.ZERO).orElseThrow();

        spool.close();
        MockConsumer<String, byte[]> closed = consumer;
        consumer = new MockConsumer<>("earliest");

        assertTrue(closed.closed());
        assertEquals(Optional.empty(), spool.claim(Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> spool.ack(claimed));
        assertEquals(Set.of(), consumer.subscription(), "no consumer joins the group after close");
    }

    /** A drainer that died leaves its consumer behind; the next claim on any thread closes it. */
    @Test
    void aDeadDrainersConsumerIsClosedByTheNextClaim() throws Exception {
        KafkaSpool spool = spool();
        Thread.ofVirtual()
                .start(() -> assertDoesNotThrow(() -> spool.claim(Duration.ZERO)))
                .join();
        MockConsumer<String, byte[]> orphan = consumer;
        consumer = new MockConsumer<>("earliest");

        spool.claim(Duration.ZERO);

        assertTrue(orphan.closed());
    }

    /**
     * Close leaves a live drainer's consumer to it, since a consumer is not thread-safe, and stops waiting when
     * interrupted; it closes the consumer of a drainer that exits while it waits. The bug is a consumer closed under a
     * running poll, or one left open in the group.
     */
    @Test
    @Timeout(10)
    void closeLeavesALiveDrainersConsumerToItAndClosesAnExitedOnes() throws Exception {
        KafkaSpool spool = spool();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread drainer = Thread.ofVirtual().start(() -> {
            assertDoesNotThrow(() -> spool.claim(Duration.ZERO));
            claimed.countDown();
            assertDoesNotThrow(() -> release.await());
        });
        claimed.await();

        Thread interrupted = closingAndWaiting(spool);
        interrupted.interrupt();
        interrupted.join();
        assertFalse(consumer.closed(), "its drainer is alive");

        Thread closer = closingAndWaiting(spool);
        release.countDown();
        drainer.join();
        closer.join();
        assertTrue(consumer.closed(), "its drainer exited");
    }

    /** A thread inside {@code close}, sleeping in its wait for the live drainers. */
    private static Thread closingAndWaiting(KafkaSpool spool) {
        Thread closer = Thread.ofVirtual().start(spool::close);
        while (closer.getState() != Thread.State.TIMED_WAITING) Thread.onSpinWait();
        return closer;
    }

    /** A lag read that fails is reported, and inside the cache window the broker is not asked again. */
    @Test
    void aFailedLagReadIsReportedAndCached() {
        KafkaSpool spool = spool();
        when(admin.listConsumerGroupOffsets("tessary-ingest")).thenThrow(new TimeoutException("slow"));

        IngestSpool.Stats idle = new IngestSpool.Stats("kafka", true, 0, 0, 0, 0, 0);
        assertEquals(idle, spool.stats());
        nanos.addAndGet(Duration.ofSeconds(4).toNanos());
        assertEquals(idle, spool.stats());

        assertEquals(Optional.of("broker not reachable at redpanda:9092: TimeoutException"), spool.unavailable());
        verify(admin, times(1)).listConsumerGroupOffsets("tessary-ingest");
    }

    /** An interrupt during any broker wait is kept on the thread, and the wait counts as not done. */
    @Test
    void anInterruptDuringABrokerWaitIsKept() {
        producer = producer(false);
        KafkaSpool spool = spool();
        Thread.currentThread().interrupt();
        assertEquals(IngestSpool.Admission.SHED, spool.append("p", ENTRIES));
        assertTrue(Thread.interrupted(), "publishing");

        when(admin.listConsumerGroupOffsets("tessary-ingest").partitionsToOffsetAndMetadata())
                .thenReturn(new KafkaFutureImpl<>());
        Thread.currentThread().interrupt();
        assertEquals(new IngestSpool.Stats("kafka", true, 0, 0, 0, 0, 0), spool.stats());
        assertTrue(Thread.interrupted(), "reading lag");

        when(admin.createTopics(anyCollection()).all())
                .thenReturn(new KafkaFutureImpl<>(), KafkaFuture.completedFuture(null));
        Thread.currentThread().interrupt();
        spool();
        assertTrue(Thread.interrupted(), "creating the topics");

        when(admin.incrementalAlterConfigs(anyMap()).all()).thenReturn(new KafkaFutureImpl<>());
        Thread.currentThread().interrupt();
        spool();
        assertTrue(Thread.interrupted(), "switching off write caching");
    }
}
