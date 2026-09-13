// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.MetricRollupRow;
import ai.tessary.usage.UsageUnit;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The heartbeat's lifetime span and L1 totals, read from {@code metric_rollup} against the real Postgres.
 * Rows need a real org and project: {@code metric_rollup} has cascading foreign keys to both.
 */
@SpringBootTest
class TelemetryCountsIntegrationTest {

    @Autowired
    MetricRollupRepository rollups;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @BeforeEach
    void emptyRollups() {
        jdbc.sql("DELETE FROM metric_rollup").update();
    }

    private void rollup(TenantFixture.Setup tenant, UsageUnit unit, long value, String bucketStart, String grain) {
        rollups.upsert(MetricRollupRow.of(
                Ids.ulid(),
                tenant.org().id(),
                tenant.project().id(),
                unit.wire(),
                value,
                bucketStart,
                grain,
                Instant.now().toString()));
    }

    @Test
    @DisplayName("an empty install totals 0")
    void installLifetimeTotal_emptyIsZero() {
        assertEquals(0L, rollups.installLifetimeTotal(UsageUnit.INGESTED_SPANS));
    }

    @Test
    @DisplayName("sums every day rollup across orgs and projects, and never the hour rows beside them")
    void installLifetimeTotal_sumsDayRowsOnly() {
        TenantFixture.Setup a = TenantFixture.bootstrap(tenants, "telemetry-counts-a");
        TenantFixture.Setup b = TenantFixture.bootstrap(tenants, "telemetry-counts-b");
        // Day rows for two orgs and projects.
        rollup(a, UsageUnit.INGESTED_SPANS, 1_000, "2026-09-11T00:00:00Z", UsageUnit.BUCKET_DAY);
        rollup(a, UsageUnit.INGESTED_SPANS, 2_500, "2026-09-12T00:00:00Z", UsageUnit.BUCKET_DAY);
        rollup(b, UsageUnit.INGESTED_SPANS, 400, "2026-09-12T00:00:00Z", UsageUnit.BUCKET_DAY);
        // Hour rows covering the same spans as a day row: counting them too would double the total.
        rollup(a, UsageUnit.INGESTED_SPANS, 600, "2026-09-12T10:00:00Z", UsageUnit.BUCKET_HOUR);
        rollup(a, UsageUnit.INGESTED_SPANS, 1_900, "2026-09-12T11:00:00Z", UsageUnit.BUCKET_HOUR);
        // Another unit on the same day.
        rollup(a, UsageUnit.L1_EVALS, 70, "2026-09-12T00:00:00Z", UsageUnit.BUCKET_DAY);

        assertEquals(3_900L, rollups.installLifetimeTotal(UsageUnit.INGESTED_SPANS));
        assertEquals(70L, rollups.installLifetimeTotal(UsageUnit.L1_EVALS));
    }
}
