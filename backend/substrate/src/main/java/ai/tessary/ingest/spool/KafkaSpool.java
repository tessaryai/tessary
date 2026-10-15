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
import java.util.Objects;
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
 * A 200 means the batch is on the broker's log ({@code acks=all}). The offset is committed only after the substrate
 * write, so a crash replays into the idempotent write path. Keyed by project for per-partition order. A
 * {@code KafkaConsumer} is not thread-safe, so each drainer owns one.
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

    record Envelope(String projectId, List<RawEntry> entries) {}

    record KafkaReceipt(ConsumerRecord<String, byte[]> record) implements Receipt {}

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

    private long frameBytes() {
        return props.getMaxMessageBytes() + 64 * 1024;
    }

    /** Retried lazily: with no compose dependency the backend often boots before the broker. */
    private boolean ensureTopics() {
        if (topicsReady.get()) return true;
        // At most one attempt per admin timeout, so a down broker does not add seconds to every push.
        long now = System.nanoTime();
        if (now - topicsTriedAtNanos < ADMIN_TIMEOUT_MS * 1_000_000L) return false;
        topicsTriedAtNanos = now;
        Map<String, String> configs = Map.of(
                "max.message.bytes",
                String.valueOf(frameBytes()),
                // Set with the replication factor, since replicas the leader never waits for do not make a publish
                // durable.
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
                    // These apply at CREATE only, so log what was asked; the broker holds what is in force.
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

    /** Redpanda's switch; Apache Kafka rejects the key, so a refusal is informational. */
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
            // Not persisted, so not accepted: the edge answers 503 + Retry-After.
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
            // Park it on the dead-letter topic rather than block the partition forever.
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
     * Never throws: a failed dead-letter publish seeks back for redelivery, and a failed commit after a rebalance is
     * redelivered to the new owner.
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
        return ((KafkaReceipt) Objects.requireNonNull(claimed.receipt())).record();
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

    /** A consumer whose thread died cannot be raced by it, so any thread may close it. */
    private void closeStaleSlots() {
        for (Map.Entry<Thread, Slot> e : slots.entrySet()) {
            if (e.getKey().isAlive()) continue;
            if (slots.remove(e.getKey(), e.getValue())) e.getValue().consumer.close(Duration.ofSeconds(5));
        }
    }

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
                // From the log's beginning, not 0, so retention-trimmed records are not counted as lag.
                long from = c != null ? c.offset() : start == null ? 0 : start.offset();
                lag += Math.max(0, end.offset() - from);
            }
            snapshot = new Stats("kafka", true, lag, 0, 0, 0, deadLettered.get());
            lagFailure = null;
            if (publishFailure != null && now - publishFailedAtNanos > RECOVERY_QUIET.toNanos()) publishFailure = null;
        } catch (ExecutionException | TimeoutException | KafkaException e) {
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

    /** Admission is bounded by the broker's own storage, so the pre-decode gate is inert here. */
    @Override
    public double pressure() {
        return 0;
    }

    @Override
    public Optional<String> unavailable() {
        // A successful lag read says nothing about whether pushes land.
        String publish = publishFailure;
        return Optional.ofNullable(publish != null ? publish : lagFailure);
    }

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

    /** Consumers leave the group so partitions rebalance at once rather than after the session timeout. */
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
