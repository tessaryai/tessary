// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * A bucket's recent normal: the closed windows of the last few weeks, weighted so that recent traffic
 * counts for more, with the days a confirmed regression ran through left out. Replaces the single
 * previously-closed window as metric drift's short-horizon reference
 * ({@code classifiers/metric_drift/PROGRAM.md} §4.3).
 *
 * <h2>Why one window was not enough</h2>
 *
 * <p>The previous window is one arbitrary slice of traffic, and everything wrong with it follows from
 * that. It is as noisy as any single window, so half the comparison's sampling noise came from the bar
 * rather than from the thing being measured. It has whatever shape the clock gave it — a window that
 * happened to close over a quiet night was the bar a busy morning got judged against. And a step change
 * fires against it exactly once, because the very next window's previous IS the new level: the reference
 * that was supposed to catch sudden breaks forgets them within one window.
 *
 * <p>A control built from several weeks fixes all three at once. It is thicker, so it is quieter; it
 * spans whole diurnal and weekday cycles, so a Monday morning is judged against a mixture that already
 * contains previous mornings; and because a confirmed regression is kept OUT of it, a step change goes
 * on firing until somebody rules on it rather than normalizing itself.
 *
 * <h2>Days, not windows</h2>
 *
 * <p>The ring holds one slot per UTC day, each an exact merge of every window that closed in it, rather
 * than one slot per window. That is what makes it bounded: a busy bucket closes a window every few
 * minutes, so a per-window ring would either grow without limit or hold a hard cap that spans a few
 * hours — which would put us back to comparing a morning against a night, the diurnal problem the long
 * horizon exists to solve. A day slot costs the same however busy the bucket is.
 *
 * <p>The day is also the grain exclusion works on, and that is a feature rather than a rounding: a day
 * some of whose traffic was a confirmed regression is not a day whose traffic was normal.
 *
 * <h2>The weighting</h2>
 *
 * <p>A day {@code a} days old contributes {@code 2^(-a/7)} per sample — a seven-day half-life on the
 * wall clock, so the control tracks a genuine change in level over a fortnight or so while a single bad
 * afternoon never dominates it. Retention stops at {@link #RETAIN_DAYS}, three half-lives, past which a
 * day is worth an eighth of a fresh one and is not worth the bytes.
 *
 * <p><b>Wall clock, not event time.</b> Ages are measured against the sweep's own clock, so a backfill
 * that lands a month of event time in one pass does not resolve to a control whose every day is
 * simultaneously fresh and ancient.
 *
 * <h2>What the weights do and do not touch</h2>
 *
 * <p>Weighting applies to the MEASURE sketch — the one the detector reads — and not to the workload and
 * token blocks, which are merged exactly over the same retained days. Those blocks are context for a
 * human ("the inputs were flat while the outputs moved"), never an input to the decision, and a median
 * of the reference period's prompt sizes does not become more honest for being tilted toward Tuesday.
 */
public final class MetricControl {

    /** The {@code kind} discriminator written into {@code metric_baseline.control_json}. Never renamed. */
    static final String KIND = "control";

    /**
     * Half-life of a day's contribution, in days. Seven, so the control's memory spans whole weekday and
     * diurnal cycles: at one day old a slot still counts 91%, at a week 50%, at a fortnight 25%.
     *
     * <p>Not a config dial. It decides how fast a legitimate new level is accepted, which is a claim
     * about the product's posture rather than a per-project tuning knob — and the dial that already
     * exists for "how big a move counts" ({@code w1_floor}) is the one an operator should reach for.
     */
    static final double HALF_LIFE_DAYS = 7.0;

    /** How far back the ring keeps days. Three half-lives; past this a day is worth under an eighth. */
    static final int RETAIN_DAYS = 21;

    /**
     * One UTC day of closed windows, merged exactly.
     *
     * @param day ISO {@code yyyy-MM-dd}, UTC. The key, and the thing exclusion is decided on.
     * @param sketchJson the day's merged measure sketch. Always present — a day with no sketch is not a
     *     day the ring has any reason to hold.
     * @param workloadJson the day's merged workload, or null when nothing reported one.
     * @param tokensJson the day's merged token decomposition, or null — every duration day, and any cost
     *     day whose traffic carried no usage.
     */
    public record Day(
            String day,
            String sketchJson,
            @Nullable String workloadJson,
            @Nullable String tokensJson) {}

    /** Ascending by day, oldest first, at most {@link #RETAIN_DAYS} entries. */
    private final List<Day> days;

    private MetricControl(List<Day> days) {
        this.days = days;
    }

    /** An empty control — what a bucket that has never closed a window has. */
    public static MetricControl empty() {
        return new MetricControl(List.of());
    }

    public boolean isEmpty() {
        return days.isEmpty();
    }

    /** The days held, oldest first. Exposed for the sweep's logging and for tests. */
    public List<Day> days() {
        return days;
    }

    /**
     * The newest day held, or null on an empty ring — the most recent COMPLETE summary of where this
     * bucket sits.
     *
     * <p>This is what <em>Legitimate — absorb</em> pins, and what a page falls back to when nothing has
     * been pinned yet. Deliberately not {@code current_sketch_json}, which is the window still being
     * filled: pinning a part-filled window installs a reference below {@code min_sample} that the
     * detector then silences with {@code BELOW_MIN_SAMPLE} until something else replaces it.
     *
     * <p>Not exclusion-filtered, and that is right for this use. A human pressing absorb is saying "this
     * level, the one I am looking at, is the new normal" — the very level a confirmed finding was written
     * about. Excluding it here would pin the level they are absorbing AWAY from.
     */
    public @Nullable Day newest() {
        return days.isEmpty() ? null : days.get(days.size() - 1);
    }

    /** The UTC day a window closing at {@code at} belongs to. */
    public static String dayOf(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
    }

    /**
     * Fold a just-closed window into the control and drop whatever has aged out.
     *
     * <p>Returns a new instance rather than mutating: the sweep threads one control through a page that
     * may close several windows, and a value here is what makes that thread obviously correct.
     *
     * <p><b>The fold is exact.</b> Windows landing in the same day are merged bin for bin, so a day
     * assembled from twenty windows equals the same day assembled from one — the same property that
     * makes {@link MetricSketch#merge} safe to call across page boundaries. Only the day-to-day weighting
     * is approximate, and it is applied at read time in {@link #resolve} rather than baked in here, so a
     * stored ring never has to be re-derived when the clock moves.
     */
    public MetricControl fold(
            Grid grid,
            String day,
            MetricSketch measure,
            @Nullable MetricWorkload workload,
            @Nullable MetricTokens tokens,
            Instant now) {
        Map<String, Day> byDay = new LinkedHashMap<>();
        String oldest =
                LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(RETAIN_DAYS).toString();
        for (Day d : days) {
            // A day older than retention, and — belt and braces — one dated in the future, which only a
            // clock skew or a hand-edited row produces and which would otherwise pin a weight at 1 forever.
            if (d.day().compareTo(oldest) < 0 || d.day().compareTo(dayOf(now)) > 0) continue;
            byDay.put(d.day(), d);
        }

        Day existing = byDay.get(day);
        MetricSketch mergedMeasure = measure.copy();
        MetricWorkload mergedWorkload = workload == null ? null : workload.copy();
        MetricTokens mergedTokens = tokens == null ? null : tokens.copy();
        if (existing != null) {
            // A slot on a dead grid — hist_bins edited under a live project — is DISCARDED rather than
            // merged, exactly as MetricWorkload.fromJson treats one: it can never line up with the samples
            // arriving now, and the alternative to dropping it is an exception on every close.
            MetricSketch prior = readSketch(existing.sketchJson(), grid);
            if (prior != null) mergedMeasure.merge(prior);
            MetricWorkload priorWorkload = readWorkload(existing.workloadJson(), grid);
            if (priorWorkload != null) {
                if (mergedWorkload == null) {
                    mergedWorkload = priorWorkload;
                } else {
                    mergedWorkload.merge(priorWorkload);
                }
            }
            MetricTokens priorTokens = readTokens(existing.tokensJson(), grid);
            if (priorTokens != null) {
                if (mergedTokens == null) {
                    mergedTokens = priorTokens;
                } else {
                    mergedTokens.merge(priorTokens);
                }
            }
        }
        byDay.put(
                day,
                new Day(
                        day,
                        mergedMeasure.toJson(),
                        mergedWorkload == null || mergedWorkload.isEmpty() ? null : mergedWorkload.toJson(),
                        mergedTokens == null || mergedTokens.isEmpty() ? null : mergedTokens.toJson()));

        List<Day> out = new ArrayList<>(byDay.values());
        out.sort((a, b) -> a.day().compareTo(b.day()));
        return new MetricControl(List.copyOf(out));
    }

    /**
     * The reference to compare a window against, or null when nothing usable is left after exclusion.
     *
     * @param excludedDays days a currently-confirmed regression ran through. Their traffic is a
     *     deviation somebody has already ruled on, so folding it in would let the very thing under
     *     investigation become the bar the next window is judged against — the silent normalization the
     *     pinned reference exists to prevent, reintroduced through the back door. Recomputed on every
     *     pass rather than applied when the day was folded, because a ruling lands well after the window
     *     closed; keeping the ring exact and the exclusion late is what makes a late verdict retroactive.
     */
    public @Nullable Resolved resolve(Grid grid, Instant now, Set<String> excludedDays) {
        Weighted measure = new Weighted(grid);
        MetricWorkload workload = new MetricWorkload(MetricWorkload.grid(grid.bins()));
        MetricTokens tokens = new MetricTokens(MetricTokens.grid(grid.bins()));
        int used = 0;
        int excluded = 0;
        String oldest = null;
        for (Day d : days) {
            if (excludedDays.contains(d.day())) {
                excluded++;
                continue;
            }
            MetricSketch sketch = readSketch(d.sketchJson(), grid);
            if (sketch == null) continue;
            double weight = weightOf(d.day(), now);
            if (weight <= 0) continue;
            measure.fold(sketch, weight);
            MetricWorkload dayWorkload = readWorkload(d.workloadJson(), grid);
            if (dayWorkload != null) workload.merge(dayWorkload);
            MetricTokens dayTokens = readTokens(d.tokensJson(), grid);
            if (dayTokens != null) tokens.merge(dayTokens);
            if (oldest == null) oldest = d.day();
            used++;
        }
        if (used == 0 || measure.count() == 0) return null;
        return new Resolved(
                measure,
                workload.isEmpty() ? null : workload,
                tokens.isEmpty() ? null : tokens,
                used,
                excluded,
                oldest);
    }

    /**
     * A resolved control: the weighted measure sketch the detector reads, and the exactly-merged context
     * blocks a finding reports beside it.
     *
     * @param daysUsed how many day slots survived exclusion and retention — logged, so "the control went
     *     quiet" is distinguishable from "the control excluded everything".
     * @param daysExcluded how many were dropped as days a confirmed regression ran through. Reported on
     *     the finding because it is the number that explains why a long-running regression keeps firing
     *     rather than normalizing itself into its own reference.
     * @param oldestDay the earliest day still contributing, so a reader can see the span the bar was
     *     built over rather than assume the full retention window. Null only when nothing was used, which
     *     {@link #resolve} returns null for anyway.
     */
    public record Resolved(
            MetricSketch measure,
            @Nullable MetricWorkload workload,
            @Nullable MetricTokens tokens,
            int daysUsed,
            int daysExcluded,
            @Nullable String oldestDay) {}

    /** {@code 2^(-age/7)}, or 0 for a day outside retention. */
    private static double weightOf(String day, Instant now) {
        long age;
        try {
            age = ChronoUnit.DAYS.between(LocalDate.parse(day), LocalDate.ofInstant(now, ZoneOffset.UTC));
        } catch (RuntimeException e) {
            return 0.0;
        }
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

    // ---------------------------------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------------------------------

    /** Serialized form for {@code metric_baseline.control_json}. Round-trips through {@link #fromJson}. */
    public String toJson() {
        ObjectNode root = MetricHistogram.JSON.createObjectNode();
        root.put("kind", KIND);
        // Written down rather than assumed on read: a ring persisted under one half-life and resolved
        // under another is a silent change of meaning, and a reader that can see both can say so.
        root.put("half_life_days", HALF_LIFE_DAYS);
        ArrayNode arr = root.putArray("days");
        for (Day d : days) {
            ObjectNode node = arr.addObject();
            node.put("d", d.day());
            try {
                node.set("m", MetricHistogram.JSON.readTree(d.sketchJson()));
                if (d.workloadJson() != null) node.set("w", MetricHistogram.JSON.readTree(d.workloadJson()));
                if (d.tokensJson() != null) node.set("t", MetricHistogram.JSON.readTree(d.tokensJson()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("metric control day produced unreadable json", e);
            }
        }
        return root.toString();
    }

    /**
     * Rehydrate a ring written by {@link #toJson()}. A malformed payload comes back EMPTY rather than
     * throwing: the control is a reference the next close rebuilds, and a bucket that cannot read its own
     * ring should wait for a fresh one rather than take the whole sweep down with it.
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
                out.add(new Day(
                        day,
                        measure.toString(),
                        workload == null || workload.isNull() ? null : workload.toString(),
                        tokens == null || tokens.isNull() ? null : tokens.toString()));
            }
            out.sort((a, b) -> a.day().compareTo(b.day()));
            return new MetricControl(List.copyOf(out));
        } catch (JsonProcessingException e) {
            return empty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The weighted view
    // ---------------------------------------------------------------------------------------------

    /**
     * A read-only {@link MetricSketch} over fractionally-weighted bin mass — what the day slots resolve
     * to, and the only sketch in this package whose bins are not integer counts.
     *
     * <p>Separate from {@link MetricHistogram} rather than a widening of it. Making the histogram's bins
     * doubles would change the shape of every persisted sketch in the table to serve one reader, and the
     * exactness of an integer merge is the property the rest of the design leans on hardest.
     */
    static final class Weighted implements MetricSketch {

        private final Grid grid;
        private final double[] bins;
        private double underflow;
        private double overflow;

        /** Total weighted mass — {@code Σ nᵢ·dᵢ}. The denominator of every proportion below. */
        private double mass;

        /** {@code Σ nᵢ·dᵢ²}, carried solely so {@link #count()} can report Kish's effective sample size. */
        private double massSq;

        private double clampedSumLog;

        Weighted(Grid grid) {
            this.grid = grid;
            this.bins = new double[grid.bins()];
        }

        /** Fold one day's exact sketch in at weight {@code w}. */
        void fold(MetricSketch other, double w) {
            if (!(other instanceof MetricHistogram h)) {
                throw new IllegalArgumentException("metric control folds histograms, not "
                        + other.getClass().getSimpleName());
            }
            h.foldScaledInto(bins, w);
            underflow += w * h.underflow();
            overflow += w * h.overflow();
            mass += w * h.count();
            massSq += w * w * h.count();
            clampedSumLog += w * h.meanLog() * h.count();
        }

        /**
         * {@inheritDoc}
         *
         * <p><b>Kish's effective sample size, not the raw total.</b> A control assembled from a fresh day
         * and three faded ones does not carry the sampling precision its raw sample count suggests, and
         * this number is read by {@link MetricDriftDetector#effectiveFloor} to set the bar — so reporting
         * the raw count would scale the noise floor as if the old days were as informative as today's and
         * hand back a bar that is too low. {@code (Σw)² / Σw²} is the standard correction, and it
         * degenerates to the plain count when every weight is equal.
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

        /** Both moments off the bin midpoints, for the reason {@link MetricHistogram#stdDevLog} gives. */
        @Override
        public double stdDevLog() {
            if (mass <= 0 || count() < 2) return 0.0;
            double lo = grid.logLo();
            double hi = grid.logHi();
            double width = grid.slotWidthLog();
            double sum = underflow * lo + overflow * hi;
            for (int i = 0; i < bins.length; i++) {
                if (bins[i] != 0) sum += bins[i] * (lo + (i + 0.5) * width);
            }
            double mean = sum / mass;

            double sumSq = underflow * sq(lo - mean) + overflow * sq(hi - mean);
            for (int i = 0; i < bins.length; i++) {
                if (bins[i] == 0) continue;
                sumSq += bins[i] * sq(lo + (i + 0.5) * width - mean);
            }
            return Math.sqrt(sumSq / mass);
        }

        private static double sq(double v) {
            return v * v;
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

        /** No writes: the ring is the accumulator and this is the view of it a comparison reads. */
        @Override
        public void add(double logValue) {
            throw new UnsupportedOperationException("a metric control is resolved from its ring, not added to");
        }

        @Override
        public void merge(MetricSketch other) {
            throw new UnsupportedOperationException("a metric control is resolved from its ring, not merged into");
        }

        /** Itself: with no mutator on this class, an independent copy and this instance are the same thing. */
        @Override
        public MetricSketch copy() {
            return this;
        }

        /**
         * Never persisted. {@code metric_baseline.control_json} holds the RING — the exact per-day
         * sketches — precisely so that a late verdict can retroactively drop a day; storing this view
         * instead would bake today's weights and today's exclusions into the table.
         */
        @Override
        public String toJson() {
            throw new UnsupportedOperationException(
                    "a metric control persists as its ring (MetricControl.toJson), never as a resolved view");
        }
    }
}
