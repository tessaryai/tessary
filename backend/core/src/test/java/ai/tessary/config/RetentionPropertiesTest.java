// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetentionPropertiesTest {

    /**
     * The bug: a {@code tessary.retention.*} override (a shorter TTL, the kill switch) binds to the wrong
     * field or not at all, so the sweep deletes on the default schedule the operator tried to change.
     */
    @Test
    void everyKeyBindsToItsOwnField() {
        RetentionProperties p = ConfigBinding.bind(
                "tessary.retention",
                new RetentionProperties(),
                Map.of(
                        "tessary.retention.enabled", "false",
                        "tessary.retention.batch-size", "11",
                        "tessary.retention.max-batches-per-sweep", "12",
                        "tessary.retention.trace-ttl-days", "13",
                        "tessary.retention.detection-ttl-days", "14",
                        "tessary.retention.media-grace-hours", "15"));

        assertEquals(false, p.isEnabled());
        assertEquals(
                List.of(11, 12, 13, 14, 15),
                List.of(
                        p.getBatchSize(),
                        p.getMaxBatchesPerSweep(),
                        p.getTraceTtlDays(),
                        p.getDetectionTtlDays(),
                        p.getMediaGraceHours()));
    }
}
