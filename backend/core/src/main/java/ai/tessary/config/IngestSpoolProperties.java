// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Which {@code IngestSpool} buffers accepted batches (#984): {@code memory} (the default: bounded by
 * bytes, a restart loses what is queued) or {@code kafka} (a Kafka-API broker, opt-in: accepted means
 * persisted). {@code max-lag-ms} is the age of the oldest unprocessed batch past which
 * the {@code /actuator/health/ingest} group (and the top-level status) reports DOWN; {@code 0} disables the check.
 */
@Component
@ConfigurationProperties(prefix = "tessary.ingest.spool")
public class IngestSpoolProperties {

    public static final Set<String> MODES = Set.of("memory", "kafka");

    private String mode = "memory";
    private long maxLagMs = 300_000;
    private Kafka kafka = new Kafka();

    /** The Kafka-API spool's settings, read only when {@code mode=kafka}. */
    public static class Kafka {
        private String bootstrapServers = "redpanda:9092";
        private String topic = "tessary.ingest";
        private String deadLetterTopic = "tessary.ingest.dead-letter";
        private String groupId = "tessary-ingest";
        private int partitions = 8;
        private int consumers = 4;
        private short replicationFactor = 1;
        private long maxMessageBytes = 8L * 1024 * 1024;
        private long publishTimeoutMs = 10_000;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String v) {
            this.bootstrapServers = v;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String v) {
            this.topic = v;
        }

        public String getDeadLetterTopic() {
            return deadLetterTopic;
        }

        public void setDeadLetterTopic(String v) {
            this.deadLetterTopic = v;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String v) {
            this.groupId = v;
        }

        public int getPartitions() {
            return partitions;
        }

        /** Drainer threads, each with its own consumer in the group; the broker spreads the partitions across them. */
        public int getConsumers() {
            return consumers;
        }

        public void setConsumers(int v) {
            this.consumers = v;
        }

        public void setPartitions(int v) {
            this.partitions = v;
        }

        /**
         * Copies the broker keeps of every accepted batch, applied when the spool creates its topics.
         *
         * <p>1 suits the single-node Redpanda the {@code kafka} compose profile bundles, and is the only
         * value that broker can satisfy. Point {@code bootstrap-servers} at a multi-broker cluster and
         * this is what decides whether the mode's promise — accepted means persisted — survives losing a
         * broker: at 1, {@code acks=all} is one machine's disk, and that machine's failure loses batches
         * the edge already answered 200 to. 3 is the usual choice there.
         */
        public short getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(short v) {
            if (v < 1) {
                throw new IllegalArgumentException(
                        "tessary.ingest.spool.kafka.replication-factor must be at least 1, not " + v);
            }
            this.replicationFactor = v;
        }

        /**
         * How many replicas must hold a batch before the broker acknowledges it, derived rather than
         * configured so the pair cannot be set to a combination that silently weakens the other.
         *
         * <p>2 whenever there is more than one replica: with {@code acks=all} that is what makes an
         * acknowledged publish mean two disks. Leaving it at 1 under replication would let the leader
         * acknowledge alone whenever the followers lag, which is the single-copy window this key exists
         * to close. At {@code replicationFactor=1} it can only be 1 — there is no second replica to wait
         * for, and asking for one would refuse every publish.
         */
        public short minInSyncReplicas() {
            return replicationFactor > 1 ? (short) 2 : (short) 1;
        }

        public long getMaxMessageBytes() {
            return maxMessageBytes;
        }

        public void setMaxMessageBytes(long v) {
            this.maxMessageBytes = v;
        }

        public long getPublishTimeoutMs() {
            return publishTimeoutMs;
        }

        public void setPublishTimeoutMs(long v) {
            this.publishTimeoutMs = v;
        }
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(m)) {
            throw new IllegalArgumentException(
                    "tessary.ingest.spool.mode must be one of " + MODES + ", not '" + mode + "'");
        }
        this.mode = m;
    }

    public long getMaxLagMs() {
        return maxLagMs;
    }

    public void setMaxLagMs(long maxLagMs) {
        this.maxLagMs = maxLagMs;
    }
}
