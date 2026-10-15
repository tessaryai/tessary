// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One window of one tool's outcomes ({@code devdocs/concepts/tool-error.md} §2, §10). Only {@link #calls()} and
 * {@link #failures()} decide; the patterns explain. Past {@link #MAX_PATTERNS} the tail folds into {@link #OTHER}
 * visibly, since a tool emitting unboundedly many signatures is itself the finding.
 */
public final class ToolErrorRate {

    /** Small enough that the blob stays readable by a human and by the triage agent. */
    public static final int MAX_PATTERNS = 50;

    /** Where the tail goes once {@link #MAX_PATTERNS} is reached. Never a real signature. */
    public static final String OTHER = "<other>";

    private long calls;
    private long failures;
    private final Map<String, Tally> patterns = new LinkedHashMap<>();

    public ToolErrorRate() {}

    private ToolErrorRate(long calls, long failures, Map<String, Tally> patterns) {
        this.calls = calls;
        this.failures = failures;
        this.patterns.putAll(patterns);
    }

    public record Tally(long count, String source) {}

    public record Pattern(String signature, String source, long ref, long cur) {

        /** The ranking key; may be negative. */
        public long delta() {
            return cur - ref;
        }
    }

    /** @param failure null when the call succeeded; a success still counts toward {@link #calls()} */
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
     * The bulk path the hourly replay uses; per-call folding would be unaffordable for busy tools. @param failures
     * clamped to {@code calls}: a rate above 1 would put the CUSUM's logs into the complex plane
     */
    public void addCounts(long calls, long failures) {
        long f = Math.max(0, Math.min(failures, calls));
        this.calls += Math.max(0, calls);
        this.failures += f;
    }

    public ToolErrorRate copy() {
        return new ToolErrorRate(calls, failures, patterns);
    }

    public long calls() {
        return calls;
    }

    public long failures() {
        return failures;
    }

    /** No reference side, so every pattern carries {@code ref = 0}. */
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
