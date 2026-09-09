// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.spool;

import ai.tessary.config.IngestSpoolProperties;
import ai.tessary.ingest.RawEntry;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The opt-in spool (#984): a Kafka-API broker (Kafka, Redpanda, or any compatible) holds every
 * accepted batch. {@link #append} publishes with {@code acks=all} and waits for the broker's
 * acknowledgement before answering, so a 200 means the batch is on the broker's log (and on its
 * disk: write caching is turned off on the topics where the broker accepts it); a publish that fails
 * or times out is a
 * shed, and the edge tells the producer to retry. The drainer {@link #claim}s one record at a time
 * and {@link #ack}s by committing its offset only after the substrate write committed, so a crash
 * between the two replays the record into the idempotent write path rather than losing it. A batch
 * that exhausts its retries is {@link #nack}ed to the dead-letter topic and committed, so nothing is
 * dropped and nothing blocks the partition; if even that publish fails the consumer seeks back to
 * the record and it is redelivered. Neither settlement throws.
 *
 * <p>Messages are keyed by project, so one project's batches land on one partition in order, which
 * is what lets parallel consumers (M3) never contend on the same trace rows. Serialisation is the
 * decoded {@link RawEntry} list as JSON, the same shape the in-process spool holds, so the drain is
 * identical in both modes.
 *
 * <p>Each drainer thread owns a consumer of its own in the same group (a {@code KafkaConsumer} is not
 * thread-safe), so the broker spreads the partitions across the drainers and a project's partition is
 * drained by exactly one of them at a time. {@link #stats} reads lag through the admin client instead,
 * so any thread may ask.
 */
// CloseResource: the consumer handed around below lives as long as the spool and is closed by close()
// or by the owning thread on reset; the locals are references to it, not resources of their own.
@SuppressWarnings("PMD.CloseResource")
public final class KafkaSpool implements IngestSpool, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaSpool.class);
    private static final Duration STATS_TTL = Duration.ofSeconds(5);
    private static final Duration RECOVERY_QUIET = Duration.ofSeconds(30);
    private static final long ADMIN_TIMEOUT_MS = 3_000;
    private static final long MIN_PUBLISH_TIMEOUT_MS = 1_000;

    /** The wire shape of one message. */
    record Envelope(String projectId, List<RawEntry> entries) {}

    /** The broker record behind a claim, so ack and nack know what to commit. */
    record KafkaReceipt(ConsumerRecord<String, byte[]> record) implements Receipt {}

    /** A drainer thread's consumer and the record it is holding; only that thread touches the consumer. */
    private static final class Slot {
        final KafkaConsumer<String, byte[]> consumer;
        final Thread owner;
        final AtomicLong inFlightRecordMillis = new AtomicLong();

        Slot(KafkaConsumer<String, byte[]> consumer, Thread owner) {
            this.consumer = consumer;
            this.owner = owner;
        }
    }

    private final IngestSpoolProperties.Kafka props;
    private final ObjectMapper mapper;
    private final long publishTimeoutMs;
    private final KafkaProducer<String, byte[]> producer;
    private final Admin admin;
    /** One consumer per drainer thread, in the same group, so the broker spreads the partitions (M3). */
    private final Map<Thread, Slot> slots = new ConcurrentHashMap<>();

    private volatile boolean closing;
    private final AtomicBoolean topicsReady = new AtomicBoolean();
    private final AtomicLong deadLettered = new AtomicLong();
    private volatile @Nullable String publishFailure;
    private volatile long publishFailedAtNanos;
    private volatile @Nullable String lagFailure;
    private volatile long topicsTriedAtNanos;
    private volatile Stats cached = new Stats("kafka", true, 0, 0, 0, 0, 0);
    private volatile long cachedAtNanos;

    public KafkaSpool(IngestSpoolProperties.Kafka props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.publishTimeoutMs = Math.max(MIN_PUBLISH_TIMEOUT_MS, props.getPublishTimeoutMs());
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, String.valueOf(frameBytes()));
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(publishTimeoutMs));
        p.put(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                String.valueOf(Math.max(MIN_PUBLISH_TIMEOUT_MS, publishTimeoutMs / 2)));
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(publishTimeoutMs));
        this.producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
        Properties a = new Properties();
        a.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        a.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(ADMIN_TIMEOUT_MS));
        a.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(ADMIN_TIMEOUT_MS));
        this.admin = Admin.create(a);
        ensureTopics();
    }

    /** The largest frame a batch can need: the message cap plus headers, key and envelope slack. */
    private long frameBytes() {
        return props.getMaxMessageBytes() + 64 * 1024;
    }

    /**
     * Creates the two topics with the settings the contract needs: enough partitions to spread projects,
     * a message cap matching the app's; write caching is then turned off where the broker accepts it. Retried
     * lazily until it succeeds, because with no compose dependency the backend often boots first.
     */
    private boolean ensureTopics() {
        if (topicsReady.get()) return true;
        // At most one attempt per admin timeout window, so a broker that is down does not cost every
        // push three seconds on top of the shed.
        long now = System.nanoTime();
        if (now - topicsTriedAtNanos < ADMIN_TIMEOUT_MS * 1_000_000L) return false;
        topicsTriedAtNanos = now;
        Map<String, String> configs = Map.of(
                "max.message.bytes",
                String.valueOf(frameBytes()),
                // Set on CREATE, alongside the replication factor, because the two only mean anything
                // together: replicas the leader never waits for do not make an acknowledged publish
                // durable. See IngestSpoolProperties.Kafka.minInSyncReplicas.
                "min.insync.replicas",
                String.valueOf(props.minInSyncReplicas()));
        for (String topic : List.of(props.getTopic(), props.getDeadLetterTopic())) {
            try {
                admin.createTopics(List.of(new NewTopic(topic, props.getPartitions(), props.getReplicationFactor())
                                .configs(configs)))
                        .all()
                        .get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.info(
                        "ingest spool topic created topic={} partitions={} replication={} min-isr={}",
                        topic,
                        props.getPartitions(),
                        props.getReplicationFactor(),
                        props.minInSyncReplicas());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TopicExistsException) {
                    // The settings above apply at CREATE only, so an operator who raises
                    // replication-factor after the first boot would otherwise get silence from a topic
                    // that kept the value it was made with. Say what was asked for; the broker holds
                    // what is actually in force.
                    log.info(
                            "ingest spool topic exists topic={}; it keeps the settings it was created with,"
                                    + " not the requested partitions={} replication={} min-isr={}",
                            topic,
                            props.getPartitions(),
                            props.getReplicationFactor(),
                            props.minInSyncReplicas());
                } else {
                    log.warn(
                            "ingest spool topic not created topic={} error={}",
                            topic,
                            cause == null ? "unknown" : cause.getClass().getSimpleName());
                    publishFailure = "broker refused topic " + topic + ": "
                            + (cause == null ? "unknown" : cause.getClass().getSimpleName());
                    publishFailedAtNanos = System.nanoTime();
                    return false;
                }
            } catch (TimeoutException | KafkaException e) {
                log.warn(
                        "ingest spool broker not reachable bootstrap={}; publishes shed until it is",
                        props.getBootstrapServers());
                publishFailure = "broker not reachable at " + props.getBootstrapServers();
                publishFailedAtNanos = System.nanoTime();
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        disableWriteCaching();
        topicsReady.set(true);
        return true;
    }

    /**
     * {@code write.caching=false} is Redpanda's per-topic switch and Apache Kafka rejects the key, so
     * it is applied after creation and a refusal is informational: on Kafka, the durability of an
     * acknowledged write is the broker's own flush and replication policy.
     */
    private void disableWriteCaching() {
        for (String topic : List.of(props.getTopic(), props.getDeadLetterTopic())) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            try {
                admin.incrementalAlterConfigs(Map.of(
                                resource,
                                List.of(new AlterConfigOp(
                                        new ConfigEntry("write.caching", "false"), AlterConfigOp.OpType.SET))))
                        .all()
                        .get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                log.info(
                        "ingest spool topic={} keeps the broker's own write caching (not Redpanda, or refused): {}",
                        topic,
                        e.getCause() == null
                                ? "unknown"
                                : e.getCause().getClass().getSimpleName());
            } catch (TimeoutException | KafkaException e) {
                log.info(
                        "ingest spool topic={} write caching not set: {}",
                        topic,
                        e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One drainer per configured consumer: messages are keyed by project, so a project stays on one of them. */
    @Override
    public int drainers() {
        return Math.max(1, props.getConsumers());
    }

    @Override
    public Admission append(String projectId, List<RawEntry> entries) {
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new Envelope(projectId, entries));
        } catch (JacksonException e) {
            log.warn("ingest spool could not encode a batch project={} spans={}", projectId, entries.size());
            return Admission.SHED;
        }
        if (body.length > props.getMaxMessageBytes()) {
            log.warn(
                    "ingest spool batch exceeds max-message-bytes — refused project={} spans={} bytes={} max={}",
                    projectId,
                    entries.size(),
                    body.length,
                    props.getMaxMessageBytes());
            return Admission.OVERSIZE;
        }
        if (!ensureTopics()) return Admission.SHED;
        try {
            producer.send(new ProducerRecord<>(props.getTopic(), projectId, body))
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            publishFailure = null;
            return Admission.ACCEPTED;
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            // Not persisted, so not accepted: the edge answers 503 + Retry-After and the exporter re-sends.
            Throwable cause = e instanceof ExecutionException ee && ee.getCause() != null ? ee.getCause() : e;
            publishFailure = "publish failed: " + cause.getClass().getSimpleName();
            publishFailedAtNanos = System.nanoTime();
            log.warn(
                    "ingest spool publish failed — shed batch project={} spans={} error={}",
                    projectId,
                    entries.size(),
                    cause.getClass().getSimpleName());
            return Admission.SHED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Admission.SHED;
        }
    }

    @Override
    public Optional<Claimed> claim(Duration wait) throws InterruptedException {
        closeStaleSlots();
        if (closing) {
            Slot mine = slots.remove(Thread.currentThread());
            if (mine != null) mine.consumer.close(Duration.ofSeconds(5));
            Thread.sleep(Math.min(wait.toMillis(), 200));
            return Optional.empty();
        }
        Slot slot = slot();
        KafkaConsumer<String, byte[]> c = slot.consumer;
        ConsumerRecords<String, byte[]> records;
        try {
            records = c.poll(wait);
        } catch (WakeupException e) {
            return Optional.empty();
        } catch (InterruptException e) {
            InterruptedException interrupted = new InterruptedException("interrupted while polling the ingest spool");
            interrupted.initCause(e);
            throw interrupted;
        } catch (KafkaException e) {
            log.warn("ingest spool poll failed error={}", e.getClass().getSimpleName());
            Thread.sleep(Math.min(wait.toMillis(), 1_000));
            return Optional.empty();
        }
        if (records.isEmpty()) return Optional.empty();
        ConsumerRecord<String, byte[]> record = records.iterator().next();
        slot.inFlightRecordMillis.set(record.timestamp());
        Envelope envelope;
        try {
            envelope = mapper.readValue(record.value(), Envelope.class);
        } catch (IOException e) {
            // Undecodable: park it on the dead-letter topic rather than block the partition forever.
            log.warn("ingest spool record undecodable partition={} offset={}", record.partition(), record.offset());
            settle(c, record, true);
            return Optional.empty();
        }
        return Optional.of(new Claimed(
                envelope.projectId(),
                envelope.entries(),
                record.value().length,
                System.nanoTime(),
                new KafkaReceipt(record)));
    }

    @Override
    public void ack(Claimed claimed) {
        settle(slot().consumer, record(claimed), false);
    }

    @Override
    public void nack(Claimed claimed) {
        settle(slot().consumer, record(claimed), true);
    }

    /**
     * Commit past the record, parking it on the dead-letter topic first when asked. Never throws: a
     * dead-letter publish that fails leaves the offset uncommitted and seeks back, so the record is
     * redelivered; a commit that fails (a rebalance took the partition) is logged, and the record is
     * redelivered to whoever now holds the partition. Either way the drainer keeps draining.
     */
    private void settle(KafkaConsumer<String, byte[]> c, ConsumerRecord<String, byte[]> record, boolean deadLetter) {
        Slot mine = slots.get(Thread.currentThread());
        if (mine != null) mine.inFlightRecordMillis.set(0);
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        if (deadLetter && !deadLetter(record)) {
            try {
                c.seek(tp, record.offset());
            } catch (RuntimeException e) {
                log.warn(
                        "ingest spool could not seek back to a batch; it is redelivered on rebalance error={}",
                        e.getClass().getSimpleName());
            }
            return;
        }
        try {
            c.commitSync(Map.of(tp, new OffsetAndMetadata(record.offset() + 1)));
        } catch (KafkaException e) {
            log.warn(
                    "ingest spool commit failed partition={} offset={} error={}; the batch is redelivered",
                    record.partition(),
                    record.offset(),
                    e.getClass().getSimpleName());
        }
    }

    private static ConsumerRecord<String, byte[]> record(Claimed claimed) {
        if (!(claimed.receipt() instanceof KafkaReceipt r)) {
            throw new IllegalStateException("a Kafka spool claim carries its record");
        }
        return r.record();
    }

    private boolean deadLetter(ConsumerRecord<String, byte[]> record) {
        try {
            producer.send(new ProducerRecord<>(props.getDeadLetterTopic(), record.key(), record.value()))
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            deadLettered.incrementAndGet();
            log.warn(
                    "ingest spool batch dead-lettered project={} partition={} offset={}",
                    record.key(),
                    record.partition(),
                    record.offset());
            return true;
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            log.error(
                    "ingest spool could not dead-letter a batch; it will be redelivered error={}",
                    e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The calling thread's own consumer, created on first use; a thread arriving after close releases its own. */
    private Slot slot() {
        Thread me = Thread.currentThread();
        if (closing) {
            Slot mine = slots.remove(me);
            if (mine != null) mine.consumer.close(Duration.ofSeconds(5));
            throw new IllegalStateException("ingest spool is closing");
        }
        Slot existing = slots.get(me);
        if (existing != null) return existing;
        ensureTopics();
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, props.getGroupId());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        p.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, String.valueOf(frameBytes()));
        p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, String.valueOf(frameBytes()));
        KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
        c.subscribe(List.of(props.getTopic()));
        Slot fresh = new Slot(c, me);
        slots.put(me, fresh);
        return fresh;
    }

    /** A consumer whose drainer thread died cannot be raced by it, so any thread may close it; the group rebalances. */
    private void closeStaleSlots() {
        for (Map.Entry<Thread, Slot> e : slots.entrySet()) {
            if (e.getKey().isAlive()) continue;
            if (slots.remove(e.getKey(), e.getValue())) e.getValue().consumer.close(Duration.ofSeconds(5));
        }
    }

    /**
     * Test seam: drop the calling thread's consumer without committing, the way a crashed process
     * would; the next claim on that thread gets a fresh one and the broker redelivers.
     */
    public void resetConsumerForTest() {
        Slot mine = slots.remove(Thread.currentThread());
        if (mine != null) mine.consumer.close(Duration.ofSeconds(5));
    }

    @Override
    public Stats stats() {
        long now = System.nanoTime();
        Stats snapshot = cached;
        if (now - cachedAtNanos < STATS_TTL.toNanos()) {
            return withAge(snapshot);
        }
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(props.getGroupId())
                    .partitionsToOffsetAndMetadata()
                    .get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            TopicDescription description = admin.describeTopics(List.of(props.getTopic()))
                    .allTopicNames()
                    .get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .get(props.getTopic());
            if (description == null) return withAge(snapshot);
            Set<TopicPartition> partitions = description.partitions().stream()
                    .map(info -> new TopicPartition(props.getTopic(), info.partition()))
                    .collect(Collectors.toSet());
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
            for (TopicPartition tp : partitions) {
                latest.put(tp, OffsetSpec.latest());
                earliest.put(tp, OffsetSpec.earliest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(latest).all().get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> starts =
                    admin.listOffsets(earliest).all().get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long lag = 0;
            for (TopicPartition tp : partitions) {
                ListOffsetsResult.ListOffsetsResultInfo end = ends.get(tp);
                ListOffsetsResult.ListOffsetsResultInfo start = starts.get(tp);
                if (end == null) continue;
                OffsetAndMetadata c = committed.get(tp);
                // From the committed offset, or from the log's beginning (not 0) for a partition never
                // committed, so retention that already trimmed the log is not counted as lag.
                long from = c != null ? c.offset() : start == null ? 0 : start.offset();
                lag += Math.max(0, end.offset() - from);
            }
            snapshot = new Stats("kafka", true, lag, 0, 0, 0, deadLettered.get());
            lagFailure = null;
            // The broker answers again and no push has failed for a while: an idle instance recovers.
            if (publishFailure != null && now - publishFailedAtNanos > RECOVERY_QUIET.toNanos()) publishFailure = null;
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            // Keep the last known depth and say the broker could not be asked; health reads that.
            lagFailure = "broker not reachable at " + props.getBootstrapServers() + ": "
                    + e.getClass().getSimpleName();
            log.warn("ingest spool lag read failed error={}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cached = snapshot;
        cachedAtNanos = now;
        return withAge(snapshot);
    }

    /**
     * The broker has no in-process budget to report: admission is bounded by the broker's own storage,
     * and the pre-decode gate is inert in this mode. The OTLP body cap still bounds one request's heap.
     */
    @Override
    public double pressure() {
        return 0;
    }

    @Override
    public Optional<String> unavailable() {
        // The publish path's verdict wins: a lag read that succeeds says nothing about whether pushes land.
        String publish = publishFailure;
        return Optional.ofNullable(publish != null ? publish : lagFailure);
    }

    /** The age of the oldest record any drainer is holding, from its broker timestamp; 0 when none is in flight. */
    private Stats withAge(Stats s) {
        long oldest = 0;
        for (Slot slot : slots.values()) {
            long recordMillis = slot.inFlightRecordMillis.get();
            if (recordMillis > 0 && (oldest == 0 || recordMillis < oldest)) oldest = recordMillis;
        }
        long age = oldest <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - oldest);
        return new Stats(s.mode(), s.durable(), s.depth(), s.bytes(), s.maxBytes(), age, deadLettered.get());
    }

    @Override
    public void close() {
        closing = true;
        try {
            producer.close(Duration.ofSeconds(5));
        } finally {
            try {
                admin.close(Duration.ofSeconds(5));
            } finally {
                closeConsumers();
            }
        }
    }

    /**
     * Every consumer leaves the group on shutdown so the partitions rebalance at once rather than after
     * the session timeout. The calling thread closes its own directly; the others are woken and given a
     * moment to be closed by their owners on the next claim, and closed here if the owner is already
     * gone (a dead thread cannot race).
     */
    private void closeConsumers() {
        closing = true;
        Slot mine = slots.remove(Thread.currentThread());
        if (mine != null) mine.consumer.close(Duration.ofSeconds(5));
        for (Slot slot : slots.values()) slot.consumer.wakeup();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!slots.isEmpty() && System.nanoTime() < deadline) {
            boolean anyOwnerAlive = false;
            for (Slot slot : slots.values()) anyOwnerAlive |= slot.owner.isAlive();
            if (!anyOwnerAlive) break;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Map.Entry<Thread, Slot> e : slots.entrySet()) {
            if (!e.getKey().isAlive() && slots.remove(e.getKey(), e.getValue())) {
                e.getValue().consumer.close(Duration.ofSeconds(5));
            }
        }
    }
}
