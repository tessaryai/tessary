// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.metering.MeteringService;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.QueryError;
import ai.tessary.evals.query.QueryDtos.CountRequest;
import ai.tessary.evals.query.QueryDtos.FacetsRequest;
import ai.tessary.evals.query.QueryDtos.SearchRequest;
import ai.tessary.evals.query.QueryDtos.TimeRange;
import ai.tessary.evals.query.QueryDtos.TimeseriesRequest;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import ai.tessary.evals.usage.MetricRollupRepository;
import ai.tessary.evals.usage.MetricRollupRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance: the pre-aggregated {@code metric_rollup} table is reachable through the generic
 * aggregation-first Query API as the {@code metric_rollups} dataset, with a {@code SUM(value)} measure (not
 * {@code COUNT(*)}), bucketed on {@code bucket_start} (not {@code created_at}), faceted/filtered by
 * {@code metric}/{@code granularity}. Exercised against the real pgvector Postgres
 * (Testcontainers) so the SUM/measure SQL and the {@code bucket_start::timestamptz} bucketing run for real,
 * and asserts the Query-API results match the dedicated {@code MeteringController} ({@link MeteringService})
 * timeseries — the parity the issue requires.
 */
@SpringBootTest
class QueryUsageRollupsTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    QueryController controller;

    @Autowired
    MetricRollupRepository rollups;

    @Autowired
    MeteringService metering;

    @Autowired
    TenantService tenants;

    private static TenantContext token(String projectId) {
        return new TenantContext("user-1", null, "org-1", projectId, "member", "tok-1");
    }

    /** Seed one hourly metric_rollup row. */
    private void seed(String orgId, String pid, String unit, long value, String bucketStart) {
        seed(orgId, pid, unit, value, bucketStart, "hour");
    }

    private void seed(String orgId, String pid, String unit, long value, String bucketStart, String granularity) {
        rollups.upsert(MetricRollupRow.of(
                Ids.ulid(),
                orgId,
                pid,
                unit,
                value,
                bucketStart,
                granularity,
                Instant.now().toString()));
    }

    @Test
    void usageRollupsDataset_sumsAmount_filtersAndFacetsAndMatchesMeteringController() {
        var fix = TenantFixture.bootstrap(tenants, "query-usage");
        String orgId = fix.org().id();
        String pid = fix.project().id();
        TenantContext ctx = token(pid);

        // Mid-day hours so date_trunc('day', bucket_start::timestamptz) lands on the same UTC day regardless
        // of the test connection's session timezone (avoids a midnight-straddle false split).
        Instant h0 = Instant.parse("2026-06-10T10:00:00Z");
        Instant h1 = h0.plus(1, ChronoUnit.HOURS);

        // ingested_spans: 10 in hour 0, 4 in hour 1 (hourly grain). One row per (project, unit, bucket)
        // since Track A collapsed the per-environment grain.
        seed(orgId, pid, "ingested_spans", 10, h0.toString());
        seed(orgId, pid, "ingested_spans", 4, h1.toString());
        // l1_evals: 1000 in hour 0 — a different unit, to verify metric filtering.
        seed(orgId, pid, "l1_evals", 1000, h0.toString());
        // A DAILY-grain row that must NOT be mixed into the hourly timeseries (granularity filter).
        seed(orgId, pid, "ingested_spans", 999, h0.truncatedTo(ChronoUnit.DAYS).toString(), "day");

        TimeRange range = new TimeRange("2026-06-09T00:00:00Z", "2026-06-11T00:00:00Z");
        Map<String, String> hourSpans = Map.of("metric", "ingested_spans", "granularity", "hour");

        // ---- count: SUM(value), not COUNT(*) of rows ----
        long total = controller
                .count(ctx, new CountRequest("metric_rollups", range, hourSpans))
                .data()
                .count();
        assertEquals(14, total, "SUM of hourly ingested_spans values (10+4), not the 2 row COUNT");

        // ---- timeseries at hour grain: two buckets ----
        var hourly = controller
                .timeseries(ctx, new TimeseriesRequest("metric_rollups", "hour", range, hourSpans))
                .data()
                .buckets();
        assertEquals(2, hourly.size(), "two hourly buckets");
        assertEquals(10, hourly.get(0).count(), "hour 0");
        assertEquals(4, hourly.get(1).count(), "hour 1");

        // ---- timeseries re-truncated to day grain: the hourly rows roll up to one day bucket ----
        var daily = controller
                .timeseries(ctx, new TimeseriesRequest("metric_rollups", "day", range, hourSpans))
                .data()
                .buckets();
        assertEquals(1, daily.size(), "the hourly rows re-truncate to one day bucket");
        assertEquals(14, daily.get(0).count(), "hourly rows re-truncated + SUMmed to the day");

        // ---- facets by metric (no unit filter): ingested_spans=14, l1_evals=1000 ----
        var byUnit = controller
                .facets(ctx, new FacetsRequest("metric_rollups", "metric", range, Map.of("granularity", "hour"), null))
                .data()
                .facets();
        assertEquals(14, facetValue(byUnit, "ingested_spans"));
        assertEquals(1000, facetValue(byUnit, "l1_evals"));

        // ---- parity with the dedicated MeteringController timeseries (the issue's acceptance) ----
        var metered = metering.projectTimeseries(pid, "ingested_spans", "hour", range.from(), range.to());
        assertEquals(
                metered.stream().mapToLong(b -> b.value()).sum(),
                hourly.stream().mapToLong(b -> b.count()).sum(),
                "Query API totals match the dedicated MeteringController timeseries");

        // ---- search is unsupported on a pre-aggregated dataset (400, not a wrong-column keyset) ----
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> controller.search(
                        ctx, new SearchRequest("metric_rollups", null, "keyword", null, null, null, null)));
        assertEquals(QueryError.SEARCH_UNSUPPORTED_FOR_DATASET, ex.error());
    }

    private static long facetValue(List<QueryDtos.FacetBucket> facets, @Nullable String value) {
        return facets.stream()
                .filter(f -> java.util.Objects.equals(f.value(), value))
                .mapToLong(QueryDtos.FacetBucket::count)
                .findFirst()
                .orElse(0L);
    }
}
