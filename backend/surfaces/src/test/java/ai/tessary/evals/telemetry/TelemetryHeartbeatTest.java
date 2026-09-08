// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.telemetry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.tessary.evals.edition.Edition;
import ai.tessary.evals.storage.TraceV2Repository;
import ai.tessary.evals.tenant.OrganizationRepository;
import ai.tessary.evals.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The one gate this whole subsystem hangs off: {@link TelemetryProperties#isEnabled()} must be checked
 * BEFORE anything else in {@link TelemetryHeartbeat#tick()} runs — no DB read (not even the harmless
 * {@link InstallIdRepository}, which itself writes on first call), no HTTP client touch. This is what makes
 * devdocs/reference/telemetry-contract.md §3's "zero outbound calls, including DNS resolution, when
 * disabled" true. No network and no Spring context — the gate is checked with mocks, unit-level, exactly
 * as the contract's own risk (a silent no-op instead of a build-time failure) calls for.
 */
class TelemetryHeartbeatTest {

    @Test
    void disabledTelemetryTouchesNothingDownstream() {
        TelemetryProperties props = new TelemetryProperties();
        props.setEnabled(false);
        InstallIdRepository installIds = mock(InstallIdRepository.class);
        HomeTessaryClient client = mock(HomeTessaryClient.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        TraceV2Repository traces = mock(TraceV2Repository.class);

        TelemetryHeartbeat heartbeat = new TelemetryHeartbeat(
                props, installIds, client, orgs, projects, traces, new ObjectMapper(), Edition.open());
        heartbeat.tick();

        verifyNoInteractions(installIds, client, orgs, projects, traces);
    }
}
