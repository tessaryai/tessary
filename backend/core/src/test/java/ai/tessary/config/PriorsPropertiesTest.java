// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Boot-time fail-fast validation of the priors governance knobs: a misconfigured privacy boundary
 * ({@code min-cohort < 2} would publish a cohort-of-one; non-positive {@code epsilon}/{@code
 * sensitivity} makes the DP noise undefined) must refuse to start rather than silently weaken the
 * guarantee. {@code validate()} mirrors what {@code @PostConstruct} runs at boot.
 */
class PriorsPropertiesTest {

    private static PriorsProperties props(int minCohort, double epsilon, double sensitivity) {
        PriorsProperties p = new PriorsProperties();
        p.setMinCohort(minCohort);
        p.setEpsilon(epsilon);
        p.setSensitivity(sensitivity);
        return p;
    }

    @Test
    void acceptsAConservativeDefaultConfiguration() {
        assertDoesNotThrow(() -> props(5, 1.0, 1.0).validate());
        assertDoesNotThrow(() -> props(2, 0.5, 1.0).validate()); // exactly the k-anonymity floor
    }

    @Test
    void rejectsACohortOfOne_soKAnonymityCannotBeDefeated() {
        assertThrows(IllegalStateException.class, () -> props(1, 1.0, 1.0).validate());
        assertThrows(IllegalStateException.class, () -> props(0, 1.0, 1.0).validate());
    }

    @Test
    void rejectsANonPositiveEpsilon() {
        assertThrows(IllegalStateException.class, () -> props(5, 0.0, 1.0).validate());
        assertThrows(IllegalStateException.class, () -> props(5, -1.0, 1.0).validate());
    }

    @Test
    void rejectsANonPositiveSensitivity() {
        assertThrows(IllegalStateException.class, () -> props(5, 1.0, 0.0).validate());
        assertThrows(IllegalStateException.class, () -> props(5, 1.0, -1.0).validate());
    }
}
