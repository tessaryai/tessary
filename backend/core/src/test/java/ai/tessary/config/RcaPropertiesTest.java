// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RcaPropertiesTest {

    /** The bug: a {@code tessary.rca.*} override binds to the wrong field or is silently ignored. */
    @Test
    void everyKeyBindsToItsOwnField() {
        RcaProperties p = ConfigBinding.bind(
                "tessary.rca",
                new RcaProperties(),
                Map.of(
                        "tessary.rca.batch-size", "11",
                        "tessary.rca.lease-seconds", "12",
                        "tessary.rca.max-attempts", "13",
                        "tessary.rca.agentic.sandbox", "local",
                        "tessary.rca.agentic.launcher-url", "http://launcher:8080",
                        "tessary.rca.agentic.launcher-api-key", "k",
                        "tessary.rca.agentic.timeout-ms", "14",
                        "tessary.rca.agentic.max-turns", "15",
                        "tessary.rca.agentic.mcp-base-url", "https://app.example"));

        RcaProperties.Agentic a = p.getAgentic();
        assertEquals(
                List.of(11L, 12L, 13L, 14L, 15L),
                List.of(
                        (long) p.getBatchSize(),
                        p.getLeaseSeconds(),
                        (long) p.getMaxAttempts(),
                        a.getTimeoutMs(),
                        (long) a.getMaxTurns()));
        assertEquals(
                List.of("local", "http://launcher:8080", "k", "https://app.example"),
                List.of(a.getSandbox(), a.getLauncherUrl(), a.getLauncherApiKey(), a.getMcpBaseUrl()));
    }
}
