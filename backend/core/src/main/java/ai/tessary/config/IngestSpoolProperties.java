// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code memory} (default; lost on restart) or {@code kafka} (accepted means persisted). {@code max-lag-ms} is the
 * oldest batch age past which {@code /actuator/health/ingest} reports DOWN; {@code 0} disables.
 */
@Component
@ConfigurationProperties(prefix = "tessary.ingest.spool")
public class IngestSpoolProperties {

    public static final Set<String> MODES = Set.of("memory", "kafka");

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

        /** Each with its own consumer in the group. */
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
         * 1 suits the bundled single-node Redpanda. On a multi-broker cluster, 1 means losing a broker loses batches
         * already answered 200; 3 is usual.
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
         * Derived so the pair cannot silently weaken each other: 2 whenever replicated, so an acknowledged publish
         * means two disks; 1 at a replication factor of 1.
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

    public void setMode(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(m)) {
            throw new IllegalArgumentException(
                    "tessary.ingest.spool.mode must be one of " + MODES + ", not '" + mode + "'");
        }
    }

    public long getMaxLagMs() {
        return maxLagMs;
    }

    public void setMaxLagMs(long maxLagMs) {
        this.maxLagMs = maxLagMs;
    }
}
