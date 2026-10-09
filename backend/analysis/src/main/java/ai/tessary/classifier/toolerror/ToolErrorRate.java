// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One window of one tool's outcomes: how many calls, how many failed, and which failure patterns those
 * were. Design contract: {@code devdocs/concepts/tool-error.md} §2 and §10.
 *
 * <p><b>Two counters decide, the patterns explain.</b> Only {@link #calls()} and {@link #failures()}
 * reach {@link ToolErrorDetector}; the pattern tallies exist so that a finding can say <em>which</em>
 * failures moved the rate rather than only that it moved. Keeping the decision to two integers is what
 * makes the statistic reproducible from a corpus export — everything else is reporting.
 *
 * <p>A mutable accumulator, not a value: {@link #add} and {@link #addCounts} write in place, and
 * {@link #copy()} hands out an independent snapshot.
 *
 * <p><b>Bounded by construction.</b> A window can hold a week of a busy tool's traffic, so the pattern
 * map cannot be allowed to grow with the failure count. Past {@link #MAX_PATTERNS} distinct signatures
 * the tail folds into {@link #OTHER}, so the folding stays visible in the patterns themselves. A tool
 * emitting unboundedly many distinct signatures is itself the finding — silently truncating to the
 * first N would hide exactly that.
 */
public final class ToolErrorRate {

    /**
     * Distinct signatures kept before the tail folds. Fifty is far more than a healthy tool produces and
     * small enough that the blob stays readable by a human and by the Layer-2 triage agent.
     */
    public static final int MAX_PATTERNS = 50;

    /** Where the tail goes once {@link #MAX_PATTERNS} is reached. Never a real signature. */
    public static final String OTHER = "<other>";

    private long calls;
    private long failures;
    private final Map<String, Tally> patterns = new LinkedHashMap<>();

    /** An empty window: no calls, no failures, no patterns. */
    public ToolErrorRate() {
        // Accumulate through add/addCounts; there is no state to establish up front.
    }

    private ToolErrorRate(long calls, long failures, Map<String, Tally> patterns) {
        this.calls = calls;
        this.failures = failures;
        this.patterns.putAll(patterns);
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
     * has already alarmed (tool-error.md §5), so there is nothing to lose here by counting in bulk.
     *
     * @param failures clamped to {@code calls}: a bucket claiming more failures than calls is a query bug,
     *     and a rate above 1 would put the CUSUM's logs into the complex plane rather than failing loudly
     */
    public void addCounts(long calls, long failures) {
        long f = Math.max(0, Math.min(failures, calls));
        this.calls += Math.max(0, calls);
        this.failures += f;
    }

    /** An independent copy — writes to either afterwards do not touch the other. */
    public ToolErrorRate copy() {
        return new ToolErrorRate(calls, failures, patterns);
    }

    /** Tool calls in this window, failing and succeeding alike. The rate's denominator. */
    public long calls() {
        return calls;
    }

    /** Calls this window that {@link ToolFailure} recognized as failures. */
    public long failures() {
        return failures;
    }

    /**
     * The observed window's patterns, largest first, then by signature. There is no reference side, so
     * every pattern carries {@code ref = 0} and its delta is its whole count.
     */
    public static List<Pattern> ranked(ToolErrorRate cur, int limit) {
        Map<String, Pattern> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Tally> e : cur.patterns.entrySet()) {
            merged.put(
                    e.getKey(),
                    new Pattern(
                            e.getKey(), e.getValue().source(), 0, e.getValue().count()));
        }
        List<Pattern> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparingLong(Pattern::delta).reversed().thenComparing(Pattern::signature));
        return out.size() <= limit ? List.copyOf(out) : List.copyOf(out.subList(0, limit));
    }
}
