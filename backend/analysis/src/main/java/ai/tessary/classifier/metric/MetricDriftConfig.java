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
 * The metric-drift operating point from {@code config_json} (metric-drift.md, deviation-math.md). Every field is
 * clamped because these blobs are live-edited and a {@code w1_floor} of 0 would be a firehose. Each measure carries its
 * own grain in {@link Measured}. Unknown measure names are dropped, so a newer build's blob cannot dead-letter an older
 * sweep.
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

    /**
     * EXPERIMENT(metric-drift-tuning): enough that a window's p95 is not luck, small enough that a busy call site
     * closes several windows a day.
     */
    public static final int DEFAULT_WINDOW_TARGET_COUNT = 500;

    /**
     * EXPERIMENT(metric-drift-tuning): the elapsed-EVENT-time close for thin buckets.
     * {@link MetricDriftDetector#effectiveFloor} raises the bar for an early close.
     */
    public static final int DEFAULT_WINDOW_MAX_HOURS = 24;

    /**
     * EXPERIMENT(metric-drift-tuning): 100 samples put about 5 in the top 5%. Also bounds the effective-floor scaling.
     * A wait, not a skip.
     */
    public static final int DEFAULT_MIN_SAMPLE = 100;

    /**
     * EXPERIMENT(metric-drift-tuning): {@code e^0.139 ≈ 1.15}, "tell me about 15% moves", the 99th percentile of a
     * synthetic null run on wide-spread traffic. The dial is the move, not a false-alarm rate, so operators can predict
     * it.
     */
    public static final double DEFAULT_W1_FLOOR = 0.139;

    /**
     * EXPERIMENT(metric-drift-tuning): the share of a turn shift a tool shift must explain to suppress the turn finding
     * ({@link MetricSuppression}). Generous because a turn usually makes several calls of its dominant tool; the
     * suppressed shift stays attached as evidence.
     */
    public static final double DEFAULT_EXPLAINED_BY_FRACTION = 0.5;

    /**
     * EXPERIMENT(metric-drift-tuning): how long a page is held for a trace whose root span has not landed, so a root
     * that never arrives cannot stall the cursor.
     */
    public static final int DEFAULT_SETTLE_SECONDS = 300;

    /**
     * EXPERIMENT(metric-drift-tuning): the bin count is part of a sketch's {@code gridId}, so changing it orphans every
     * persisted window; the clamp range is deliberately narrow.
     */
    public static final int DEFAULT_HIST_BINS = MetricHistogram.DEFAULT_BINS;

    private static final int WINDOW_TARGET_COUNT_MIN = 50;
    private static final int WINDOW_TARGET_COUNT_MAX = 100_000;
    private static final int WINDOW_MAX_HOURS_MIN = 1;
    private static final int WINDOW_MAX_HOURS_MAX = 2_160;
    private static final int MIN_SAMPLE_MIN = 30;
    // 0.01 log units is a ~1% shift, below the eval's own quiet case; 3.0 is a 20x move.
    private static final double W1_FLOOR_MIN = 0.01;
    private static final double W1_FLOOR_MAX = 3.0;
    // Below 0.1 any tool wobble claims the turn's shift; above 1.0 the rule silently switches itself off.
    private static final double EXPLAINED_BY_FRACTION_MIN = 0.1;
    private static final double EXPLAINED_BY_FRACTION_MAX = 1.0;
    private static final int SETTLE_SECONDS_MIN = 0;
    private static final int SETTLE_SECONDS_MAX = 3_600;
    private static final int HIST_BINS_MIN = 64;
    private static final int HIST_BINS_MAX = 1_024;
    private static final MetricDriftConfig DEFAULTS = new MetricDriftConfig(
            // Shipping turn without tool would report a symptom it could have named the cause of.
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
        // min_sample above window_target_count would silently mean no bucket is ever compared.
        minSample = (int) clamp(minSample, MIN_SAMPLE_MIN, windowTargetCount);
        w1Floor = clamp(w1Floor, W1_FLOOR_MIN, W1_FLOOR_MAX);
        explainedByFraction = clamp(explainedByFraction, EXPLAINED_BY_FRACTION_MIN, EXPLAINED_BY_FRACTION_MAX);
        settleSeconds = (int) clamp(settleSeconds, SETTLE_SECONDS_MIN, SETTLE_SECONDS_MAX);
        histBins = (int) clamp(histBins, HIST_BINS_MIN, HIST_BINS_MAX);
    }

    public static MetricDriftConfig defaults() {
        return DEFAULTS;
    }

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

    /** Kept beside {@link #of} so the persisted shape round-trips. */
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

    /**
     * Fixed facts about a measure, not knobs. Only measures that can open a finding appear: the token buckets are
     * evidence on the cost finding (§6.1).
     */
    public record Measured(String measure, Grain grain, String bucketKind, Grid grid) {}

    public List<Measured> measured() {
        List<Measured> out = new ArrayList<>(measures.size());
        for (String measure : measures) {
            Measured spec = specs().get(measure);
            if (spec != null) out.add(withBins(spec));
        }
        return List.copyOf(out);
    }

    public List<Measured> measuredAt(Grain grain) {
        return measured().stream().filter(m -> m.grain() == grain).toList();
    }

    /**
     * Keyed by the PERSISTED measure name, also {@code metric_baseline.measure}; persisted strings are never renamed.
     */
    private static Map<String, Measured> specs() {
        Map<String, Measured> specs = new LinkedHashMap<>();
        specs.put(
                Measure.TURN_DURATION,
                new Measured(
                        Measure.TURN_DURATION,
                        // The bucket is the entry point's call site: a child-scoped baseline would model "traces that
                        // happened to contain this tool".
                        Grain.TURN,
                        MetricBaselineRow.BucketKind.CALL_SITE,
                        Grid.duration()));
        specs.put(
                Measure.TOOL_DURATION,
                new Measured(
                        Measure.TOOL_DURATION,
                        // The bucket is the tool with no call site, so a tool used in five places is not shattered into
                        // five thin populations.
                        Grain.OBSERVATION,
                        MetricBaselineRow.BucketKind.TOOL,
                        // The same grid as the turn, so MetricSuppression compares commensurable medians.
                        Grid.duration()));
        specs.put(
                Measure.COST,
                new Measured(Measure.COST, Grain.TURN, MetricBaselineRow.BucketKind.CALL_SITE, Grid.cost()));
        return specs;
    }

    private Measured withBins(Measured spec) {
        Grid grid = spec.grid();
        if (grid.bins() == histBins) return spec;
        return new Measured(
                spec.measure(), spec.grain(), spec.bucketKind(), new Grid(grid.lo(), grid.ratio(), histBins));
    }

    /**
     * Dropping rather than throwing: a newer build's measure name would otherwise dead-letter the measures this build
     * understands.
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
