// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassifierPropertiesTest {

    /**
     * The bug: a setter that writes the wrong field, or a key that no longer binds, leaves an operator's
     * {@code tessary.classifier.*} override silently ignored and the worker running on the default.
     */
    @Test
    void everyKeyBindsToItsOwnField() {
        ClassifierProperties p = ConfigBinding.bind(
                "tessary.classifier",
                new ClassifierProperties(),
                Map.ofEntries(
                        Map.entry("tessary.classifier.batch-size", "11"),
                        Map.entry("tessary.classifier.encoder-batch-size", "12"),
                        Map.entry("tessary.classifier.lease-seconds", "13"),
                        Map.entry("tessary.classifier.max-attempts", "14"),
                        Map.entry("tessary.classifier.dead-letter-cooldown-seconds", "15"),
                        Map.entry("tessary.classifier.thread-max-observations", "16"),
                        Map.entry("tessary.classifier.triage-min-trace-count", "17"),
                        Map.entry("tessary.classifier.triage-breaker-failures", "18"),
                        Map.entry("tessary.classifier.triage-breaker-cooldown-seconds", "19"),
                        Map.entry("tessary.classifier.triage-config-retry-seconds", "20"),
                        Map.entry("tessary.classifier.triage-mcp-base-url", "https://app.example"),
                        Map.entry("tessary.classifier.triage-sandbox", "local")));

        assertEquals(
                List.of(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L),
                List.of(
                        (long) p.getBatchSize(),
                        (long) p.getEncoderBatchSize(),
                        p.getLeaseSeconds(),
                        (long) p.getMaxAttempts(),
                        p.getDeadLetterCooldownSeconds(),
                        (long) p.getThreadMaxObservations(),
                        p.getTriageMinTraceCount(),
                        (long) p.getTriageBreakerFailures(),
                        p.getTriageBreakerCooldownSeconds(),
                        p.getTriageConfigRetrySeconds()));
        assertEquals("https://app.example", p.getTriageMcpBaseUrl());
        assertEquals("local", p.getTriageSandbox());
    }
}
