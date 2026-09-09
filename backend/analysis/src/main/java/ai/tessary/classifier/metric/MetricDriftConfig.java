// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
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
 * {@code classifiers/metric_drift/PROGRAM.md}, defaults enumerated in {@code PLAN.md} §4. The arithmetic
 * these numbers feed lives in {@code devdocs/concepts/deviation-math.md}; changing a default here without
 * updating that doc leaves it describing a detector that no longer exists.
 *
 * <p>Every field is clamped in the compact constructor: these blobs are editable live, per project,
 * while the classifier is being tuned, and an unclamped {@code w1_floor} of 0 turns the detector into a
 * firehose on the next sweep with nothing in the code path to notice.
 *
 * <p>Every default below is provisional (tagged {@code EXPERIMENT(metric-drift-tuning)}), and both
 * metric-drift modules seed disabled until PLAN.md §9's null-case run against real traffic writes back a
 * measured operating point.
 *
 * <p><b>The measure list is the classifier.</b> One switch governs several measures (PROGRAM.md §3.1):
 * {@code duration_drift} is turn plus tool, {@code cost_drift} is cost plus its token evidence, so which
 * measures are live is config, not a catalog fact. Each measure carries its own grain here, in
 * {@link Measured}, and the sweep reads it from this record rather than from
 * {@code BuiltInClassifierCatalog.grainFor()}, which returns one grain per detector kind.
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
    // Defaults: PLAN.md §4, every one EXPERIMENT(metric-drift-tuning)
    // -----------------------------------------------------------------------------------------------

    /**
     * EXPERIMENT(metric-drift-tuning): samples that close a window on the thick path. 500 is enough that
     * a p95 read off the window is not luck, and small enough that a busy call site closes several
     * windows a day rather than reporting a regression a week late.
     */
    public static final int DEFAULT_WINDOW_TARGET_COUNT = 500;

    /**
     * EXPERIMENT(metric-drift-tuning): the elapsed-EVENT-time close for a bucket too thin to reach the
     * count. One day.
     *
     * <p>{@link MetricDriftDetector#effectiveFloor} scales the move bar to the sample size, so a bucket
     * that closes early is judged against a bar it can support: at a hundred samples against a
     * five-hundred reference that's a 27% move, held to the same 1% false-alarm rate a full window gets.
     * Small moves on thin traffic stay invisible, but large ones now surface in a day instead of a week.
     */
    public static final int DEFAULT_WINDOW_MAX_HOURS = 24;

    /**
     * EXPERIMENT(metric-drift-tuning): the floor below which nothing is compared at all. 100 samples put
     * roughly 5 in the top 5%, the least a p95 can be built from without the tail being one trace's
     * opinion.
     *
     * <p>{@link MetricDriftDetector#effectiveFloor} raises the move bar for windows near this floor back
     * to a 1% false-alarm rate; this constant also bounds that scaling, since the bar cannot exceed
     * {@code w1Floor · sqrt(windowTargetCount / minSample)}.
     *
     * <p>This is a wait, not a skip: a bucket under the floor keeps its window open past
     * {@link #windowMaxHours} rather than closing an uncomparable one, so a rare tool eventually gets
     * watched instead of never being watched.
     */
    public static final int DEFAULT_MIN_SAMPLE = 100;

    /**
     * EXPERIMENT(metric-drift-tuning): the smallest move worth reporting, as a signed-W₁ magnitude in log
     * units, at a full window. {@code e^0.139 ≈ 1.15}, so "tell me about 15% moves".
     *
     * <p>The dial is the move, deliberately, not a false-alarm rate: the two are interchangeable
     * arithmetic, but "tell you about 15% moves" is something an operator can hold in their head and
     * predict, while "1% false-alarm rate" makes the reportable move differ per call site.
     * {@link MetricDriftDetector#impliedFalseAlarmRate} surfaces the consequence where the dial is set.
     *
     * <p>0.139 is the 99th percentile of a synthetic null run (two 500-sample windows drawn from one
     * lognormal, 20k repetitions) for a wide-spread call site whose p95 is ≈3.7× its median, typical of
     * agent work: the 1% bar on that traffic. Still synthetic; PLAN.md §9's null case against real traffic
     * is what settles it.
     */
    public static final double DEFAULT_W1_FLOOR = 0.139;

    /**
     * EXPERIMENT(metric-drift-tuning): how much of a turn-duration shift a tool-duration shift inside the
     * same call site must account for, in absolute time, before the turn finding is suppressed in its
     * favor ({@link MetricSuppression}, PROGRAM.md §6.1). Half.
     *
     * <p>Deliberately generous: a tool bucket's delta is measured per call and a turn's per turn, and a
     * turn usually makes more than one call to whichever tool dominates it, so a single call's delta
     * systematically understates its own contribution. Suppression is cheap either way, since the
     * suppressed turn shift stays attached to the tool finding as evidence, so the bar only decides which
     * of the two is the headline.
     *
     * <p>It must not swallow the case turn duration exists for: eleven tool calls where three used to do,
     * where every call is as fast as ever and only the count moved. That case leaves every tool bucket
     * flat, clearing this bar by a wide margin rather than a hair.
     */
    public static final double DEFAULT_EXPLAINED_BY_FRACTION = 0.5;

    /**
     * EXPERIMENT(metric-drift-tuning): how long a trace is given for its spans to arrive before a summing
     * measure reads it. Five minutes, matching {@code BehaviorDriftConfig}'s {@code trace_settle_seconds},
     * since both wait on the same exporter.
     *
     * <p>Applied as a blanket horizon only by measures that {@link Measured#settles()}; duration skips it,
     * see that method for why a fixed delay there would buy nothing.
     *
     * <p>It also caps how long {@code MetricDriftSweep.admissibleThrough} holds a page open for a trace
     * whose root span hasn't landed (a trace whose root is already there is swept immediately), so a trace
     * whose root never arrives can't stall a forward-only cursor for good.
     */
    public static final int DEFAULT_SETTLE_SECONDS = 300;

    /**
     * EXPERIMENT(metric-drift-tuning): bins per sketch grid, {@link MetricHistogram#DEFAULT_BINS}.
     *
     * <p>The one dial here with a persistence consequence: the bin count is part of a sketch's
     * {@code gridId}, and sketches already written outlive an edit to it. Changing this therefore
     * orphans every pinned and previous window in the project. That is survivable rather than fatal:
     * {@link MetricDriftDetector} treats a grid mismatch as a silent pass and the next close re-pins,
     * but it is a real cost, so the clamp range below is deliberately narrow.
     */
    public static final int DEFAULT_HIST_BINS = MetricHistogram.DEFAULT_BINS;

    // -----------------------------------------------------------------------------------------------
    // Clamps: a live-edited blob must not be able to drive the detector into nonsense
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
    // ever cover the whole of a turn's delta measured per call, so the rule would switch itself off,
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
        // above the graduation bar, and for the same reason: the failure is invisible, not loud.
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
     * The counterpart of {@link #of}, kept in the same class so the two ends of this persisted shape
     * cannot drift apart un-checked. Every field round-trips through the same snake_case names {@link #of}
     * reads, so a config edited via {@code ClassifierService#setTuning} and re-parsed on the next sweep
     * reads back exactly what this wrote.
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
     * <p>These are facts about the measure, not knobs: a config blob can turn a measure on or off, but
     * cannot claim that cost is read off a span or that duration needs a settle horizon.
     *
     * <p>Only measures that can open a finding appear here. The four token buckets don't: PROGRAM.md §6.1
     * makes them evidence attached to the cost finding rather than findings of their own, so a prompt edit
     * that kills caching writes one row rather than five. {@link ai.tessary.classifier.metric.MetricSource}
     * computes them every window regardless.
     *
     * @param settles whether this measure must wait out {@link #settleSeconds} before reading a trace.
     *     The split is load-bearing (PROGRAM.md §5): cost and the token buckets sum over a trace's spans
     *     and need every span to have arrived, since measuring early reads as cheap and drifts permanently
     *     toward cheaper when ingest lags. Duration is read off a single span that carries its own start
     *     and end, so a settle horizon there buys nothing and just delays every finding.
     */
    public record Measured(String measure, Grain grain, boolean settles, String bucketKind, Grid grid) {}

    /**
     * How long a trace must have existed before this signal may read it: the max over the measures it is
     * live for, since one signal has one job row and therefore one keyset cursor.
     *
     * <p>Taking the max rather than a per-measure horizon keeps the two settle paths honest under a shared
     * cursor: a cursor advanced on a duration measure's zero-second horizon would walk past traces whose
     * spans haven't landed, and a cost measure on the same cursor could never go back for them.
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

    /** The specs for the live measures drawn at one grain: the sweep's per-grain fan-out. */
    public List<Measured> measuredAt(Grain grain) {
        return measured().stream().filter(m -> m.grain() == grain).toList();
    }

    /**
     * The registry. Keyed by the PERSISTED measure name, which is also
     * {@code metric_baseline.measure}: wire and Java say <em>classifier</em>, the database and its
     * persisted strings stay <em>signal</em>, and neither is ever renamed once written.
     */
    private static Map<String, Measured> specs() {
        Map<String, Measured> specs = new LinkedHashMap<>();
        specs.put(
                Measure.TURN_DURATION,
                new Measured(
                        Measure.TURN_DURATION,
                        // TURN: one user-facing turn, read off its root span. The bucket is the entry
                        // point's call site: a trace can span several call sites, so a baseline scoped to
                        // a child would model "traces that happened to contain this tool".
                        Grain.TURN,
                        false,
                        MetricBaselineRow.BucketKind.CALL_SITE,
                        Grid.duration()));
        specs.put(
                Measure.TOOL_DURATION,
                new Measured(
                        Measure.TOOL_DURATION,
                        // OBSERVATION: one dispatchable span, read off its own interval. The bucket is an
                        // ActionSymbol `kind:normalized-name` with no call site, deliberately: a tool's
                        // latency is a tool's latency regardless of entry point, and scoping per call site
                        // would shatter a tool used in five places into five thin populations. Which call
                        // site a finding is filed under is decided per window from the traffic that filled
                        // it.
                        Grain.OBSERVATION,
                        // Same zero horizon as the turn: the span carries its own start and end, so its
                        // arrival is the completion signal.
                        false,
                        MetricBaselineRow.BucketKind.TOOL,
                        // The same duration grid as the turn, deliberately: MetricSuppression compares the
                        // two grains' medians in milliseconds, and a shared layout keeps those numbers
                        // commensurable.
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
