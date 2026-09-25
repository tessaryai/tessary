// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrustrationPropertiesTest {

    /** The bug: a {@code tessary.frustration.*} override binds to the wrong field or is silently ignored. */
    @Test
    void everyKeyBindsToItsOwnField() {
        FrustrationProperties p = ConfigBinding.bind(
                "tessary.frustration",
                new FrustrationProperties(),
                Map.of(
                        "tessary.frustration.concurrency", "11",
                        "tessary.frustration.timeout-ms", "12",
                        "tessary.frustration.max-attempts", "13",
                        "tessary.frustration.page-retries", "14",
                        "tessary.frustration.credential-retry-seconds", "15"));

        assertEquals(
                List.of(11L, 12L, 13L, 14L, 15L),
                List.of(
                        (long) p.getConcurrency(),
                        p.getTimeoutMs(),
                        (long) p.getMaxAttempts(),
                        (long) p.getPageRetries(),
                        p.getCredentialRetrySeconds()));
    }
}
