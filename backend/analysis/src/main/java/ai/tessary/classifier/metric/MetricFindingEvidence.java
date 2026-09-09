// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricControl.Resolved;
import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import ai.tessary.classifier.metric.MetricDriftDetector.Direction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/**
 * The two user-visible strings a metric-drift finding is made of: its {@code cause_key} and its
 * evidence blob (see {@code classifiers/metric_drift/PROGRAM.md} §6 and §7). Pure: a {@link Decision},
 * two sketches and two workloads in, JSON out, so the eval can print exactly what a finding would have
 * carried without standing up a schema.
 *
 * <p>The workload block matters because the correction loop's job is separating "the agent changed"
 * from "the traffic changed", and triage can only judge that from what the finding carries; it opens
 * no repository. A bare "1.4x slower" gives it nothing to rule on.
 *
 * <p>{@code cause_key} stays a stable, pasteable identifier even though {@link #title} carries the
 * headline: it is what queries and allowlist entries name, so {@link #directionWord} stays frozen
 * while {@link #directionPhrase} is free to be reworded.
 */
public final class MetricFindingEvidence {

    private MetricFindingEvidence() {}

    /**
     * {@code <measure>:<bucket_key>:<direction>:<reference>}, one row per cause, so a shift persisting
     * across twenty windows keeps one finding with a climbing {@code trace_count} rather than opening
     * twenty.
     *
     * <p>The reference is part of the key on purpose: a break against last week and a creep against the
     * last deploy are two different claims about the same bucket, and collapsing them would make the
     * finding's own evidence contradict its key.
     */
    public static String causeKey(String measure, String bucketKey, Decision decision) {
        return measure + ":" + bucketKey + ":" + directionWord(measure, decision.direction()) + ":"
                + decision.reference().wire();
    }

    /**
     * The direction as it appears in the cause key, never as prose a reader sees.
     *
     * <p>This token is persisted and must not change: it is a segment of {@code cause_key}, the
     * identity of a finding. If the spelling moved, every row, case, and allowlist entry keyed on it
     * would stop matching, and the next sweep would open a duplicate finding. That includes
     * {@code dearer}, which {@link #directionPhrase} no longer says out loud.
     */
    public static String directionWord(String measure, Direction direction) {
        boolean up = direction == Direction.UP;
        return switch (measure) {
            case Measure.COST, Measure.TOK_INPUT, Measure.TOK_OUTPUT, Measure.TOK_CACHE_READ, Measure.TOK_CACHE_WRITE ->
                up ? "dearer" : "cheaper";
            // The durations, and anything a later build adds: a measure whose units are time reads as
            // slower/faster, which is also the least wrong default for an unrecognized one.
            default -> up ? "slower" : "faster";
        };
    }

    /**
     * The direction as a reader sees it. Same distinction as {@link #directionWord}, opposite constraint:
     * this one is copy and may be reworded whenever the wording is wrong.
     *
     * <p>Cost reads "more expensive" rather than "dearer": the latter is British and was reaching users
     * who do not use the word.
     */
    public static String directionPhrase(String measure, Direction direction) {
        boolean up = direction == Direction.UP;
        return switch (measure) {
            case Measure.COST, Measure.TOK_INPUT, Measure.TOK_OUTPUT, Measure.TOK_CACHE_READ, Measure.TOK_CACHE_WRITE ->
                up ? "more expensive" : "cheaper";
            default -> up ? "slower" : "faster";
        };
    }

    /** A sentence, not a metric: the finding's title as prose, rendered in place of the raw key. */
    public static String title(String measure, String bucketKey, Decision decision) {
        return title(measure, bucketKey, decision.ratio(), decision.direction());
    }

    /**
     * The same sentence built from a shift already written down, reading the numbers back out of the
     * stored evidence ({@link #read}). Shared with the {@link Decision} overload rather than
     * reimplemented, so a case and the finding it came from can never disagree about the number.
     */
    public static String title(String measure, String bucketKey, double ratio, Direction direction) {
        String subject = Measure.TOOL_DURATION.equals(measure) ? "calls" : "turns";
        // Locale.ROOT: the sentence is product copy in English, so "1.40×" must not become "1,40×"
        // because the JVM happened to boot under a European locale. forbiddenapis bans the
        // default-locale overloads outright for this reason.
        return String.format(
                Locale.ROOT,
                "%s %s are %.2f× %s",
                bucketKey,
                subject,
                ratioAsMultiple(ratio),
                directionPhrase(measure, direction));
    }

    /**
     * The reported multiple, always {@code >= 1}. {@code e^W₁} is below 1 for a downward shift, and
     * "0.71× slower" is not a thing anyone says: the direction is already a word in the sentence, so
     * the number carries magnitude only.
     */
    private static double ratioAsMultiple(double ratio) {
        return ratio >= 1.0 || ratio <= 0 ? ratio : 1.0 / ratio;
    }

    /**
     * The stored blob, read back into the handful of numbers a downstream reader needs. The counterpart
     * of {@link #toJson} and deliberately in the same class: the writer and the reader of a persisted
     * shape that drift apart is a bug nothing type-checks, and here the two are eight lines from each
     * other.
     *
     * <p>Only what a case is built from is parsed: the quantile pairs, the token decomposition and the
     * suppressed shifts stay in the blob for a human and for the Layer-2 triage agent, which read the
     * JSON itself.
     *
     * @param refP50 the reference window's median in the measure's raw units (milliseconds, dollars),
     *     empty when that window was empty or the blob predates the field
     * @param curP50 the same for the window that just closed
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

    /**
     * Parse an evidence blob, or null when it cannot be read.
     *
     * <p>Null rather than an exception, and null rather than a zeroed {@link Read}: a caller handed
     * fabricated numbers would present them as the finding's own, whereas a caller handed null can show
     * the finding with the numbers marked unavailable instead.
     */
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

    /**
     * A {@code [then, now]} pair under its own name, with either side absent.
     *
     * <p>Nullable rather than {@link OptionalDouble} because this crosses the wire: a missing side means
     * the window had no reading, and a zero would be a measurement nobody took.
     */
    public record Pair(
            String key, @Nullable Double then, @Nullable Double now) {}

    /**
     * The whole blob, for the surface that renders a finding rather than headlines it.
     *
     * <p>Separate from {@link Read} on purpose: {@code Read} parses only the scalars a case's title and
     * severity need, while a chart needs the rest of the blob too (both quantile pairs, the workload
     * block, and the token decomposition).
     *
     * <p>The pair blocks are read generically, key by key, rather than against a fixed field list,
     * since new quantities get added to the writer over time and a fixed reader would silently drop
     * each one.
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
            @Nullable String windowClosedAt) {}

    /** Parse the blob for the detail surface, or null when it cannot be read. */
    public static @Nullable ShiftDetail detail(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MetricHistogram.JSON.readTree(json);
            String measure = root.path("measure").asText("");
            String bucketKey = root.path("bucket").path("key").asText("");
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
                    text(window.path("closed_at")));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Every {@code [then, now]} array on an object, in the order the writer put them. */
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

    /** One side of a {@code [then, now]} pair, empty where {@link #putPair} wrote a JSON null. */
    private static OptionalDouble pairSide(JsonNode pair, int index) {
        JsonNode side = pair.path(index);
        return side.isNumber() ? OptionalDouble.of(side.asDouble()) : OptionalDouble.empty();
    }

    /**
     * A shift at another grain that this finding accounts for, and that was therefore not written as a
     * finding of its own (PROGRAM.md §6.1). Today there is exactly one shape: a {@code turn_duration}
     * shift explained by the {@code tool_duration} shift of a tool inside that call site's traces.
     *
     * <p>Carrying it keeps suppression from being lossy: the tool row is the headline because it names
     * the fix, and the turn row it replaced stays here as the symptom, with its own reference, ratio,
     * and the share of the moved time this tool covers.
     *
     * @param covered the share of the suppressed shift's delta this finding's own delta accounts for,
     *     in absolute time. Values above 1 are ordinary and reported as measured, see
     *     {@link ai.tessary.classifier.metric.MetricSuppression.Explanation}.
     * @param refMillis the suppressed shift's reference median, in milliseconds
     * @param curMillis the suppressed shift's closed-window median, in milliseconds
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
     * The evidence blob of PROGRAM.md §7.
     *
     * @param ref the reference window's sketch: the previously closed window or the pinned one,
     *     whichever this decision was made against
     * @param cur the window that just closed
     * @param refWorkload the workload of the same window {@code ref} summarizes, or null when that
     *     window predates the workload columns. Absent rather than zeroed, since a workload of 0 would
     *     read as "users stopped typing".
     * @param refTokens what the reference window's dollars were made of, or null for every duration
     *     finding and any cost window whose traffic reported no usage
     * @param curTokens the same for the window that just closed. This pair is why {@code cost} is the
     *     only measure under {@code cost_drift} that opens a finding (PROGRAM.md §6.1): the token
     *     buckets decompose the shift here instead of opening four more rows.
     * @param windowKind {@code count} or {@code elapsed}, the close criterion that fired: a window
     *     closed on thin traffic and one closed on 500 samples in an hour support different amounts of
     *     belief
     * @param explains the shifts this finding suppressed, empty for the ordinary case. A list, not a
     *     single entry, because a tool bucket is not scoped by call site: one {@code search_docs}
     *     slowdown can explain the turn shift of every call site that calls it.
     * @param control how the rolling reference was composed, or null on the pinned arm. The rolling arm
     *     has this instead of {@code baseline} rows, since its reference is a weighted ring of per-day
     *     histograms with no window of rows to enumerate.
     */
    public static String toJson(
            String measure,
            String bucketKind,
            String bucketKey,
            Decision decision,
            MetricSketch ref,
            MetricSketch cur,
            @Nullable MetricWorkload refWorkload,
            @Nullable MetricWorkload curWorkload,
            @Nullable MetricTokens refTokens,
            @Nullable MetricTokens curTokens,
            @Nullable String sinceVersionId,
            @Nullable String windowOpenedAt,
            String windowClosedAt,
            String windowKind,
            List<Explained> explains,
            @Nullable Resolved control) {
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
        // The bar this comparison was actually held to, which on a thin window differs from the config
        // value (MetricDriftDetector#effectiveFloor). Printed beside the counts that produced it, so a
        // reader can see why 0.19 is a finding here and not there.
        root.put("floor", round(decision.floor(), 4));

        // Quantiles in the measure's raw units (milliseconds, dollars, tokens): the sketch's log space
        // is an implementation detail, and "p95 went from 9.0s to 21.4s" is the half of the finding a
        // human acts on. `[then, now]` throughout, so every pair in this blob reads the same way round.
        ObjectNode quantiles = root.putObject("quantiles");
        putPair(quantiles, "p50", rawQuantile(ref, 0.5), rawQuantile(cur, 0.5));
        putPair(quantiles, "p95", rawQuantile(ref, 0.95), rawQuantile(cur, 0.95));

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

        // The cost decomposition, written only when it exists. Its absence on a duration finding is the
        // accurate statement: turns are not made of tokens.
        //
        // A bucket the provider does not report comes back as [null, null] rather than [0, 0], which is
        // the point of tok_cache_write abstaining: a collapse in cache_read_pct beside flat output
        // tokens names a prompt-prefix regression that "3x dearer" alone would not show.
        if (refTokens != null || curTokens != null) {
            ObjectNode tokens = root.putObject("tokens");
            for (String quantity : MetricTokens.QUANTITIES) {
                putPair(tokens, quantity + "_p50", p50(refTokens, quantity), p50(curTokens, quantity));
            }
        }

        // What the rolling reference was made of. The pinned arm writes nothing here since its reference
        // is a window, enumerated as `baseline` evidence rows; this arm's reference is a weighted ring
        // of per-day histograms with no rows behind it, so the composition is reported instead. A reader
        // that finds neither `baseline` refs nor a `control` block is looking at a lost write, not
        // either arm behaving normally.
        if (control != null) {
            ObjectNode ring = root.putObject("control");
            ring.put("kind", "rolling");
            ring.put("days_used", control.daysUsed());
            // The number that explains why a long-running regression keeps firing instead of quietly
            // becoming its own reference.
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

        // Written only when it has members: an empty array on every ordinary finding would be noise for
        // the triage agent reading this blob, and absence already says this finding suppressed nothing.
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
                // Same [then, now] ordering as every other pair in this blob, in milliseconds: the scale
                // the suppression rule is decided on.
                putPair(node, "p50_ms", finite(e.refMillis()), finite(e.curMillis()));
                node.put("covered", round(e.covered(), 3));
            }
        }
        return root.toString();
    }

    /** A number as an {@code OptionalDouble}, empty when it is not one: an empty sketch's quantile. */
    private static OptionalDouble finite(double value) {
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    /**
     * A {@code [then, now]} pair, written as JSON nulls where a side has no number. An absent side is
     * the honest shape for a reference window written before the workload columns existed, and a reader
     * that finds null knows not to draw the comparison rather than drawing a wrong one.
     */
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

    /**
     * A sketch's quantile back in raw units (milliseconds, dollars, tokens), or empty for an empty
     * sketch.
     *
     * <p>Public because {@link ai.tessary.classifier.metric.MetricSuppression} decides in the same
     * units this blob prints; the two must not disagree about what a bucket's p50 is.
     */
    public static OptionalDouble rawQuantile(MetricSketch sketch, double q) {
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

    /**
     * Rounded before serialization, not after. These numbers are read by an LLM triage agent and by a
     * human, and a p95 rendered as {@code 21399.999999999996} costs tokens and attention to say
     * {@code 21400}.
     */
    private static double round(double value, int decimals) {
        if (!Double.isFinite(value)) return value;
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    /**
     * A quantile at a precision that survives the measure's units.
     *
     * <p>Two decimals is right for milliseconds and destroys dollars: a median turn costing $0.0047
     * rounds to {@code 0.0}, reporting a cost finding whose own median is zero. Fixed decimals cannot
     * serve both, since the blob holds raw units and the same field carries both measures.
     *
     * <p>Small values keep significant digits rather than decimal places; large ones keep the same
     * behavior the durations depend on.
     */
    private static double roundReading(double value) {
        if (!Double.isFinite(value) || value == 0) return value;
        double abs = Math.abs(value);
        if (abs >= 1) return round(value, 2);
        // Four significant digits below 1: enough for a sub-cent median, short of printing float noise.
        int decimals = (int) Math.ceil(-Math.log10(abs)) + 3;
        return round(value, Math.min(decimals, 12));
    }
}
