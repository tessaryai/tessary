// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricControl.Resolved;
import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * A metric-drift finding's {@code cause_key} and evidence blob (metric-drift.md §6, §7). Pure, so it is testable
 * without a schema. The workload block lets triage separate "the agent changed" from "the traffic changed".
 */
public final class MetricFindingEvidence {

    private MetricFindingEvidence() {}

    /**
     * One row per cause, so a persisting shift keeps one finding. The reference is part of the key: a break against
     * last week and a creep since a deploy are different claims.
     */
    public static String causeKey(String measure, String bucketKey, Decision decision) {
        return measure + ":" + bucketKey + ":" + directionWord(measure, decision.direction()) + ":"
                + decision.reference().wire();
    }

    /**
     * Persisted as a segment of {@code cause_key}: changing the spelling (including {@code dearer}) would open
     * duplicate findings and orphan cases and allowlist entries.
     */
    public static String directionWord(String measure, Direction direction) {
        boolean up = direction == Direction.UP;
        return switch (measure) {
            case Measure.COST, Measure.TOK_INPUT, Measure.TOK_OUTPUT, Measure.TOK_CACHE_READ, Measure.TOK_CACHE_WRITE ->
                up ? "dearer" : "cheaper";
            // Time units read slower/faster, the least wrong default for an unknown measure.
            default -> up ? "slower" : "faster";
        };
    }

    /** Copy, free to be reworded, unlike {@link #directionWord}. */
    public static String directionPhrase(String measure, Direction direction) {
        boolean up = direction == Direction.UP;
        return switch (measure) {
            case Measure.COST, Measure.TOK_INPUT, Measure.TOK_OUTPUT, Measure.TOK_CACHE_READ, Measure.TOK_CACHE_WRITE ->
                up ? "more expensive" : "cheaper";
            default -> up ? "slower" : "faster";
        };
    }

    public static String title(String measure, String bucketKey, double ratio, Direction direction) {
        String subject = Measure.TOOL_DURATION.equals(measure) ? "calls" : "turns";
        // Locale.ROOT so "1.40×" never becomes "1,40×"; forbiddenapis bans the default-locale overloads.
        return String.format(
                Locale.ROOT,
                "%s %s are %.2f× %s",
                bucketKey,
                subject,
                ratioAsMultiple(ratio),
                directionPhrase(measure, direction));
    }

    /** Always {@code >= 1}: the direction is already a word in the sentence. */
    private static double ratioAsMultiple(double ratio) {
        return ratio >= 1.0 || ratio <= 0 ? ratio : 1.0 / ratio;
    }

    /**
     * The reader of {@link #toJson}'s blob, kept in the same class so writer and reader cannot drift. Parses only what
     * a case is built from; the rest stays in the blob for humans and triage.
     */
    public record Read(
            String measure,
            String bucketKey,
            String reference,
            double w1Log,
            double ratio,
            Direction direction,
            long nCur,
            OptionalDouble refP50,
            OptionalDouble curP50) {}

    /** Null rather than a zeroed {@link Read}, so a caller never presents fabricated numbers as the finding's own. */
    public static @Nullable Read read(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MetricHistogram.JSON.readTree(json);
            JsonNode bucket = root.path("bucket");
            String measure = root.path("measure").asText("");
            String bucketKey = bucket.path("key").asText("");
            if (measure.isEmpty() || bucketKey.isEmpty()) return null;
            return new Read(
                    measure,
                    bucketKey,
                    root.path("reference").asText(""),
                    root.path("w1_log").asDouble(0),
                    root.path("ratio").asDouble(1),
                    "down".equals(root.path("direction").asText("up")) ? Direction.DOWN : Direction.UP,
                    root.path("n_cur").asLong(0),
                    pairSide(root.path("quantiles").path("p50"), 0),
                    pairSide(root.path("quantiles").path("p50"), 1));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Nullable because a missing side is a window with no reading, and a zero would be a measurement nobody took. */
    public record Pair(
            String key, @Nullable Double then, @Nullable Double now) {}

    /**
     * The whole blob for a chart. Pair blocks are read key by key, so quantities the writer adds later are not silently
     * dropped.
     */
    public record ShiftDetail(
            String measure,
            String bucketKey,
            String reference,
            String direction,
            double ratio,
            double w1Log,
            double floor,
            long nRef,
            long nCur,
            List<Pair> quantiles,
            List<Pair> workload,
            List<Pair> tokens,
            @Nullable String windowOpenedAt,
            @Nullable String windowClosedAt,
            String bucketKind,
            @Nullable String windowKind,
            /** Not "since this version"; see {@code MetricFindingEvidence.Explained}. */
            @Nullable String sinceVersionId,
            /** Null on the pinned arm, whose reference is enumerated as {@code baseline} evidence rows. */
            @Nullable Control control,
            List<Explains> explains,
            /** Null on a blob written before the mean existed. */
            @Nullable Pair mean,
            @Nullable PlainShift shift) {}

    /** Now minus then, in the measure's own units; a side with no reading leaves its difference null. */
    public record PlainShift(
            @Nullable Double mean,
            @Nullable Double p50,
            @Nullable Double p95) {}

    /**
     * Where inside the window the shift began. {@code index} counts window rows before {@code at}; {@code rowsBefore}
     * is how many of them were written as {@code baseline}.
     */
    public record ChangePoint(String at, String method, int index, int rowsBefore) {

        public static final String METHOD_CUSUM = "cusum";

        /** The window's rows carried no values, so {@code at} is the window open. */
        public static final String METHOD_UNAVAILABLE = "unavailable";
    }

    public record Control(
            int daysUsed,
            int daysExcludedAsConfirmed,
            @Nullable String oldestDay,
            double halfLifeDays,
            int retainDays) {}

    public record Explains(
            String measure,
            String bucketKind,
            String bucketKey,
            String reference,
            double w1Log,
            double ratio,
            String direction,
            long nCur,
            @Nullable Double refMillis,
            @Nullable Double curMillis,
            double covered) {}

    public static @Nullable ShiftDetail detail(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MetricHistogram.JSON.readTree(json);
            String measure = root.path("measure").asText("");
            JsonNode bucket = root.path("bucket");
            String bucketKey = bucket.path("key").asText("");
            if (measure.isEmpty() || bucketKey.isEmpty()) return null;
            JsonNode window = root.path("window");
            return new ShiftDetail(
                    measure,
                    bucketKey,
                    root.path("reference").asText(""),
                    root.path("direction").asText("up"),
                    root.path("ratio").asDouble(1),
                    root.path("w1_log").asDouble(0),
                    root.path("floor").asDouble(0),
                    root.path("n_ref").asLong(0),
                    root.path("n_cur").asLong(0),
                    pairs(root.path("quantiles")),
                    pairs(root.path("workload")),
                    pairs(root.path("tokens")),
                    text(window.path("opened_at")),
                    text(window.path("closed_at")),
                    bucket.path("kind").asText(""),
                    text(window.path("kind")),
                    text(root.path("since_version_id")),
                    control(root.path("control")),
                    explains(root.path("explains")),
                    mean(root.path("mean")),
                    plainShift(root.path("shift")));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static @Nullable Control control(JsonNode node) {
        if (!node.isObject()) return null;
        return new Control(
                node.path("days_used").asInt(0),
                node.path("days_excluded_as_confirmed").asInt(0),
                text(node.path("oldest_day")),
                node.path("half_life_days").asDouble(0),
                node.path("retain_days").asInt(0));
    }

    private static List<Explains> explains(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Explains> out = new java.util.ArrayList<>();
        for (JsonNode e : node) {
            JsonNode bucket = e.path("bucket");
            JsonNode p50Ms = e.path("p50_ms");
            out.add(new Explains(
                    e.path("measure").asText(""),
                    bucket.path("kind").asText(""),
                    bucket.path("key").asText(""),
                    e.path("reference").asText(""),
                    e.path("w1_log").asDouble(0),
                    e.path("ratio").asDouble(1),
                    e.path("direction").asText("up"),
                    e.path("n_cur").asLong(0),
                    number(p50Ms.path(0)),
                    number(p50Ms.path(1)),
                    e.path("covered").asDouble(0)));
        }
        return List.copyOf(out);
    }

    private static @Nullable Pair mean(JsonNode pair) {
        return pair.isArray() && pair.size() == 2 ? new Pair("mean", number(pair.get(0)), number(pair.get(1))) : null;
    }

    private static @Nullable PlainShift plainShift(JsonNode node) {
        if (!node.isObject()) return null;
        return new PlainShift(number(node.path("mean")), number(node.path("p50")), number(node.path("p95")));
    }

    private static List<Pair> pairs(JsonNode block) {
        if (!block.isObject()) return List.of();
        List<Pair> out = new java.util.ArrayList<>();
        block.properties().forEach(entry -> {
            JsonNode pair = entry.getValue();
            if (pair.isArray() && pair.size() == 2) {
                out.add(new Pair(entry.getKey(), number(pair.get(0)), number(pair.get(1))));
            }
        });
        return List.copyOf(out);
    }

    private static @Nullable Double number(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private static @Nullable String text(JsonNode node) {
        String s = node.asText("");
        return s.isEmpty() ? null : s;
    }

    private static OptionalDouble pairSide(JsonNode pair, int index) {
        JsonNode side = pair.path(index);
        return side.isNumber() ? OptionalDouble.of(side.asDouble()) : OptionalDouble.empty();
    }

    /**
     * A shift at another grain this finding accounts for, so it was not written as its own finding (§6.1), kept here so
     * suppression is not lossy. {@code covered} above 1 is ordinary and reported as measured.
     */
    public record Explained(
            String measure,
            String bucketKind,
            String bucketKey,
            Decision decision,
            double refMillis,
            double curMillis,
            double covered) {}

    /**
     * The evidence blob of metric-drift.md §7. A missing reference workload is absent, not zeroed, since 0 would read
     * as "users stopped typing". The token pairs decompose a cost shift instead of opening four more findings.
     * {@code explains} is a list because one tool's slowdown can explain several call sites' turn shifts.
     */
    public static String toJson(
            String measure,
            String bucketKind,
            String bucketKey,
            Decision decision,
            MetricReading ref,
            MetricReading cur,
            @Nullable MetricWorkload refWorkload,
            @Nullable MetricWorkload curWorkload,
            @Nullable MetricTokens refTokens,
            @Nullable MetricTokens curTokens,
            @Nullable String sinceVersionId,
            @Nullable String windowOpenedAt,
            String windowClosedAt,
            String windowKind,
            List<Explained> explains,
            @Nullable Resolved control,
            Grid grid,
            ChangePoint changepoint,
            @Nullable String referenceFrom) {
        ObjectNode root = MetricHistogram.JSON.createObjectNode();
        root.put("measure", measure);
        ObjectNode bucket = root.putObject("bucket");
        bucket.put("kind", bucketKind);
        bucket.put("key", bucketKey);
        root.put("reference", decision.reference().wire());
        root.put("w1_log", round(decision.w1Log(), 4));
        root.put("ratio", round(decision.ratio(), 4));
        root.put("direction", decision.direction().wire());
        root.put("n_ref", decision.nRef());
        root.put("n_cur", decision.nCur());
        // The bar actually held, which differs from config on a thin window (MetricDriftDetector#effectiveFloor).
        root.put("floor", round(decision.floor(), 4));

        // Raw units, `[then, now]` throughout: "p95 went from 9.0s to 21.4s" is what a human acts on.
        ObjectNode quantiles = root.putObject("quantiles");
        putPair(quantiles, "p50", rawQuantile(ref, 0.5), rawQuantile(cur, 0.5));
        putPair(quantiles, "p95", rawQuantile(ref, 0.95), rawQuantile(cur, 0.95));
        // Both sides off the sketch: the rolling control and older pinned blobs have no rows to average.
        OptionalDouble meanThen = rawMean(ref, grid);
        OptionalDouble meanNow = rawMean(cur, grid);
        putPair(root, "mean", meanThen, meanNow);
        ObjectNode shift = root.putObject("shift");
        putDifference(shift, "mean", meanThen, meanNow);
        putDifference(shift, "p50", rawQuantile(ref, 0.5), rawQuantile(cur, 0.5));
        putDifference(shift, "p95", rawQuantile(ref, 0.95), rawQuantile(cur, 0.95));

        ObjectNode workload = root.putObject("workload");
        putPair(
                workload,
                "input_tokens_p50",
                p50(refWorkload, MetricWorkload.INPUT_TOKENS),
                p50(curWorkload, MetricWorkload.INPUT_TOKENS));
        putPair(
                workload,
                "user_msg_chars_p50",
                p50(refWorkload, MetricWorkload.USER_MSG_CHARS),
                p50(curWorkload, MetricWorkload.USER_MSG_CHARS));
        putPair(
                workload,
                "prior_turns_p50",
                p50(refWorkload, MetricWorkload.PRIOR_TURNS),
                p50(curWorkload, MetricWorkload.PRIOR_TURNS));

        // Written only when it exists. An unreported bucket is [null, null], not [0, 0].
        if (refTokens != null || curTokens != null) {
            ObjectNode tokens = root.putObject("tokens");
            for (String quantity : MetricTokens.QUANTITIES) {
                putPair(tokens, quantity + "_p50", p50(refTokens, quantity), p50(curTokens, quantity));
            }
        }

        // The rolling arm has no rows behind its reference, so its composition is reported instead. Neither `baseline`
        // refs nor `control` means a lost write.
        if (control != null) {
            ObjectNode ring = root.putObject("control");
            ring.put("kind", "rolling");
            ring.put("days_used", control.daysUsed());
            // Why a long-running regression keeps firing instead of becoming its own reference.
            ring.put("days_excluded_as_confirmed", control.daysExcluded());
            ring.put("oldest_day", control.oldestDay());
            ring.put("half_life_days", MetricControl.HALF_LIFE_DAYS);
            ring.put("retain_days", MetricControl.RETAIN_DAYS);
        }

        root.put("since_version_id", sinceVersionId);
        ObjectNode window = root.putObject("window");
        window.put("opened_at", windowOpenedAt);
        window.put("closed_at", windowClosedAt);
        window.put("kind", windowKind);
        ObjectNode located = root.putObject("changepoint");
        located.put("at", changepoint.at());
        located.put("method", changepoint.method());
        located.put("index", changepoint.index());
        located.put("rows_before", changepoint.rowsBefore());
        root.put("reference_from", referenceFrom);

        // Absence already says this finding suppressed nothing.
        if (!explains.isEmpty()) {
            var array = root.putArray("explains");
            for (Explained e : explains) {
                ObjectNode node = array.addObject();
                node.put("measure", e.measure());
                ObjectNode suppressed = node.putObject("bucket");
                suppressed.put("kind", e.bucketKind());
                suppressed.put("key", e.bucketKey());
                node.put("reference", e.decision().reference().wire());
                node.put("w1_log", round(e.decision().w1Log(), 4));
                node.put("ratio", round(e.decision().ratio(), 4));
                node.put("direction", e.decision().direction().wire());
                node.put("n_cur", e.decision().nCur());
                putPair(node, "p50_ms", finite(e.refMillis()), finite(e.curMillis()));
                node.put("covered", round(e.covered(), 3));
            }
        }
        return root.toString();
    }

    private static OptionalDouble finite(double value) {
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    /** Null sides, so a reader skips the comparison rather than drawing a wrong one. */
    private static void putPair(ObjectNode into, String field, OptionalDouble then, OptionalDouble now) {
        var pair = into.putArray(field);
        if (then.isPresent()) {
            pair.add(roundReading(then.getAsDouble()));
        } else {
            pair.addNull();
        }
        if (now.isPresent()) {
            pair.add(roundReading(now.getAsDouble()));
        } else {
            pair.addNull();
        }
    }

    private static void putDifference(ObjectNode into, String field, OptionalDouble then, OptionalDouble now) {
        if (then.isPresent() && now.isPresent()) {
            into.put(field, roundReading(now.getAsDouble() - then.getAsDouble()));
        } else {
            into.putNull(field);
        }
    }

    /**
     * The count-weighted mean of raw values off the bins, with the same edge treatment as {@code stdDevLog} and
     * {@code quantile}: underflow at the floor, each bin at its midpoint, overflow at the ceiling.
     */
    public static OptionalDouble rawMean(MetricReading sketch, Grid grid) {
        if (sketch.count() == 0) return OptionalDouble.empty();
        double[] cdf = sketch.cdf();
        double sum = cdf[0] * Math.exp(grid.logLo());
        for (int i = 0; i < grid.bins(); i++) {
            double mass = cdf[i + 1] - cdf[i];
            if (mass != 0) sum += mass * Math.exp(grid.logLo() + (i + 0.5) * grid.slotWidthLog());
        }
        sum += (cdf[grid.bins() + 1] - cdf[grid.bins()]) * Math.exp(grid.logHi());
        return OptionalDouble.of(sum);
    }

    /**
     * Public because {@link ai.tessary.classifier.metric.MetricSuppression} must decide in the same units this blob
     * prints.
     */
    public static OptionalDouble rawQuantile(MetricReading sketch, double q) {
        if (sketch.count() == 0) return OptionalDouble.empty();
        Double logValue = sketch.quantile(q);
        return logValue == null ? OptionalDouble.empty() : OptionalDouble.of(Math.exp(logValue));
    }

    private static OptionalDouble p50(@Nullable MetricWorkload workload, String quantity) {
        return workload == null ? OptionalDouble.empty() : workload.p50(quantity);
    }

    private static OptionalDouble p50(@Nullable MetricTokens tokens, String quantity) {
        return tokens == null ? OptionalDouble.empty() : tokens.p50(quantity);
    }

    /** Rounded before serialization: an LLM and a human read these. */
    private static double round(double value, int decimals) {
        if (!Double.isFinite(value)) return value;
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    /**
     * Fixed decimals cannot serve both units: two decimals turns a $0.0047 median into 0.0. Small values keep
     * significant digits instead.
     */
    private static double roundReading(double value) {
        if (!Double.isFinite(value) || value == 0) return value;
        double abs = Math.abs(value);
        if (abs >= 1) return round(value, 2);
        // Four significant digits below 1.
        int decimals = (int) Math.ceil(-Math.log10(abs)) + 3;
        return round(value, Math.min(decimals, 12));
    }
}
