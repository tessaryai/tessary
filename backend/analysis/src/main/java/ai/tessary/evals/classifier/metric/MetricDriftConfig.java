// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

import ai.tessary.evals.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.evals.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.evals.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The metric-drift operating point, parsed from the classifier's {@code config_json}. Design contract:
 * {@code classifiers/metric_drift/PROGRAM.md}, defaults enumerated in {@code PLAN.md} §4.
 *
 * <p><b>The arithmetic these numbers feed, and the evidence behind them, is
 * {@code docs/concepts/deviation-math.md}.</b> Changing a default here without updating that document
 * leaves the only human-readable account of the operating point describing a detector that no longer
 * exists — the same-PR co-update rule in the root {@code AGENTS.md} covers it.
 *
 * <p>Shaped after {@code BehaviorDriftConfig} — almost every number here is a <em>policy</em> rather
 * than a threshold — and after {@code trend/CusumParams} (long deleted, kept here as the
 * shape this was copied from) in one specific respect: every
 * field is clamped in the compact constructor. That is not defensive habit. These blobs are editable
 * live, per project, while the classifier is being tuned, and an unclamped {@code w1_floor} of 0 turns
 * the detector into a firehose on the next sweep with nothing in the code path to notice.
 *
 * <p><b>EXPERIMENT(metric-drift-tuning):</b> every default below is provisional and every one of them is
 * expected to move. PLAN.md §9 runs the null case — real traffic split in half, unmodified, everything
 * that fires is a false positive — and writes the measured operating point back. Until that lands, both
 * metric-drift modules seed <em>disabled</em>, which is the honest posture for numbers nobody has
 * measured. When the operating point is finalized, this record stays and the per-project blob parsing
 * ({@link #of}) is what goes away.
 *
 * <p><b>The measure list is the classifier.</b> One switch governs several measures (PROGRAM.md §3.1) —
 * {@code duration_drift} is turn plus tool, {@code cost_drift} is cost plus its token evidence — so
 * which measures are live is config, not a catalog fact. {@code BuiltInClassifierCatalog.grainFor()}
 * returns ONE grain per detector kind and therefore cannot answer "at what grain does this classifier
 * draw candidates"; each measure carries its own grain here, in {@link Measured}, and the sweep reads
 * it from this record. See {@link Grain#WINDOW} for why the catalog-level answer is a window rather than
 * a fudge.
 *
 * @param measures the persisted measure names this signal is live for, deduped and filtered to the ones
 *     {@link Measured} knows. An unknown name is dropped rather than rejected: a config blob written by
 *     a newer build must not dead-letter the sweep of an older one.
 */
public record MetricDriftConfig(
        List<String> measures,
        int windowTargetCount,
        int windowMaxHours,
        int minSample,
        double w1Floor,
        double explainedByFraction,
        int settleSeconds,
        int histBins) {

    // -----------------------------------------------------------------------------------------------
    // Defaults — PLAN.md §4, every one EXPERIMENT(metric-drift-tuning)
    // -----------------------------------------------------------------------------------------------

    /**
     * EXPERIMENT(metric-drift-tuning): samples that close a window on the thick path. 500 is enough that
     * a p95 read off the window is not luck, and small enough that a busy call site closes several
     * windows a day rather than reporting a regression a week late.
     */
    public static final int DEFAULT_WINDOW_TARGET_COUNT = 500;

    /**
     * EXPERIMENT(metric-drift-tuning): the elapsed-EVENT-time close for a bucket too thin to reach the
     * count. <b>One day.</b>
     *
     * <p>It was a week, on the reasoning that a thin bucket needs a week to accumulate anything worth
     * comparing. That reasoning was half right and had the wrong remedy: such a bucket cannot support the
     * same effect size a thick one can, but making it WAIT does not fix that — it just means a tool that
     * broke on Monday is reported on Sunday, if the window even clears
     * {@link MetricDriftDetector#effectiveFloor} when it finally closes.
     *
     * <p>Scaling the bar to the sample size is what actually fixes it, so the clock can now be short. A
     * thin bucket closes daily and is judged against a bar it can support: at a hundred samples against a
     * five-hundred reference that is a 27% move, held to the same 1% false-alarm rate a full window gets.
     * Small moves on thin traffic are still invisible — no schedule can change that — but large ones
     * surface in a day instead of a week.
     */
    public static final int DEFAULT_WINDOW_MAX_HOURS = 24;

    /**
     * EXPERIMENT(metric-drift-tuning): the floor below which nothing is compared at all. 100 samples put
     * roughly 5 in the top 5%, which is the least a p95 can be built from without the tail being one
     * trace's opinion.
     *
     * <p>Lowered from 150 alongside the 24-hour close, and safe only because of it: with a flat bar a
     * hundred-sample window false-alarms 20% of the time, and it is {@link
     * MetricDriftDetector#effectiveFloor} that brings that back to 1% by asking such a window for a
     * bigger move. This constant is also what bounds that scaling — with both windows at or above it the
     * bar cannot exceed {@code w1Floor · sqrt(windowTargetCount / minSample)}.
     *
     * <p>This is a <em>wait</em>, not a skip. A bucket under the floor keeps its window open past
     * {@link #windowMaxHours} rather than closing an uncomparable one, so a rare tool eventually gets
     * watched instead of silently never being watched — the posture behaviour drift's
     * {@code min_support} / {@code graduation_sessions} pair already takes.
     */
    public static final int DEFAULT_MIN_SAMPLE = 100;

    /**
     * EXPERIMENT(metric-drift-tuning): the smallest move worth reporting, as a signed-W₁ magnitude in log
     * units, at a FULL window. {@code e^0.139 ≈ 1.15}, so "tell me about 15% moves".
     *
     * <p><b>The dial is the move, deliberately, and not a false-alarm rate.</b> The two are
     * interchangeable arithmetic — one implies the other given how spread out a bucket's traffic is — but
     * they are not interchangeable promises. "We tell you about 15% moves" is something an operator can
     * hold in their head and predict; "we tell you at a 1% false-alarm rate" is not, and it makes the
     * reportable move differ per call site, so two people looking at two buckets get different answers to
     * "why didn't this fire". {@link MetricDriftDetector#impliedFalseAlarmRate} exists so the consequence
     * is still visible where the dial is set.
     *
     * <p><b>Where 0.139 came from.</b> The 99th percentile of a synthetic null run — two windows of 500
     * drawn from one lognormal, twenty thousand repetitions, this exact statistic on this exact grid — for
     * a wide-spread call site whose p95 is ≈3.7× its median, which is typical of agent work. So on that
     * traffic it is the 1% bar. On tighter traffic the same number is a stricter one, and that variation
     * is the price of the move being the promise.
     *
     * <pre>
     *   false alarms   move   catches a 1.2x move
     *       0.1%       20%            52%
     *         1%       15%            83%
     *         5%       12%            94%
     *        10%       10%            97%
     * </pre>
     *
     * <p>Above 1% the curve turns sharply upward while little sensitivity remains to win; below it, 1.2×
     * moves start getting away.
     *
     * <p><b>Still synthetic.</b> PLAN.md §9's null case against real traffic is what settles it, and a real
     * corpus can move it either way.
     */
    public static final double DEFAULT_W1_FLOOR = 0.139;

    /**
     * EXPERIMENT(metric-drift-tuning): how much of a turn-duration shift a tool-duration shift inside the
     * same call site must account for, in absolute time, before the turn finding is suppressed in its
     * favour ({@link MetricSuppression}, PROGRAM.md §6.1). Half.
     *
     * <p><b>Deliberately generous, on two arguments.</b> A tool bucket's Δ is measured per CALL and a
     * turn's per TURN, and a turn usually makes more than one call to whichever tool dominates it — so a
     * single call's Δ systematically understates its own contribution, and a bar near 1.0 would mean the
     * rule almost never fires. And suppression is cheap: the suppressed turn shift is attached to the
     * tool finding as evidence either way, so the only thing the bar decides is which of the two is the
     * headline, and the tool row is the one that names a fix.
     *
     * <p>What it must not do is swallow the case turn duration exists for — <em>eleven tool calls where
     * three used to do</em>, where every call is as fast as it ever was and only the count moved. That
     * case leaves every tool bucket flat, contributing a Δ of about zero, so it clears this bar by a wide
     * margin rather than by a hair. PLAN.md §11 names the opposite failure (an injected span-count
     * increase swallowed by a coincident tool shift) and its response is to raise this number.
     */
    public static final double DEFAULT_EXPLAINED_BY_FRACTION = 0.5;

    /**
     * EXPERIMENT(metric-drift-tuning): how long a trace is given for its spans to arrive before a
     * SUMMING measure reads it. Five minutes, matching {@code BehaviorDriftConfig}'s
     * {@code trace_settle_seconds}, since both are waiting on the same exporter.
     *
     * <p>Applied as a BLANKET horizon only by measures that {@link Measured#settles()}. It is
     * deliberately not applied that way to duration — see that method for why a fixed delay there costs
     * every finding its latency and buys nothing.
     *
     * <p>It has a second, narrower job that every measure shares: it caps how long
     * {@code MetricDriftSweep.admissibleThrough} will hold a page open for a trace whose ROOT SPAN has
     * not landed. That is a conditional wait, not a settle — a trace whose root is already there is
     * swept immediately — and the cap is what stops a trace whose root will never arrive from stalling a
     * forward-only cursor for good.
     */
    public static final int DEFAULT_SETTLE_SECONDS = 300;

    /**
     * EXPERIMENT(metric-drift-tuning): bins per sketch grid, {@link MetricHistogram#DEFAULT_BINS}.
     *
     * <p>The one dial here with a persistence consequence: the bin count is part of a sketch's
     * {@code gridId}, and sketches already written outlive an edit to it. Changing this therefore
     * orphans every pinned and previous window in the project. That is survivable rather than fatal —
     * {@link MetricDriftDetector} treats a grid mismatch as a silent pass and the next close re-pins —
     * but it is a real cost, so the clamp range below is deliberately narrow.
     */
    public static final int DEFAULT_HIST_BINS = MetricHistogram.DEFAULT_BINS;

    // -----------------------------------------------------------------------------------------------
    // Clamps — a live-edited blob must not be able to drive the detector into nonsense
    // -----------------------------------------------------------------------------------------------

    private static final int WINDOW_TARGET_COUNT_MIN = 50;
    private static final int WINDOW_TARGET_COUNT_MAX = 100_000;
    // A window that can never close on time never closes at all on a thin bucket; a horizon past a
    // quarter compares against a reference nobody remembers deploying.
    private static final int WINDOW_MAX_HOURS_MIN = 1;
    private static final int WINDOW_MAX_HOURS_MAX = 2_160;
    private static final int MIN_SAMPLE_MIN = 30;
    // 0.01 in log units is a ~1% shift. The eval's own silent case is 1.02x, so a floor under this would
    // fire on traffic the program has already decided is quiet; 3.0 is a 20x move, past which nothing
    // would ever be reported.
    private static final double W1_FLOOR_MIN = 0.01;
    private static final double W1_FLOOR_MAX = 3.0;
    // Below 0.1 any tool wobble inside a slow call site would claim the turn's shift, and the "eleven
    // tool calls where three used to do" case is the one that goes quiet first. Above 1.0 nothing could
    // ever cover the whole of a turn's delta measured per call, so the rule would switch itself off —
    // invisibly, which is the failure every clamp in this record guards against rather than the loud one.
    private static final double EXPLAINED_BY_FRACTION_MIN = 0.1;
    private static final double EXPLAINED_BY_FRACTION_MAX = 1.0;
    private static final int SETTLE_SECONDS_MIN = 0;
    private static final int SETTLE_SECONDS_MAX = 3_600;
    private static final int HIST_BINS_MIN = 64;
    private static final int HIST_BINS_MAX = 1_024;
    private static final MetricDriftConfig DEFAULTS = new MetricDriftConfig(
            // Both duration measures, because they are two halves of one question and therefore one
            // switch (PROGRAM.md §3.1). Shipping tool_duration WITHOUT turn_duration would be the
            // supportable half-configuration; shipping turn without tool is the one that reports a
            // symptom it could have named the cause of.
            List.of(Measure.TURN_DURATION, Measure.TOOL_DURATION),
            DEFAULT_WINDOW_TARGET_COUNT,
            DEFAULT_WINDOW_MAX_HOURS,
            DEFAULT_MIN_SAMPLE,
            DEFAULT_W1_FLOOR,
            DEFAULT_EXPLAINED_BY_FRACTION,
            DEFAULT_SETTLE_SECONDS,
            DEFAULT_HIST_BINS);

    public MetricDriftConfig {
        measures = knownMeasures(measures);
        windowTargetCount = (int) clamp(windowTargetCount, WINDOW_TARGET_COUNT_MIN, WINDOW_TARGET_COUNT_MAX);
        windowMaxHours = (int) clamp(windowMaxHours, WINDOW_MAX_HOURS_MIN, WINDOW_MAX_HOURS_MAX);
        // min_sample above window_target_count is not a stricter setting, it is a broken one: every
        // count-closed window would arrive under the comparison floor and no bucket would ever be
        // compared, silently and forever. Same shape as BehaviorDriftConfig clamping session_sample_cap
        // above the graduation bar, and for the same reason — the failure is invisible, not loud.
        minSample = (int) clamp(minSample, MIN_SAMPLE_MIN, windowTargetCount);
        w1Floor = clamp(w1Floor, W1_FLOOR_MIN, W1_FLOOR_MAX);
        explainedByFraction = clamp(explainedByFraction, EXPLAINED_BY_FRACTION_MIN, EXPLAINED_BY_FRACTION_MAX);
        settleSeconds = (int) clamp(settleSeconds, SETTLE_SECONDS_MIN, SETTLE_SECONDS_MAX);
        histBins = (int) clamp(histBins, HIST_BINS_MIN, HIST_BINS_MAX);
    }

    /** The baked defaults, used when a project's signal carries no config. */
    public static MetricDriftConfig defaults() {
        return DEFAULTS;
    }

    /** Parse the classifier's {@code config_json}, falling back per-field to {@link #defaults()}. */
    public static MetricDriftConfig of(ObjectMapper mapper, @Nullable String configJson) {
        if (configJson == null || configJson.isBlank()) return DEFAULTS;
        JsonNode node;
        try {
            node = mapper.readTree(configJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return DEFAULTS;
        }
        return new MetricDriftConfig(
                measureNames(node, DEFAULTS.measures),
                i(node, "window_target_count", DEFAULTS.windowTargetCount),
                i(node, "window_max_hours", DEFAULTS.windowMaxHours),
                i(node, "min_sample", DEFAULTS.minSample),
                d(node, "w1_floor", DEFAULTS.w1Floor),
                d(node, "explained_by_fraction", DEFAULTS.explainedByFraction),
                i(node, "settle_seconds", DEFAULTS.settleSeconds),
                i(node, "hist_bins", DEFAULTS.histBins));
    }

    /**
     * The counterpart of {@link #of}, in the same class for the reason {@code MetricFindingEvidence}
     * gives for keeping its writer and reader together: a persisted shape whose two ends drift apart is a
     * bug nothing type-checks. Every field round-trips through the same snake_case names {@link #of}
     * reads, so a config edited via {@code ClassifierService#setTuning} and re-parsed on the next sweep
     * reads back exactly what this wrote — including the fields the edit didn't touch, since the caller is
     * expected to build this instance from a full parse ({@link #of}) with only the tuned fields replaced.
     */
    public String toJson(ObjectMapper mapper) {
        var root = mapper.createObjectNode();
        var measuresArray = root.putArray("measures");
        for (String measure : measures) measuresArray.add(measure);
        root.put("window_target_count", windowTargetCount);
        root.put("window_max_hours", windowMaxHours);
        root.put("min_sample", minSample);
        root.put("w1_floor", w1Floor);
        root.put("explained_by_fraction", explainedByFraction);
        root.put("settle_seconds", settleSeconds);
        root.put("hist_bins", histBins);
        return root.toString();
    }

    // -----------------------------------------------------------------------------------------------
    // Measures
    // -----------------------------------------------------------------------------------------------

    /**
     * One measure's fixed properties: what grain its candidates are drawn at, whether it has to wait for
     * a trace to settle, and which bucket kind and sketch grid it lives on.
     *
     * <p>These are facts about the MEASURE, not knobs — a config blob can turn a measure on or off, and
     * cannot claim that cost is read off a span or that duration needs a settle horizon.
     *
     * <p>Only measures that can OPEN a finding appear here. The four token buckets deliberately do not:
     * PROGRAM.md §6.1 makes them evidence attached to the cost finding and never findings of their own,
     * so a prompt edit that kills caching writes one row rather than five. They are computed by
     * {@link ai.tessary.evals.classifier.metric.MetricSource} every window regardless.
     *
     * @param settles whether this measure must wait out {@link #settleSeconds} before reading a trace.
     *     <b>It is not uniform, and the split is load-bearing</b> (PROGRAM.md §5). Cost and the token
     *     buckets SUM over a trace's spans, so they need every span to have arrived — measuring early
     *     reads as cheap, which surfaces as a permanent drift toward cheaper whenever ingest lags.
     *     Duration is read off a single span that carries its own start and end, so that span's ARRIVAL
     *     is the completion signal; a settle horizon there buys nothing and delays every duration finding
     *     by the length of the horizon.
     */
    public record Measured(String measure, Grain grain, boolean settles, String bucketKind, Grid grid) {}

    /**
     * How long a trace must have existed before THIS signal may read it: the maximum over the measures it
     * is live for, because one signal has one job row and therefore one keyset cursor.
     *
     * <p>Taking the maximum rather than a per-measure horizon is what keeps the two settle paths honest
     * under a shared cursor. A cursor advanced on a duration measure's zero-second horizon walks past
     * traces whose spans have not landed, and a cost measure on the same cursor could never go back for
     * them — the forward-only-cursor hazard PROGRAM.md §3.3 names. In practice each classifier's measures
     * agree: {@code duration_drift} is all duration and settles for zero seconds, {@code cost_drift} is
     * all summing and settles for the full horizon.
     */
    public int settleSecondsFor(List<Measured> live) {
        int settle = 0;
        for (Measured m : live) {
            if (m.settles()) settle = Math.max(settle, settleSeconds);
        }
        return settle;
    }

    /** The specs for the measures this signal is live for, in the registry's order. */
    public List<Measured> measured() {
        List<Measured> out = new ArrayList<>(measures.size());
        for (String measure : measures) {
            Measured spec = specs().get(measure);
            if (spec != null) out.add(withBins(spec));
        }
        return List.copyOf(out);
    }

    /** The specs for the live measures drawn at one grain — the sweep's per-grain fan-out. */
    public List<Measured> measuredAt(Grain grain) {
        return measured().stream().filter(m -> m.grain() == grain).toList();
    }

    /**
     * The registry. Keyed by the PERSISTED measure name, which is also
     * {@code metric_baseline.measure} — wire and Java say <em>classifier</em>, the database and its
     * persisted strings stay <em>signal</em>, and neither is ever renamed once written.
     */
    private static Map<String, Measured> specs() {
        Map<String, Measured> specs = new LinkedHashMap<>();
        specs.put(
                Measure.TURN_DURATION,
                new Measured(
                        Measure.TURN_DURATION,
                        // TURN: one user-facing turn, read off its root span. The bucket is the entry
                        // point's call site — a trace legitimately spans several call sites, so a baseline
                        // scoped to a child would model "traces that happened to contain this tool".
                        Grain.TURN,
                        false,
                        MetricBaselineRow.BucketKind.CALL_SITE,
                        Grid.duration()));
        specs.put(
                Measure.TOOL_DURATION,
                new Measured(
                        Measure.TOOL_DURATION,
                        // OBSERVATION: one dispatchable span, read off its own interval. The bucket is an
                        // ActionSymbol `kind:normalized-name` and carries NO call site, deliberately — a
                        // tool's latency is a tool's latency whichever entry point called it, and scoping
                        // the bucket per call site would shatter a tool used in five places into five
                        // populations, none of them thick enough to arm. Which call site a FINDING is
                        // filed under is decided per window from the traffic that filled it.
                        Grain.OBSERVATION,
                        // Same zero horizon as the turn, and for the same reason: the span carries its own
                        // start and end, so its arrival is the completion signal.
                        false,
                        MetricBaselineRow.BucketKind.TOOL,
                        // The same duration grid as the turn. Not incidental — MetricSuppression compares
                        // the two grains' medians in milliseconds, and a shared layout is what keeps those
                        // two numbers commensurable without a conversion nobody would remember to make.
                        Grid.duration()));
        specs.put(
                Measure.COST,
                new Measured(Measure.COST, Grain.TURN, true, MetricBaselineRow.BucketKind.CALL_SITE, Grid.cost()));
        return specs;
    }

    /** Re-lay a registry spec's grid on the configured bin count; {@code lo} and the ratio are fixed. */
    private Measured withBins(Measured spec) {
        Grid grid = spec.grid();
        if (grid.bins() == histBins) return spec;
        return new Measured(
                spec.measure(),
                spec.grain(),
                spec.settles(),
                spec.bucketKind(),
                new Grid(grid.lo(), grid.ratio(), histBins));
    }

    /**
     * Dedupe and drop names the registry does not know. Dropping rather than throwing is deliberate: a
     * blob written by a newer build naming a measure this one has not learned yet would otherwise
     * dead-letter the sweep, taking the measures it DOES understand down with it.
     */
    private static List<String> knownMeasures(List<String> raw) {
        Set<String> known = specs().keySet();
        Set<String> kept = new LinkedHashSet<>();
        for (String name : raw) {
            if (name != null && known.contains(name)) kept.add(name);
        }
        return List.copyOf(kept);
    }

    private static List<String> measureNames(JsonNode node, List<String> fallback) {
        JsonNode v = node.get("measures");
        if (v == null || !v.isArray()) return fallback;
        List<String> names = new ArrayList<>(v.size());
        for (JsonNode item : v) {
            if (item.isTextual()) names.add(item.asText());
        }
        return names;
    }

    private static int i(JsonNode node, String field, int fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asInt(fallback);
    }

    private static double d(JsonNode node, String field, double fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asDouble(fallback);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.min(hi, Math.max(lo, v));
    }
}
