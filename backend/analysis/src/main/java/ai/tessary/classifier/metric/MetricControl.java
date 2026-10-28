// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.finding.EvidenceSample;
import ai.tessary.classifier.metric.MetricEvidenceRefs.Timed;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A bucket's recent normal: closed windows of the last weeks, one exactly-merged slot per UTC EVENT day, weighted
 * {@code 2^(-age/7)}, with days a confirmed regression ran through left out so a real step change keeps firing until
 * ruled on. Retention is anchored on the newest day held, never wall clock, so an out-of-order import cannot evict a
 * newer day. Only the measure sketch is weighted; workload and token blocks are context.
 */
public final class MetricControl {

    /** Never renamed. */
    static final String KIND = "control";

    /**
     * Not a config dial: it decides how fast a legitimate new level is accepted, a product posture. Operators tune
     * {@code w1_floor}.
     */
    static final double HALF_LIFE_DAYS = 7.0;

    /** Three half-lives; past this a day is worth under an eighth. */
    static final int RETAIN_DAYS = 21;

    /**
     * Rows kept per day slot, for the rolling arm's baseline sample. A product constant like {@link #HALF_LIFE_DAYS}:
     * {@link #RETAIN_DAYS} of them bound a bucket's ring.
     */
    static final int DAY_ROWS = 100;

    /**
     * {@code day} is the key exclusion decides on. A day without a sketch is never held. {@code refsJson} is a sample of
     * the rows folded into the day, in fold order; null on a day folded before rows were kept.
     */
    public record Day(
            String day,
            String sketchJson,
            @Nullable String workloadJson,
            @Nullable String tokensJson,
            @Nullable String refsJson) {}

    private final List<Day> days;

    private MetricControl(List<Day> days) {
        this.days = days;
    }

    public static MetricControl empty() {
        return new MetricControl(List.of());
    }

    /**
     * What absorb pins. Not {@code current_sketch_json}, which is still filling. Not exclusion-filtered: absorb accepts
     * exactly the level a confirmed finding was about.
     */
    public @Nullable Day newest() {
        return days.isEmpty() ? null : days.get(days.size() - 1);
    }

    public static String dayOf(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
    }

    /**
     * Returns a new instance, since one control is threaded through a page that may close several windows. The fold is
     * exact; weighting happens at read time in {@link #resolve}. Retention anchors on {@code max(day, days held)}, so
     * folding an older day can never evict a newer one. {@code rows} are the closed window's rows; a day keeps up to
     * {@link #DAY_ROWS} of them, each window's share in proportion to the samples it put into the day's sketch.
     */
    public MetricControl fold(
            Grid grid,
            String day,
            MetricSketch measure,
            MetricWorkload workload,
            MetricTokens tokens,
            List<Timed> rows) {
        Map<String, Day> byDay = new LinkedHashMap<>();
        for (Day d : days) {
            byDay.put(d.day(), d);
        }

        Day existing = byDay.get(day);
        MetricSketch mergedMeasure = measure.copy();
        MetricWorkload mergedWorkload = workload.copy();
        MetricTokens mergedTokens = tokens.copy();
        List<Timed> keptRows = List.of();
        if (existing != null) {
            // A dead-grid slot is discarded, as MetricWorkload.fromJson does; merging it would throw on every close.
            MetricSketch prior = readSketch(existing.sketchJson(), grid);
            if (prior != null) {
                mergedMeasure.merge(prior);
                keptRows = existingShare(existing, prior.count(), measure.count());
            }
            MetricWorkload priorWorkload = readWorkload(existing.workloadJson(), grid);
            if (priorWorkload != null) mergedWorkload.merge(priorWorkload);
            MetricTokens priorTokens = readTokens(existing.tokensJson(), grid);
            if (priorTokens != null) mergedTokens.merge(priorTokens);
        }
        byDay.put(
                day,
                new Day(
                        day,
                        mergedMeasure.toJson(),
                        mergedWorkload.isEmpty() ? null : mergedWorkload.toJson(),
                        mergedTokens.isEmpty() ? null : mergedTokens.toJson(),
                        rowsJson(keptRows, rows)));

        // Never wall-clock now: a backfilled old day leaves the anchor alone.
        String anchor = day;
        for (String d : byDay.keySet()) {
            if (d.compareTo(anchor) > 0) anchor = d;
        }
        String oldest = LocalDate.parse(anchor).minusDays(RETAIN_DAYS).toString();

        List<Day> out = new ArrayList<>();
        for (Day d : byDay.values()) {
            if (d.day().compareTo(oldest) < 0) continue;
            out.add(d);
        }
        out.sort((a, b) -> a.day().compareTo(b.day()));
        return new MetricControl(List.copyOf(out));
    }

    /** Shares follow the day sketch's counts, so the last window cannot take most of a day it added little to. */
    private static List<Timed> existingShare(Day existing, long priorCount, long newCount) {
        if (priorCount + newCount <= 0) return List.of();
        int share = (int) Math.round((double) DAY_ROWS * priorCount / (priorCount + newCount));
        return EvidenceSample.spread(MetricEvidenceRefs.fromJson(existing.refsJson()), share);
    }

    private static @Nullable String rowsJson(List<Timed> kept, List<Timed> added) {
        List<Timed> out = new ArrayList<>(kept);
        out.addAll(EvidenceSample.spread(added, DAY_ROWS - kept.size()));
        return MetricEvidenceRefs.toJson(out);
    }

    /**
     * Ages are measured back from {@code eventDay}, never wall clock, and days after it are skipped, so a replay never
     * compares against traffic that had not happened yet. {@code excludedDays} is applied on every pass rather than at
     * fold time, so a late verdict is retroactive.
     */
    public @Nullable Resolved resolve(Grid grid, String eventDay, Set<String> excludedDays) {
        Weighted measure = new Weighted(grid);
        MetricWorkload workload = new MetricWorkload(MetricWorkload.grid(grid.bins()));
        MetricTokens tokens = new MetricTokens(MetricTokens.grid(grid.bins()));
        int excluded = 0;
        List<Day> used = new ArrayList<>();
        for (Day d : days) {
            if (excludedDays.contains(d.day())) {
                excluded++;
                continue;
            }
            MetricSketch sketch = readSketch(d.sketchJson(), grid);
            if (sketch == null) continue;
            double weight = weightOf(d.day(), eventDay);
            if (weight <= 0) continue;
            measure.fold(sketch, weight);
            MetricWorkload dayWorkload = readWorkload(d.workloadJson(), grid);
            if (dayWorkload != null) workload.merge(dayWorkload);
            MetricTokens dayTokens = readTokens(d.tokensJson(), grid);
            if (dayTokens != null) tokens.merge(dayTokens);
            used.add(d);
        }
        if (used.isEmpty() || measure.count() == 0) return null;
        return new Resolved(
                measure,
                workload.isEmpty() ? null : workload,
                tokens.isEmpty() ? null : tokens,
                used.size(),
                excluded,
                used.get(0).day(),
                List.copyOf(used));
    }

    /** The rows the days kept, in day order. Decoded here rather than in {@link #resolve}, which most reads call. */
    public static List<Timed> rows(List<Day> days) {
        List<Timed> out = new ArrayList<>();
        for (Day d : days) {
            out.addAll(MetricEvidenceRefs.fromJson(d.refsJson()));
        }
        return List.copyOf(out);
    }

    /**
     * {@code daysUsed} tells "went quiet" from "excluded everything"; {@code daysExcluded} explains why a long
     * regression keeps firing. {@code days} are the days that passed every skip, oldest first.
     */
    public record Resolved(
            MetricReading measure,
            @Nullable MetricWorkload workload,
            @Nullable MetricTokens tokens,
            int daysUsed,
            int daysExcluded,
            @Nullable String oldestDay,
            List<Day> days) {}

    /**
     * 0 outside retention or after {@code eventDay}. A day exactly {@link #RETAIN_DAYS} old still counts, and the same
     * day weighs 1.0.
     */
    private static double weightOf(String day, String eventDay) {
        long age = ChronoUnit.DAYS.between(LocalDate.parse(day), LocalDate.parse(eventDay));
        if (age < 0 || age > RETAIN_DAYS) return 0.0;
        return Math.pow(2.0, -age / HALF_LIFE_DAYS);
    }

    private static @Nullable MetricSketch readSketch(String json, Grid grid) {
        try {
            MetricSketch sketch = MetricSketch.fromJson(json);
            return sketch.gridId().equals(grid.id()) ? sketch : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable MetricWorkload readWorkload(@Nullable String json, Grid grid) {
        if (json == null || json.isBlank()) return null;
        try {
            return MetricWorkload.fromJson(json, MetricWorkload.grid(grid.bins()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable MetricTokens readTokens(@Nullable String json, Grid grid) {
        if (json == null || json.isBlank()) return null;
        try {
            return MetricTokens.fromJson(json, MetricTokens.grid(grid.bins()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String toJson() {
        ObjectNode root = MetricHistogram.JSON.createObjectNode();
        root.put("kind", KIND);
        // Written down so a ring resolved under a different half-life is detectable.
        root.put("half_life_days", HALF_LIFE_DAYS);
        ArrayNode arr = root.putArray("days");
        for (Day d : days) {
            ObjectNode node = arr.addObject();
            node.put("d", d.day());
            node.putRawValue("m", new RawValue(d.sketchJson()));
            if (d.workloadJson() != null) node.putRawValue("w", new RawValue(d.workloadJson()));
            if (d.tokensJson() != null) node.putRawValue("t", new RawValue(d.tokensJson()));
            if (d.refsJson() != null) node.putRawValue("r", new RawValue(d.refsJson()));
        }
        return root.toString();
    }

    /**
     * A malformed payload comes back empty: the next close rebuilds it, and one bucket must not take the sweep down.
     */
    public static MetricControl fromJson(@Nullable String json) {
        if (json == null || json.isBlank()) return empty();
        try {
            JsonNode root = MetricHistogram.JSON.readTree(json);
            List<Day> out = new ArrayList<>();
            for (JsonNode node : root.path("days")) {
                String day = node.path("d").asText("");
                JsonNode measure = node.get("m");
                if (day.isEmpty() || measure == null || measure.isNull()) continue;
                JsonNode workload = node.get("w");
                JsonNode tokens = node.get("t");
                JsonNode refs = node.get("r");
                out.add(new Day(
                        day,
                        measure.toString(),
                        workload == null || workload.isNull() ? null : workload.toString(),
                        tokens == null || tokens.isNull() ? null : tokens.toString(),
                        refs == null || refs.isNull() ? null : refs.toString()));
            }
            out.sort((a, b) -> a.day().compareTo(b.day()));
            return new MetricControl(List.copyOf(out));
        } catch (JsonProcessingException e) {
            return empty();
        }
    }

    /**
     * The only sketch here with fractional bin mass. Separate from {@link MetricHistogram} because double bins would
     * change every persisted sketch and lose integer-merge exactness.
     */
    static final class Weighted implements MetricReading {

        private final Grid grid;
        private final double[] bins;
        private double underflow;

        private double mass;

        /** Carried only for Kish's effective sample size. */
        private double massSq;

        private double clampedSumLog;

        Weighted(Grid grid) {
            this.grid = grid;
            this.bins = new double[grid.bins()];
        }

        void fold(MetricSketch other, double w) {
            MetricHistogram h = (MetricHistogram) other;
            h.foldScaledInto(bins, w);
            underflow += w * h.underflow();
            mass += w * h.count();
            massSq += w * w * h.count();
            clampedSumLog += w * h.meanLog() * h.count();
        }

        /**
         * Kish's effective sample size {@code (Σw)² / Σw²}, not the raw total:
         * {@link MetricDriftDetector#effectiveFloor} sets the bar from it, and faded days carry less precision.
         */
        @Override
        public long count() {
            if (massSq <= 0) return 0;
            return Math.round(mass * mass / massSq);
        }

        @Override
        public double meanLog() {
            return mass == 0 ? 0.0 : clampedSumLog / mass;
        }

        @Override
        public double[] cdf() {
            double[] out = new double[bins.length + 2];
            if (mass == 0) return out;
            double running = underflow;
            out[0] = running / mass;
            for (int i = 0; i < bins.length; i++) {
                running += bins[i];
                out[i + 1] = running / mass;
            }
            out[bins.length + 1] = 1.0;
            return out;
        }

        @Override
        public @Nullable Double quantile(double q) {
            if (mass == 0) return null;
            double targetRank = Math.clamp(q, 0.0, 1.0) * mass;
            double below = underflow;
            if (below > 0 && targetRank <= below) return grid.logLo();
            for (int i = 0; i < bins.length; i++) {
                if (bins[i] == 0) continue;
                if (targetRank <= below + bins[i]) {
                    double within = (targetRank - below) / bins[i];
                    return grid.logLo() + (i + within) * grid.slotWidthLog();
                }
                below += bins[i];
            }
            return grid.logHi();
        }

        @Override
        public String gridId() {
            return grid.id();
        }

        @Override
        public double slotWidthLog() {
            return grid.slotWidthLog();
        }
    }
}
