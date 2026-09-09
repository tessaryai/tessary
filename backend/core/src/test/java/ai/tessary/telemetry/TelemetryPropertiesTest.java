// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TelemetryProperties#isEnabled()} is on-by-default — the ONE fact everything
 * downstream in {@code TelemetryHeartbeat} gates on before touching {@link InstallIdRepository} or
 * {@link HomeTessaryClient} at all (see that class's own gate-order test in {@code surfaces}, where the
 * repositories/HTTP client actually live). This test covers only the binding itself: default true,
 * and the setter this package's one {@code @ConfigurationProperties} class exposes for
 * {@code TESSARY_TELEMETRY_ENABLED} to bind onto.
 */
class TelemetryPropertiesTest {

    @Test
    void enabledByDefault_perD6OptOut() {
        assertTrue(new TelemetryProperties().isEnabled());
    }

    @Test
    void setEnabledFlipsIt() {
        TelemetryProperties props = new TelemetryProperties();
        props.setEnabled(false);
        assertFalse(props.isEnabled());
    }
}
