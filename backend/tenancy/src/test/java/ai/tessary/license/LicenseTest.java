// SPDX-License-Identifier: Apache-2.0
package ai.tessary.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LicenseTest {

    private static final License LICENSE = new License(
            "lic_1",
            "Pilot",
            null,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
            14,
            Map.of("rca_enabled", false, "slack_enabled", true),
            Map.of("ingested_traces_month", 1_000_000L));

    @Test
    void aKeyTheLicenseDoesNotNameIsUnconstrained() {
        assertEquals(Optional.of(false), LICENSE.allows("rca_enabled"));
        assertEquals(Optional.of(true), LICENSE.allows("slack_enabled"));
        assertEquals(Optional.empty(), LICENSE.allows("alerts_enabled"));
        assertEquals(Optional.of(1_000_000L), LICENSE.limit("ingested_traces_month"));
        assertEquals(Optional.empty(), LICENSE.limit("storage_bytes"));
    }

    /** The bug: a license dropped at midnight on its last day, or honored for good past its grace. */
    @Test
    void theLastDayIsInForceAndTheGraceKeepsAnExpiredLicenseHonored() {
        Instant lastDay = Instant.parse("2026-10-31T23:59:59Z");
        Instant dayAfter = Instant.parse("2026-11-01T00:00:00Z");
        Instant lastGraceDay = Instant.parse("2026-11-14T12:00:00Z");
        Instant pastGrace = Instant.parse("2026-11-15T00:00:00Z");

        assertFalse(LICENSE.expired(lastDay));
        assertTrue(LICENSE.expired(dayAfter));
        assertFalse(LICENSE.lapsed(dayAfter));
        assertFalse(LICENSE.lapsed(lastGraceDay));
        assertTrue(LICENSE.lapsed(pastGrace));
    }

    @Test
    void noneHasNoLicense() {
        assertEquals(Optional.empty(), LicenseSource.none().current());
    }
}
