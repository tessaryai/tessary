// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.metering.MeteringDtos.UsageBucketView;
import ai.tessary.metering.MeteringDtos.UsageTimeseriesView;
import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.MetricRollupRepository.UsageBucket;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The project-scoped usage read ({@code /v1/usage/timeseries}): it serves only a project-scoped token, and
 * always for that token's own project, since the request carries no project id to trust. The rollup read is
 * mocked; its SQL is {@code MeteringIntegrationTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class MeteringControllerTest {

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-02T00:00:00Z";

    @Mock
    MetricRollupRepository rollups;

    @Mock
    LlmUsageQueryRepository llmCalls;

    private MeteringController controller() {
        return new MeteringController(new MeteringService(rollups, llmCalls));
    }

    /**
     * A user session (no token) and a token bound to no project are both refused: the surface lives outside
     * {@code /api/**}, so this check is the only thing stopping a session from reading usage by path.
     */
    @Test
    void onlyAProjectScopedTokenIsServed() {
        TenantContext session = new TenantContext("user-1", null, "org-1", "p1", "member", null);
        TenantContext unboundToken = new TenantContext("user-1", null, "org-1", null, "member", "tok-1");

        TessaryException fromSession = assertThrows(
                TessaryException.class, () -> controller().timeseries(session, "l1_evals", null, FROM, TO));
        TessaryException fromUnbound = assertThrows(
                TessaryException.class, () -> controller().timeseries(unboundToken, "l1_evals", null, FROM, TO));

        assertEquals(MeteringError.TOKEN_REQUIRED, fromSession.error());
        assertEquals(MeteringError.TOKEN_REQUIRED, fromUnbound.error());
    }

    /** A token reads its own project's buckets, at the hour grain when none is named, echoing the unit asked for. */
    @Test
    void aTokenReadsItsOwnProjectsBucketsAtTheHourGrainByDefault() {
        TenantContext token = new TenantContext("user-1", null, "org-1", "p1", "member", "tok-1");
        when(rollups.projectTimeseries("p1", "l1_evals", "hour", FROM, TO))
                .thenReturn(List.of(new UsageBucket(FROM, 3)));

        UsageTimeseriesView view =
                controller().timeseries(token, "l1_evals", null, FROM, TO).data();

        assertEquals(new UsageTimeseriesView("l1_evals", view.asOf(), List.of(new UsageBucketView(FROM, 3))), view);
    }
}
