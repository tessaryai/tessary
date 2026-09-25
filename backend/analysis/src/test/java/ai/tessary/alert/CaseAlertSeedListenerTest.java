// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

import ai.tessary.tenant.ProjectCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The default case-opened rule is seeded after the project commits. With no surrounding transaction the
 * listener runs inline inside project creation, so a seed failure that escaped would fail the create of a
 * project that already exists; it has to be swallowed, since the heartbeat can seed the rule later.
 */
@ExtendWith(MockitoExtension.class)
class CaseAlertSeedListenerTest {

    @Mock
    AlertService alerts;

    @Test
    void aFailedSeedDoesNotFailProjectCreation() {
        when(alerts.ensureCaseOpenedRule("p1")).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> new CaseAlertSeedListener(alerts).onProjectCreated(new ProjectCreatedEvent("p1")));
    }
}
