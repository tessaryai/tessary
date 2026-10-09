// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.metering.MeteringService;
import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.query.QueryDtos.CountRequest;
import ai.tessary.query.QueryDtos.FacetsRequest;
import ai.tessary.query.QueryDtos.SearchRequest;
import ai.tessary.query.QueryDtos.TimeRange;
import ai.tessary.query.QueryDtos.TimeseriesRequest;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.MetricRollupRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code metric_rollup} through the Query API as the {@code metric_rollups} dataset, against real Postgres: a {@code
 * SUM(value)} measure, bucketed on {@code bucket_start}, filtered by {@code metric}/{@code granularity}, matching
 * {@link MeteringService}'s timeseries.
 */
@SpringBootTest
class QueryUsageRollupsTest {

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

        // Mid-day hours, so the day truncation holds in any session timezone.
        Instant h0 = Instant.parse("2026-06-10T10:00:00Z");
        Instant h1 = h0.plus(1, ChronoUnit.HOURS);

        // ingested_spans: 10 then 4, hourly. One row per (project, unit, bucket).
        seed(orgId, pid, "ingested_spans", 10, h0.toString());
        seed(orgId, pid, "ingested_spans", 4, h1.toString());
        // A different unit, to verify metric filtering.
        seed(orgId, pid, "l1_evals", 1000, h0.toString());
        // A daily row the hourly timeseries must exclude.
        seed(orgId, pid, "ingested_spans", 999, h0.truncatedTo(ChronoUnit.DAYS).toString(), "day");

        TimeRange range = new TimeRange("2026-06-09T00:00:00Z", "2026-06-11T00:00:00Z");
        Map<String, String> hourSpans = Map.of("metric", "ingested_spans", "granularity", "hour");

        // SUM(value), not COUNT(*).
        long total = controller
                .count(ctx, new CountRequest("metric_rollups", range, hourSpans))
                .data()
                .count();
        assertEquals(14, total, "SUM of hourly ingested_spans values (10+4), not the 2 row COUNT");

        var hourly = controller
                .timeseries(ctx, new TimeseriesRequest("metric_rollups", "hour", range, hourSpans))
                .data()
                .buckets();
        assertEquals(2, hourly.size(), "two hourly buckets");
        assertEquals(10, hourly.get(0).count(), "hour 0");
        assertEquals(4, hourly.get(1).count(), "hour 1");

        // Re-truncated to day grain: one bucket.
        var daily = controller
                .timeseries(ctx, new TimeseriesRequest("metric_rollups", "day", range, hourSpans))
                .data()
                .buckets();
        assertEquals(1, daily.size(), "the hourly rows re-truncate to one day bucket");
        assertEquals(14, daily.get(0).count(), "hourly rows re-truncated + SUMmed to the day");

        var byUnit = controller
                .facets(ctx, new FacetsRequest("metric_rollups", "metric", range, Map.of("granularity", "hour"), null))
                .data()
                .facets();
        assertEquals(14, facetValue(byUnit, "ingested_spans"));
        assertEquals(1000, facetValue(byUnit, "l1_evals"));

        // Parity with MeteringController's timeseries.
        var metered = metering.projectTimeseries(pid, "ingested_spans", "hour", range.from(), range.to());
        assertEquals(
                metered.stream().mapToLong(b -> b.value()).sum(),
                hourly.stream().mapToLong(b -> b.count()).sum(),
                "Query API totals match the dedicated MeteringController timeseries");

        // Search on a pre-aggregated dataset is a 400.
        TessaryException ex = assertThrows(
                TessaryException.class,
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
