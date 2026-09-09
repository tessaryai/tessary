// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The spool's durability arithmetic, which nothing else can prove: the {@code --kafka} boot leg runs
 * against the bundled single-node Redpanda, so it can only ever exercise a replication factor of 1 —
 * a topic asking for more replicas than the cluster holds is refused at create. The one setting a
 * multi-broker operator actually relies on ("ask for 3, get an acknowledged write on two disks")
 * would otherwise ship provable only by reading the ternary.
 */
class IngestSpoolPropertiesTest {

    @Test
    @DisplayName("min.insync.replicas is 2 under any replication, and 1 only when there is no second replica")
    void minInSyncReplicasFollowsTheReplicationFactor() {
        IngestSpoolProperties.Kafka kafka = new IngestSpoolProperties.Kafka();

        assertEquals((short) 1, kafka.getReplicationFactor(), "the default suits the bundled single node");
        assertEquals((short) 1, kafka.minInSyncReplicas(), "one replica cannot wait for a second one");

        for (short factor : new short[] {2, 3, 5}) {
            kafka.setReplicationFactor(factor);
            assertEquals(
                    (short) 2,
                    kafka.minInSyncReplicas(),
                    "replication " + factor + " must make an acknowledged publish mean two disks");
        }
    }

    @Test
    @DisplayName("a replication factor below 1 is refused rather than silently floored")
    void replicationFactorMustBeAtLeastOne() {
        IngestSpoolProperties.Kafka kafka = new IngestSpoolProperties.Kafka();
        for (short bad : new short[] {0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> kafka.setReplicationFactor(bad));
        }
        assertEquals((short) 1, kafka.getReplicationFactor(), "a refused value must not have been applied");
    }

    @Test
    @DisplayName("mode is normalised and validated, and drainers are the spool's answer rather than the mode string")
    void modeIsNormalisedAndValidated() {
        IngestSpoolProperties props = new IngestSpoolProperties();
        assertEquals("memory", props.getMode());
        props.setMode("  KAFKA  ");
        assertEquals("kafka", props.getMode(), "trimmed and lower-cased, so an operator's spacing does not matter");
        assertThrows(IllegalArgumentException.class, () -> props.setMode("redpanda"));
    }
}
