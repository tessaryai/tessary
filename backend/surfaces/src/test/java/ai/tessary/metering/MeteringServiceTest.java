// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.metering.LlmUsageQueryRepository.SubjectSpend;
import ai.tessary.metering.LlmUsageQueryRepository.UsageCell;
import ai.tessary.metering.LlmUsageQueryRepository.UsageSlice;
import ai.tessary.metering.MeteringDtos.LlmUsageCellView;
import ai.tessary.metering.MeteringDtos.LlmUsageSeriesView;
import ai.tessary.metering.MeteringDtos.LlmUsageSliceView;
import ai.tessary.metering.MeteringDtos.LlmUsageView;
import ai.tessary.metering.MeteringDtos.TriageSpendRowView;
import ai.tessary.metering.MeteringDtos.TriageSpendView;
import ai.tessary.open.errors.MeteringError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.usage.MetricRollupRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MeteringService} as the validation seam of the usage reads: every wire string (bound, grain,
 * grouping, bucket unit) is resolved and refused here, before any SQL, and the derived figures (the lane
 * labels, the cost of one triage ruling) are computed here. The repositories are mocked, fed canned slices;
 * the SQL behind them is {@code MeteringIntegrationTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class MeteringServiceTest {

    private static final String ORG = "org-1";

    @Mock
    MetricRollupRepository rollups;

    @Mock
    LlmUsageQueryRepository llmCalls;

    private MeteringService service() {
        return new MeteringService(rollups, llmCalls);
    }

    private static UsageSlice slice(@Nullable String key, @Nullable String label, long calls, String cost) {
        return new UsageSlice(
                key, label, calls, 10, 20, 30, 40, 100, new BigDecimal(cost), BigDecimal.ZERO, new BigDecimal(cost), 0);
    }

    /**
     * Every malformed series request is a 422 naming what was wrong, raised before any SQL: an unparseable
     * bound would otherwise reach Postgres as a failing cast (a 500), an inverted or empty window would read
     * as "no usage", and a window too fine for its grain would draw thousands of bars. 800 hours at the hour
     * grain is 801 buckets, one over the ceiling.
     */
    @ParameterizedTest(name = "[{0}, {1}) grain={2} group={3} -> {4}")
    @CsvSource({
        "yesterday, 2026-09-02T00:00:00Z, , , INVALID_RANGE",
        "2026-09-01T00:00:00Z, 2026-09-02, , , INVALID_RANGE",
        "2026-09-02T00:00:00Z, 2026-09-01T00:00:00Z, , , INVALID_RANGE",
        "2026-09-01T00:00:00Z, 2026-09-01T00:00:00Z, , , INVALID_RANGE",
        "2026-09-01T00:00:00Z, 2026-09-02T00:00:00Z, fortnight, , UNKNOWN_SERIES_GRAIN",
        "2026-09-01T00:00:00Z, 2026-09-02T00:00:00Z, day, team, UNKNOWN_GROUPING",
        "2026-01-01T00:00:00Z, 2026-02-03T08:00:00Z, hour, , WINDOW_TOO_FINE",
    })
    void aMalformedSeriesRequestIsRefusedBeforeAnySql(
            @Nullable String from,
            @Nullable String to,
            @Nullable String grain,
            @Nullable String group,
            MeteringError expected) {
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> service().orgLlmUsageSeries(ORG, from, to, grain, group, LlmUsageFilter.NONE));

        assertEquals(expected, e.error());
    }

    /**
     * The widest window the hour grain accepts (799 hours, exactly the 800-bucket ceiling) is served; the
     * wire strings resolve case-insensitively; a bound given with an offset is normalised to its UTC instant,
     * which is what the SQL windows on and what the response echoes.
     */
    @Test
    void aWindowAtTheBucketCeilingIsServedOnItsUtcBounds() {
        String start = "2026-01-01T00:00:00Z";
        String end = "2026-02-03T07:00:00Z";
        LlmUsageFilter filter = new LlmUsageFilter("triage", null, null);
        UsageCell cell = new UsageCell(start, "p1", "Proj One", 2, 10, 20, 30, 40, 100, new BigDecimal("0.0200000000"));
        when(llmCalls.orgTotal(ORG, start, end, filter)).thenReturn(slice(null, null, 2, "0.0200000000"));
        when(llmCalls.seriesAxis(start, end, LlmUsageGrain.HOUR)).thenReturn(List.of(start));
        when(llmCalls.series(ORG, start, end, LlmUsageGrain.HOUR, LlmUsageGrouping.PROJECT, filter))
                .thenReturn(List.of(cell));

        LlmUsageSeriesView view =
                service().orgLlmUsageSeries(ORG, "2026-01-01T05:30:00+05:30", end, "HOUR", "Project", filter);

        assertEquals(
                new LlmUsageSeriesView(
                        start,
                        end,
                        "hour",
                        "project",
                        view.asOf(),
                        LlmUsageSliceView.of(slice(null, null, 2, "0.0200000000"), null),
                        List.of(start),
                        List.of(LlmUsageCellView.of(cell))),
                view);
    }

    /**
     * With no bounds, grain or grouping the series is the page's default: the 30 days up to now, by day, as
     * one ungrouped series. An unbounded per-bucket read would be a full scan of the ledger.
     */
    @Test
    void anUnboundedSeriesRequestIsTheLastThirtyDaysByDayUngrouped() {
        when(llmCalls.orgTotal(eq(ORG), anyString(), anyString(), eq(LlmUsageFilter.NONE)))
                .thenReturn(slice(null, null, 0, "0"));

        LlmUsageSeriesView view = service().orgLlmUsageSeries(ORG, null, null, null, null, LlmUsageFilter.NONE);

        assertEquals(Duration.ofDays(30), Duration.between(Instant.parse(view.from()), Instant.parse(view.to())));
        assertEquals("day", view.grain());
        assertEquals("none", view.grouping());
    }

    /**
     * The breakdown labels a lane from {@link ModelLane}, and a lane with no {@code ModelLane} behind it (the
     * observer sandbox, or one written by a newer build) keeps its row with a null label rather than failing
     * the whole usage page. The org total's null key goes out as the empty string.
     */
    @Test
    void theBreakdownLabelsKnownLanesAndKeepsAnUnknownLaneUnlabelled() {
        when(llmCalls.orgTotal(ORG, null, null, LlmUsageFilter.NONE)).thenReturn(slice(null, null, 4, "0.4"));
        when(llmCalls.byLane(ORG, null, null))
                .thenReturn(List.of(slice("triage", null, 3, "0.3"), slice("observer", null, 1, "0.1")));
        when(llmCalls.byProject(ORG, null, null)).thenReturn(List.of(slice("p1", "Proj One", 4, "0.4")));
        when(llmCalls.byModel(ORG, null, null)).thenReturn(List.of(slice("", null, 4, "0.4")));

        LlmUsageView view = service().orgLlmUsage(ORG, null, null);

        assertEquals(
                new LlmUsageView(
                        null,
                        null,
                        view.asOf(),
                        LlmUsageSliceView.of(slice("", null, 4, "0.4"), null),
                        List.of(
                                LlmUsageSliceView.of(slice("triage", null, 3, "0.3"), ModelLane.TRIAGE.label()),
                                LlmUsageSliceView.of(slice("observer", null, 1, "0.1"), null)),
                        List.of(LlmUsageSliceView.of(slice("p1", "Proj One", 4, "0.4"), "Proj One")),
                        List.of(LlmUsageSliceView.of(slice("", null, 4, "0.4"), null))),
                view);
    }

    /**
     * The cost of one ruling is the lane's cost over its run count, to six places rounded half-up; with no runs,
     * or nothing priced, it is absent rather than a misleading zero. {@code 2/3} rounds up to {@code 0.666667},
     * which a truncating division would get wrong in the last place.
     */
    @ParameterizedTest(name = "{0} runs costing {1} -> {2}")
    @CsvSource({
        "0, 0, ",
        "3, 0, ",
        "2, 0.30, 0.150000",
        "3, 2, 0.666667",
    })
    void theCostOfOneRulingIsTheLaneCostOverItsRuns(long runs, String cost, @Nullable String perRuling) {
        LlmUsageFilter triageLane = new LlmUsageFilter("triage", null, null);
        SubjectSpend row = new SubjectSpend("finding-1", 2, 500, new BigDecimal(cost), 1, "2026-09-01T10:00:00Z");
        when(llmCalls.orgTotal(ORG, null, null, triageLane)).thenReturn(slice(null, null, runs, cost));
        when(llmCalls.bySubject(ORG, null, null, "behavior_finding", 5)).thenReturn(List.of(row));

        TriageSpendView view = service().orgTriageSpend(ORG, null, null, 5);

        assertEquals(
                new TriageSpendView(
                        null,
                        null,
                        view.asOf(),
                        runs,
                        new BigDecimal(cost),
                        perRuling == null ? null : new BigDecimal(perRuling),
                        0,
                        List.of(new TriageSpendRowView(
                                "finding-1", 2, 500, new BigDecimal(cost), 1, "2026-09-01T10:00:00Z"))),
                view);
    }

    /**
     * The project timeseries needs both bounds (an unbounded timeseries is a full scan) and one of the two
     * bucket grains the rollup table holds; anything else is a 422 before the rollup is read.
     */
    @ParameterizedTest(name = "[{0}, {1}) granularity={2} -> {3}")
    @CsvSource({
        ", 2026-09-02T00:00:00Z, hour, INVALID_RANGE",
        "2026-09-01T00:00:00Z, , hour, INVALID_RANGE",
        "2026-09-01T00:00:00Z, 2026-09-02T00:00:00Z, week, UNKNOWN_BUCKET_UNIT",
    })
    void aMalformedProjectTimeseriesIsRefusedBeforeTheRollupIsRead(
            @Nullable String from, @Nullable String to, String granularity, MeteringError expected) {
        TessaryException e = assertThrows(
                TessaryException.class, () -> service().projectTimeseries("p1", "l1_evals", granularity, from, to));

        assertEquals(expected, e.error());
    }
}
