// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which {@code IngestSpool} buffers accepted batches: {@code memory} (the default: bounded by
 * bytes, a restart loses what is queued) or {@code kafka} (a Kafka-API broker, opt-in: accepted means
 * persisted). {@code max-lag-ms} is the age of the oldest unprocessed batch past which
 * the {@code /actuator/health/ingest} group (and the top-level status) reports DOWN; {@code 0} disables the check.
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

            /** Drainer threads, each with its own consumer in the group; the broker spreads the partitions across them. */
            @DefaultValue("4") int consumers,

            /**
             * Copies the broker keeps of every accepted batch, applied when the spool creates its topics.
             *
             * <p>1 suits the single-node Redpanda the {@code kafka} compose profile bundles, and is the only
             * value that broker can satisfy. Point {@code bootstrap-servers} at a multi-broker cluster and
             * this is what decides whether the mode's promise — accepted means persisted — survives losing a
             * broker: at 1, {@code acks=all} is one machine's disk, and that machine's failure loses batches
             * the edge already answered 200 to. 3 is the usual choice there.
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
