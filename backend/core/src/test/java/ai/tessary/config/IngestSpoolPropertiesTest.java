// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.testsupport.Props;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.core.NestedExceptionUtils;

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
        IngestSpoolProperties.Kafka kafka =
                Props.of(IngestSpoolProperties.class).kafka();

        assertEquals((short) 1, kafka.replicationFactor(), "the default suits the bundled single node");
        assertEquals((short) 1, kafka.minInSyncReplicas(), "one replica cannot wait for a second one");

        for (short factor : new short[] {2, 3, 5}) {
            assertEquals(
                    (short) 2,
                    kafka(factor).minInSyncReplicas(),
                    "replication " + factor + " must make an acknowledged publish mean two disks");
        }
    }

    @Test
    @DisplayName("a replication factor below 1 is refused rather than silently floored")
    void replicationFactorMustBeAtLeastOne() {
        for (short bad : new short[] {0, -1}) {
            BindException ex = assertThrows(BindException.class, () -> kafka(bad));
            assertInstanceOf(IllegalArgumentException.class, NestedExceptionUtils.getMostSpecificCause(ex));
        }
    }

    @Test
    @DisplayName("mode is validated after trimming and lower-casing, so only an unknown spool is refused")
    void modeIsNormalisedAndValidated() {
        assertEquals(
                "kafka",
                Props.of(IngestSpoolProperties.class, "mode", "  KAFKA  ").mode(),
                "trimmed and lower-cased, so an operator's spacing does not matter");
        BindException ex =
                assertThrows(BindException.class, () -> Props.of(IngestSpoolProperties.class, "mode", "redpanda"));
        assertInstanceOf(IllegalArgumentException.class, NestedExceptionUtils.getMostSpecificCause(ex));
    }

    private static IngestSpoolProperties.Kafka kafka(short replicationFactor) {
        return Props.of(IngestSpoolProperties.class, "kafka.replication-factor", replicationFactor)
                .kafka();
    }
}
