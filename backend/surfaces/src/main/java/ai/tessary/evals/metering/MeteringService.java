// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.metering;

import ai.tessary.evals.llmspi.ModelLane;
import ai.tessary.evals.metering.LlmUsageQueryRepository.SubjectSpend;
import ai.tessary.evals.metering.LlmUsageQueryRepository.UsageSlice;
import ai.tessary.evals.metering.MeteringDtos.LlmUsageCellView;
import ai.tessary.evals.metering.MeteringDtos.LlmUsageSeriesView;
import ai.tessary.evals.metering.MeteringDtos.LlmUsageSliceView;
import ai.tessary.evals.metering.MeteringDtos.LlmUsageView;
import ai.tessary.evals.metering.MeteringDtos.TriageSpendRowView;
import ai.tessary.evals.metering.MeteringDtos.TriageSpendView;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.MeteringError;
import ai.tessary.evals.usage.MetricRollupRepository;
import ai.tessary.evals.usage.MetricRollupRepository.UsageBucket;
import ai.tessary.evals.usage.MetricRollupRepository.UsageTotal;
import ai.tessary.evals.usage.UsageUnit;
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
 * Read orchestration for usage metering — the single validation seam over
 * {@link MetricRollupRepository}, mirroring {@code QueryService} over {@code QueryRepository}. Controllers
 * resolve the opaque wire {@code unit} string against {@link UsageUnit} here (a {@code 422} on an unknown
 * unit before any SQL), and the {@code projectId} / {@code orgId} a controller passes always comes from
 * the authenticated scope, never the request body. Both read surfaces — the project-scoped timeseries and
 * the org-scoped totals — funnel through this one repository so the aggregation logic never forks.
 */
@Service
public class MeteringService {

    /**
     * The ceiling on how many bars one series read may produce. Sized so every window/grain pair the
     * usage page offers clears it comfortably (90 days of days is 90, 7 days of hours is 168) while an
     * ad-hoc 90-days-of-hours request is refused with an actionable message instead of served.
     */
    private static final int MAX_SERIES_BUCKETS = 800;

    /** The window a series read covers when the caller gives no {@code from} — the page's own default. */
    private static final Duration DEFAULT_SERIES_WINDOW = Duration.ofDays(30);

    /** The lane every Layer-2 ruling books against. */
    private static final String TRIAGE_LANE = ModelLane.TRIAGE.wire();

    /**
     * The ledger subject kind a ruling books against — the {@code behavior_finding} table. Mirrors
     * {@code E2bTriageSandbox.SUBJECT_KIND}; the two live in different modules, and the value is a
     * PERSISTED string in {@code llm_call.subject_kind}, so neither may be renamed.
     */
    private static final String TRIAGE_SUBJECT_KIND = "behavior_finding";

    private final MetricRollupRepository rollups;
    private final LlmUsageQueryRepository llmCalls;

    public MeteringService(MetricRollupRepository rollups, LlmUsageQueryRepository llmCalls) {
        this.rollups = rollups;
        this.llmCalls = llmCalls;
    }

    /**
     * The project-scoped per-bucket usage timeseries for one unit over {@code [from, to)} (both required —
     * an unbounded timeseries is a full scan), at the requested bucket grain. {@code unitWire} is validated
     * against {@link UsageUnit} and {@code granularityWire} against {@link UsageUnit#isBucketUnit} ({@code
     * hour} or {@code day}); a null grain defaults to {@code hour}. All wire strings are resolved here,
     * before any SQL.
     */
    public List<UsageBucket> projectTimeseries(
            String projectId,
            String unitWire,
            @Nullable String granularityWire,
            @Nullable String from,
            @Nullable String to) {
        if (from == null || to == null) {
            throw new EvalsException(MeteringError.INVALID_RANGE);
        }
        UsageUnit unit = UsageUnit.fromWire(unitWire);
        String granularity = granularityWire == null ? MeteringWorker.BUCKET_HOUR : granularityWire;
        if (!UsageUnit.isBucketUnit(granularity)) {
            throw new EvalsException(MeteringError.UNKNOWN_BUCKET_UNIT, granularity);
        }
        return rollups.projectTimeseries(projectId, unit.wire(), granularity, from, to);
    }

    /**
     * The org-scoped per-unit totals over {@code [from, to)} (either bound may be open) — the cross-project
     * read billing bills from. Org-level reads never go through the project-scoped Query API.
     *
     * <p>Totalled at the HOUR grain, which is not a detail: the worker's {@code day} rows re-aggregate the
     * same producer rows as the hours beside them, so a grain-agnostic total billed roughly double. Hour is
     * also the only grain that covers the day in progress.
     */
    public List<UsageTotal> orgTotals(String orgId, @Nullable String from, @Nullable String to) {
        return rollups.orgTotals(orgId, UsageUnit.BUCKET_HOUR, from, to);
    }

    /**
     * The org's platform LLM consumption over {@code [from, to)} (either bound may be open), read LIVE
     * off the per-call ledger: one total, plus the same total cut by product lane, by project and by
     * model, with the four token buckets kept apart.
     *
     * <p>Deliberately not the {@code metric_rollup} path {@link #orgTotals} reads. That one meters
     * closed buckets of a single {@code llm_tokens} scalar — right for an invoice, useless for "which
     * part of the product is burning this, and is it cache or output". Both remain: the rollup is the
     * billing basis, this is the breakdown behind it.
     */
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
     * The org's triage spend broken down per ruling — launch H2's "attributable per triage".
     *
     * <p>The lane breakdown in {@link #orgLlmUsage} says how much the triage agent cost; this says across
     * how many rulings, and therefore what one ruling costs. That unit price is the number H3 asks about:
     * after decision D14 a ruling is a microVM rather than a chat call, so whether the filter is worth its
     * price is an empirical question, and this is where the answer is read.
     *
     * @param limit how many rulings to list, costliest first. The aggregate figures above the list cover
     *     the WHOLE window regardless — a truncated list must not produce a truncated total.
     */
    public TriageSpendView orgTriageSpend(String orgId, @Nullable String from, @Nullable String to, int limit) {
        UsageSlice lane = llmCalls.orgTotal(orgId, from, to, new LlmUsageFilter(TRIAGE_LANE, null, null));
        List<SubjectSpend> rows = llmCalls.bySubject(orgId, from, to, TRIAGE_SUBJECT_KIND, limit);
        // Rulings, not calls: one ledger row IS one ruling on this lane (the sandbox books the run as a
        // whole), but a re-triaged finding books twice, so the honest denominator is the run count.
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
     * The same live ledger as {@link #orgLlmUsage}, bucketed for the usage chart: one bar per {@code
     * grain} bucket over {@code [from, to)}, split into series by {@code grouping}, optionally narrowed
     * to one lane / project / model.
     *
     * <p>Both bounds default rather than staying open — an unbounded per-bucket read of the whole
     * ledger is a full scan whose bucket count nobody can predict. {@code to} defaults to now and
     * {@code from} to {@link #DEFAULT_SERIES_WINDOW} before it. Every wire string is resolved here,
     * before any SQL: an unparseable bound, an inverted range, an unknown grain or grouping, and a
     * combination fine enough to produce more than {@link #MAX_SERIES_BUCKETS} bars are all 422s rather
     * than a database error or a chart with ten thousand bars in it.
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
            throw new EvalsException(MeteringError.INVALID_RANGE);
        }
        LlmUsageGrain grain = LlmUsageGrain.fromWire(grainWire == null ? LlmUsageGrain.DAY.wire() : grainWire);
        LlmUsageGrouping grouping =
                LlmUsageGrouping.fromWire(groupingWire == null ? LlmUsageGrouping.NONE.wire() : groupingWire);
        long buckets = Duration.between(start, end).dividedBy(grain.width()) + 1;
        if (buckets > MAX_SERIES_BUCKETS) {
            throw new EvalsException(MeteringError.WINDOW_TOO_FINE, grain.wire(), buckets, MAX_SERIES_BUCKETS);
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

    /** An ISO-8601 instant bound, rejected as a 422 rather than reaching Postgres as a cast that throws. */
    private static Instant parseBound(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException notIso) {
            throw new EvalsException(MeteringError.INVALID_RANGE, notIso);
        }
    }

    /**
     * The human label for a ledger lane. Resolved from {@link ModelLane} so the usage page and the
     * per-lane model settings always name a lane the same way; a lane with no {@code ModelLane} behind
     * it (the observer sandbox) or one written by a newer build falls back to its wire value, which the
     * client titleizes rather than dropping the row.
     */
    private static @Nullable String laneLabel(@Nullable String wire) {
        if (wire == null) return null;
        try {
            return ModelLane.fromWire(wire).label();
        } catch (EvalsException unknownLane) {
            return null;
        }
    }
}
