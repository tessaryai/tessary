// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubstratePropertiesTest {

    /** The bug: a {@code tessary.ingest.substrate.*} override binds to the wrong field or is silently ignored. */
    @Test
    void everyKeyBindsToItsOwnField() {
        SubstrateProperties p = ConfigBinding.bind(
                "tessary.ingest.substrate",
                new SubstrateProperties(),
                Map.of(
                        "tessary.ingest.substrate.queue-max-bytes", "11",
                        "tessary.ingest.substrate.refuse-above-queue-fraction", "0.5",
                        "tessary.ingest.substrate.max-attempts", "12",
                        "tessary.ingest.substrate.retry-backoff-ms", "13",
                        "tessary.ingest.substrate.resolver-batch-size", "14",
                        "tessary.ingest.substrate.resolver-statement-timeout-seconds", "15",
                        "tessary.ingest.substrate.rollup-claim-limit", "16",
                        "tessary.ingest.substrate.rollup-reap-grace-seconds", "17",
                        "tessary.ingest.substrate.rollup-stale-after-ms", "18"));

        assertEquals(0.5, p.getRefuseAboveQueueFraction());
        assertEquals(
                List.of(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L),
                List.of(
                        p.getQueueMaxBytes(),
                        (long) p.getMaxAttempts(),
                        p.getRetryBackoffMs(),
                        (long) p.getResolverBatchSize(),
                        (long) p.getResolverStatementTimeoutSeconds(),
                        (long) p.getRollupClaimLimit(),
                        (long) p.getRollupReapGraceSeconds(),
                        p.getRollupStaleAfterMs()));
    }
}
