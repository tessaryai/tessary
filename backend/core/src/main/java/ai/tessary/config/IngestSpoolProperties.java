// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code memory} (default; lost on restart) or {@code kafka} (accepted means persisted). {@code max-lag-ms} is the
 * oldest batch age past which {@code /actuator/health/ingest} reports DOWN; {@code 0} disables.
 */
@ConfigurationProperties(prefix = "tessary.ingest.spool")
public record IngestSpoolProperties(
        @DefaultValue("memory") String mode,
        @DefaultValue("300000") long maxLagMs,
        @DefaultValue Kafka kafka) {

    public static final Set<String> MODES = Set.of("memory", "kafka");

    /** The Kafka-API spool's settings, read only when {@code mode=kafka}. */
    public record Kafka(
            @DefaultValue("redpanda:9092") String bootstrapServers,
            @DefaultValue("tessary.ingest") String topic,
            @DefaultValue("tessary.ingest.dead-letter") String deadLetterTopic,
            @DefaultValue("tessary-ingest") String groupId,
            @DefaultValue("8") int partitions,

            /** Each with its own consumer in the group. */
            @DefaultValue("4") int consumers,

            /**
             * 1 suits the bundled single-node Redpanda. On a multi-broker cluster, 1 means losing a broker loses
             * batches already answered 200; 3 is usual.
             */
            @DefaultValue("1") short replicationFactor,
            @DefaultValue("8388608") long maxMessageBytes,
            @DefaultValue("10000") long publishTimeoutMs) {

        public Kafka {
            if (replicationFactor < 1) {
                throw new IllegalArgumentException(
                        "tessary.ingest.spool.kafka.replication-factor must be at least 1, not " + replicationFactor);
            }
        }

        /**
         * Derived so the pair cannot silently weaken each other: 2 whenever replicated, so an acknowledged publish
         * means two disks; 1 at a replication factor of 1.
         */
        public short minInSyncReplicas() {
            return replicationFactor > 1 ? (short) 2 : (short) 1;
        }
    }

    public IngestSpoolProperties {
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(m)) {
            throw new IllegalArgumentException(
                    "tessary.ingest.spool.mode must be one of " + MODES + ", not '" + mode + "'");
        }
        mode = m;
    }
}
