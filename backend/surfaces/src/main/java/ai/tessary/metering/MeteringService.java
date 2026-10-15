// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.metering.LlmUsageQueryRepository.SubjectSpend;
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
import ai.tessary.usage.MetricRollupRepository.UsageBucket;
import ai.tessary.usage.MetricRollupRepository.UsageTotal;
import ai.tessary.usage.UsageUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The single validation seam: wire units are resolved before any SQL, and scope ids come from the authenticated
 * context, never the body.
 */
@Service
public class MeteringService {

    /** Every window/grain pair the page offers clears it; an ad-hoc 90-days-of-hours request is refused. */
    private static final int MAX_SERIES_BUCKETS = 800;

    private static final Duration DEFAULT_SERIES_WINDOW = Duration.ofDays(30);

    private static final String TRIAGE_LANE = ModelLane.TRIAGE.wire();

    /** A persisted string shared with {@code E2bTriageSandbox.SUBJECT_KIND}; never rename either. */
    private static final String TRIAGE_SUBJECT_KIND = "behavior_finding";

    private final MetricRollupRepository rollups;
    private final LlmUsageQueryRepository llmCalls;

    public MeteringService(MetricRollupRepository rollups, LlmUsageQueryRepository llmCalls) {
        this.rollups = rollups;
        this.llmCalls = llmCalls;
    }

    /** Both bounds are required: an unbounded timeseries is a full scan. */
    public List<UsageBucket> projectTimeseries(
            String projectId,
            String unitWire,
            @Nullable String granularityWire,
            @Nullable String from,
            @Nullable String to) {
        if (from == null || to == null) {
            throw new TessaryException(MeteringError.INVALID_RANGE);
        }
        UsageUnit unit = UsageUnit.fromWire(unitWire);
        String granularity = granularityWire == null ? MeteringWorker.BUCKET_HOUR : granularityWire;
        if (!UsageUnit.isBucketUnit(granularity)) {
            throw new TessaryException(MeteringError.UNKNOWN_BUCKET_UNIT, granularity);
        }
        return rollups.projectTimeseries(projectId, unit.wire(), granularity, from, to);
    }

    /**
     * Totalled at the hour grain: day rows re-aggregate the same rows, so a grain-agnostic total doubles. Hour also
     * covers today.
     */
    public List<UsageTotal> orgTotals(String orgId, @Nullable String from, @Nullable String to) {
        return rollups.orgTotals(orgId, UsageUnit.BUCKET_HOUR, from, to);
    }

    /** Read live off the per-call ledger, not {@code metric_rollup}, which has only a single token scalar. */
    public LlmUsageView orgLlmUsage(String orgId, @Nullable String from, @Nullable String to) {
        return new LlmUsageView(
                from,
                to,
                Instant.now().toString(),
                LlmUsageSliceView.of(llmCalls.orgTotal(orgId, from, to, LlmUsageFilter.NONE), null),
                llmCalls.byLane(orgId, from, to).stream()
                        .map(s -> LlmUsageSliceView.of(s, laneLabel(s.key())))
                        .toList(),
                llmCalls.byProject(orgId, from, to).stream()
                        .map(s -> LlmUsageSliceView.of(s, s.label()))
                        .toList(),
                llmCalls.byModel(orgId, from, to).stream()
                        .map(s -> LlmUsageSliceView.of(s, null))
                        .toList());
    }

    /**
     * @param limit the aggregates cover the whole window regardless; a truncated list must not produce a truncated
     * total
     */
    public TriageSpendView orgTriageSpend(String orgId, @Nullable String from, @Nullable String to, int limit) {
        UsageSlice lane = llmCalls.orgTotal(orgId, from, to, new LlmUsageFilter(TRIAGE_LANE, null, null));
        List<SubjectSpend> rows = llmCalls.bySubject(orgId, from, to, TRIAGE_SUBJECT_KIND, limit);
        // A re-triaged finding books twice, so the honest denominator is the run count.
        long runs = lane.calls();
        BigDecimal perRuling = runs == 0 || lane.costUsd().signum() == 0
                ? null
                : lane.costUsd().divide(BigDecimal.valueOf(runs), 6, RoundingMode.HALF_UP);
        return new TriageSpendView(
                from,
                to,
                Instant.now().toString(),
                runs,
                lane.costUsd(),
                perRuling,
                lane.unpricedCalls(),
                rows.stream()
                        .map(s -> new TriageSpendRowView(
                                s.subjectId(), s.runs(), s.totalTokens(), s.costUsd(), s.unpricedRuns(), s.lastAt()))
                        .toList());
    }

    /**
     * Both bounds default rather than staying open, since an unbounded read is a full scan. Invalid input and more than
     * {@link #MAX_SERIES_BUCKETS} bars are 422s.
     */
    public LlmUsageSeriesView orgLlmUsageSeries(
            String orgId,
            @Nullable String from,
            @Nullable String to,
            @Nullable String grainWire,
            @Nullable String groupingWire,
            LlmUsageFilter filter) {
        Instant end = to == null ? Instant.now() : parseBound(to);
        Instant start = from == null ? end.minus(DEFAULT_SERIES_WINDOW) : parseBound(from);
        if (!start.isBefore(end)) {
            throw new TessaryException(MeteringError.INVALID_RANGE);
        }
        LlmUsageGrain grain = LlmUsageGrain.fromWire(grainWire == null ? LlmUsageGrain.DAY.wire() : grainWire);
        LlmUsageGrouping grouping =
                LlmUsageGrouping.fromWire(groupingWire == null ? LlmUsageGrouping.NONE.wire() : groupingWire);
        long buckets = Duration.between(start, end).dividedBy(grain.width()) + 1;
        if (buckets > MAX_SERIES_BUCKETS) {
            throw new TessaryException(MeteringError.WINDOW_TOO_FINE, grain.wire(), buckets, MAX_SERIES_BUCKETS);
        }

        String startWire = start.toString();
        String endWire = end.toString();
        return new LlmUsageSeriesView(
                startWire,
                endWire,
                grain.wire(),
                grouping.wire(),
                Instant.now().toString(),
                LlmUsageSliceView.of(llmCalls.orgTotal(orgId, startWire, endWire, filter), null),
                llmCalls.seriesAxis(startWire, endWire, grain),
                llmCalls.series(orgId, startWire, endWire, grain, grouping, filter).stream()
                        .map(LlmUsageCellView::of)
                        .toList());
    }

    /** Rejected as a 422 rather than reaching Postgres as a cast that throws. */
    private static Instant parseBound(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException notIso) {
            throw new TessaryException(MeteringError.INVALID_RANGE, notIso);
        }
    }

    /** A lane with no {@code ModelLane} behind it falls back to its wire value rather than being dropped. */
    private static @Nullable String laneLabel(@Nullable String wire) {
        try {
            return ModelLane.fromWire(wire).label();
        } catch (TessaryException unknownLane) {
            return null;
        }
    }
}
