// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One window of one tool's outcomes: how many calls, how many failed, and which failure patterns those
 * were. The payload behind a {@code metric_baseline} row whose {@code measure} is
 * {@code tool_error_rate}. Design contract: {@code classifiers/tool_error/PROGRAM.md} §2 and §10.
 *
 * <p><b>Two counters decide, the patterns explain.</b> Only {@link #calls()} and {@link #failures()}
 * reach {@link ToolErrorDetector}; the pattern tallies exist so that a finding can say <em>which</em>
 * failures moved the rate rather than only that it moved. Keeping the decision to two integers is what
 * makes the statistic reproducible from a corpus export — everything else is reporting.
 *
 * <p>A mutable accumulator, not a value: {@link #add} and {@link #merge} write in place, and
 * {@link #copy()} is what the window roll (current → prev) needs. That mirrors {@code MetricSketch},
 * whose lifecycle this shares even though its arithmetic has nothing in common.
 *
 * <p><b>Bounded by construction.</b> A window can hold a week of a busy tool's traffic, so the pattern
 * map cannot be allowed to grow with the failure count. Past {@link #MAX_PATTERNS} distinct signatures
 * the tail folds into {@link #OTHER}, and {@link #distinctPatterns()} keeps counting so a reader can see
 * that folding happened. A tool emitting unboundedly many distinct signatures is itself the finding —
 * silently truncating to the first N would hide exactly that.
 */
public final class ToolErrorRate {

    /** The persisted {@code kind} discriminator, matching {@code MetricSketch}'s convention. */
    public static final String KIND = "tool_error_rate";

    /**
     * Distinct signatures kept before the tail folds. Fifty is far more than a healthy tool produces and
     * small enough that the blob stays readable by a human and by the Layer-2 triage agent.
     */
    public static final int MAX_PATTERNS = 50;

    /** Where the tail goes once {@link #MAX_PATTERNS} is reached. Never a real signature. */
    public static final String OTHER = "<other>";

    static final ObjectMapper JSON = new ObjectMapper();

    private long calls;
    private long failures;
    private final Map<String, Tally> patterns = new LinkedHashMap<>();
    private int distinctPatterns;

    /** An empty window: no calls, no failures, no patterns. */
    public ToolErrorRate() {
        // Accumulate through add/addCounts/merge; there is no state to establish up front.
    }

    private ToolErrorRate(long calls, long failures, Map<String, Tally> patterns, int distinctPatterns) {
        this.calls = calls;
        this.failures = failures;
        this.patterns.putAll(patterns);
        this.distinctPatterns = distinctPatterns;
    }

    /** One failure's tally: how many, and which rule recognized them. */
    public record Tally(long count, String source) {}

    /** One pattern as a finding reports it — its counts in both windows, ranked by what changed. */
    public record Pattern(String signature, String source, long ref, long cur) {

        /** What this pattern contributed to the move. The ranking key, and it may be negative. */
        public long delta() {
            return cur - ref;
        }
    }

    /**
     * Fold one tool call in.
     *
     * @param failure null when the call succeeded — a success still counts toward {@link #calls()},
     *     because it is the denominator and a rate assembled without it is not a rate.
     */
    public void add(ToolFailure.@org.jspecify.annotations.Nullable Recognized failure) {
        calls++;
        if (failure == null) return;
        failures++;
        String key = patterns.containsKey(failure.signature()) || patterns.size() < MAX_PATTERNS
                ? failure.signature()
                : OTHER;
        if (!patterns.containsKey(key)) distinctPatterns++;
        Tally prior = patterns.get(key);
        patterns.put(
                key,
                new Tally(
                        prior == null ? 1 : prior.count() + 1,
                        prior == null ? failure.source().wire() : prior.source()));
    }

    /**
     * Fold a whole bucket's counts in at once, without per-call signatures.
     *
     * <p>The bulk path, and the only one the hourly replay uses. A busy tool over a month is millions of
     * calls, so a loop that folded them one at a time would make a recompute-per-read design unaffordable
     * for exactly the tools most worth watching. Signatures are read separately and only for a tool that
     * has already alarmed (PROGRAM.md §5), so there is nothing to lose here by counting in bulk.
     *
     * @param failures clamped to {@code calls}: a bucket claiming more failures than calls is a query bug,
     *     and a rate above 1 would put the CUSUM's logs into the complex plane rather than failing loudly
     */
    public void addCounts(long calls, long failures) {
        long f = Math.max(0, Math.min(failures, calls));
        this.calls += Math.max(0, calls);
        this.failures += f;
    }

    /** Fold another window's counts in, in place. Exact: a window assembled from pages equals one pass. */
    public void merge(ToolErrorRate other) {
        calls += other.calls;
        failures += other.failures;
        for (Map.Entry<String, Tally> e : other.patterns.entrySet()) {
            String key = patterns.containsKey(e.getKey()) || patterns.size() < MAX_PATTERNS ? e.getKey() : OTHER;
            if (!patterns.containsKey(key)) distinctPatterns++;
            Tally prior = patterns.get(key);
            patterns.put(
                    key,
                    new Tally(
                            prior == null
                                    ? e.getValue().count()
                                    : prior.count() + e.getValue().count(),
                            prior == null ? e.getValue().source() : prior.source()));
        }
    }

    /** An independent copy — writes to either afterwards do not touch the other. */
    public ToolErrorRate copy() {
        return new ToolErrorRate(calls, failures, patterns, distinctPatterns);
    }

    /** Tool calls in this window, failing and succeeding alike. The rate's denominator. */
    public long calls() {
        return calls;
    }

    /** Calls this window that {@link ToolFailure} recognized as failures. */
    public long failures() {
        return failures;
    }

    /** The failure rate in [0, 1]. Zero for an empty window, which is honest: nothing failed. */
    public double rate() {
        return calls == 0 ? 0.0 : (double) failures / calls;
    }

    /** Distinct signatures SEEN, including any folded into {@link #OTHER}. */
    public int distinctPatterns() {
        return distinctPatterns;
    }

    /** Whether the tail was folded — stated rather than inferred, so a truncated list never reads as whole. */
    public boolean folded() {
        return patterns.containsKey(OTHER);
    }

    /** This window's tallies, by signature. */
    public Map<String, Tally> patterns() {
        return Map.copyOf(patterns);
    }

    /**
     * The two windows' patterns aligned and ranked by what CHANGED, largest rise first.
     *
     * <p>Ranked on the delta rather than the current count on purpose: a tool whose timeouts held steady
     * at 400 while its 5xx went from 2 to 40 has one cause, and a list headed by the timeouts would name
     * the wrong one. A pattern present in only one window contributes zero on the other side, so a
     * failure mode that appeared from nothing ranks by its whole size — which is what it deserves.
     */
    public static List<Pattern> ranked(ToolErrorRate ref, ToolErrorRate cur, int limit) {
        Map<String, Pattern> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Tally> e : cur.patterns.entrySet()) {
            merged.put(
                    e.getKey(),
                    new Pattern(
                            e.getKey(), e.getValue().source(), 0, e.getValue().count()));
        }
        for (Map.Entry<String, Tally> e : ref.patterns.entrySet()) {
            Pattern p = merged.get(e.getKey());
            merged.put(
                    e.getKey(),
                    p == null
                            ? new Pattern(
                                    e.getKey(),
                                    e.getValue().source(),
                                    e.getValue().count(),
                                    0)
                            : new Pattern(
                                    p.signature(), p.source(), e.getValue().count(), p.cur()));
        }
        List<Pattern> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparingLong(Pattern::delta).reversed().thenComparing(Pattern::signature));
        return out.size() <= limit ? List.copyOf(out) : List.copyOf(out.subList(0, limit));
    }

    /** Serialized form for {@code metric_baseline.*_sketch_json}. Round-trips through {@link #fromJson}. */
    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", KIND);
        root.put("calls", calls);
        root.put("failures", failures);
        root.put("distinct_patterns", distinctPatterns);
        ObjectNode pat = root.putObject("patterns");
        for (Map.Entry<String, Tally> e : patterns.entrySet()) {
            ObjectNode t = pat.putObject(e.getKey());
            t.put("n", e.getValue().count());
            t.put("src", e.getValue().source());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Every value here is a long or a String this class produced; unreachable short of a bug.
            throw new IllegalStateException("tool error rate failed to serialize", e);
        }
    }

    /** Rehydrate a window written by {@link #toJson()}. */
    public static ToolErrorRate fromJson(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            String kind = root.path("kind").asText("");
            if (!KIND.equals(kind)) {
                throw new IllegalArgumentException("not a tool error rate sketch: kind=" + kind);
            }
            Map<String, Tally> patterns = new LinkedHashMap<>();
            JsonNode pat = root.path("patterns");
            pat.fieldNames().forEachRemaining(name -> {
                JsonNode t = pat.path(name);
                patterns.put(
                        name, new Tally(t.path("n").asLong(0), t.path("src").asText("")));
            });
            return new ToolErrorRate(
                    root.path("calls").asLong(0),
                    root.path("failures").asLong(0),
                    patterns,
                    root.path("distinct_patterns").asInt(patterns.size()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed tool error rate json", e);
        }
    }
}
