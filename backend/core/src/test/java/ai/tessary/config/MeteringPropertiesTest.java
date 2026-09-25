// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MeteringPropertiesTest {

    /** The bug: a {@code tessary.metering.*} override binds to the wrong field or is silently ignored. */
    @Test
    void everyKeyBindsToItsOwnField() {
        MeteringProperties p = ConfigBinding.bind(
                "tessary.metering",
                new MeteringProperties(),
                Map.of(
                        "tessary.metering.storage-enabled", "true",
                        "tessary.metering.claim-batch", "11",
                        "tessary.metering.lease-seconds", "12"));

        assertEquals(true, p.isStorageEnabled());
        assertEquals(11, p.getClaimBatch());
        assertEquals(12L, p.getLeaseSeconds());
    }
}
