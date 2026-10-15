// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricSourceRepository.LeafUsage;
import ai.tessary.classifier.metric.MetricSourceRepository.ToolSpanFacts;
import ai.tessary.classifier.metric.MetricSourceRepository.TurnFacts;
import ai.tessary.classifier.substrate.ActionSymbol;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository.TraceHead;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.vitals.TokenUsage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one accessor every metric-drift measure is read through: column-preferred, derived otherwise, and an explicit
 * counted abstention when neither can answer, never a null or a zero (an unpriced model read as $0 would look like a
 * cost improvement). The bucket key comes on the {@link TraceHead} so the call site is never re-derived here. Reads are
 * batched per page.
 */
@Component
public class MetricSource {

    private final MetricSourceRepository repository;
    private final ModelResolver prices;

    public MetricSource(MetricSourceRepository repository, ModelResolver prices) {
        this.repository = repository;
        this.prices = prices;
    }

    /** Counted beside the abstentions so "did the rollup backfill land" needs no query. */
    public enum Provenance {
        COLUMN,
        DERIVED
    }

    /** Deliberately small: each reason is a distinct thing an operator would DO about it. */
    public enum Absence {
        /** No end: a root span that never ended, a tool span still running, or a span shipped without a start. */
        NO_END_TIME,

        /**
         * {@code ended_at} before {@code started_at}, routine clock skew. Load-bearing: {@code log(-3)} is NaN,
         * {@link MetricSketch#add} throws on NaN, and one skewed span would dead-letter every metric measure for the
         * project.
         */
        NEGATIVE_INTERVAL,

        /**
         * A generation has no recorded cost, so the turn leaves the distribution rather than joining it at $0. Never
         * repaired retroactively: dollars are recorded at write time.
         */
        UNPRICED_MODEL,

        /**
         * The provider never reported this quantity, which is not a zero. Whether cache writes are counted is per model
         * ({@link ModelResolver#reportedModelBillsCacheCreation}); recording zero would show a fake collapse when a
         * call site switches provider.
         */
        BUCKET_NOT_REPORTED
    }

    /**
     * Counted, not filtered. {@link #NO_ROOT_SPAN} is a trace still arriving (its root ships last) and is what
     * {@code MetricDriftSweep.admissibleThrough} holds a page on; it relies on {@code MetricSourceRepository.turnFacts}
     * never counting a not-yet-adopted child as a root.
     */
    public enum Completion {
        COMPLETED,
        UNTERMINATED,
        NO_ROOT_SPAN
    }

    /** Sealed rather than a nullable double, so a caller cannot forget the absent case. */
    public sealed interface Measurement {

        /** Raw units: ms for durations, USD for cost, tokens for the buckets. */
        record Present(double value, Provenance provenance) implements Measurement {

            /**
             * Zero gives {@code -inf} and lands in the sketch's underflow counter by design: zero cache-read tokens is
             * the collapse this program exists to catch.
             */
            public double logValue() {
                return Math.log(value);
            }
        }

        record Absent(Absence reason) implements Measurement {}

        static Measurement present(double value, Provenance provenance) {
            return new Present(value, provenance);
        }

        static Measurement absent(Absence reason) {
            return new Absent(reason);
        }

        default boolean isPresent() {
            return this instanceof Present;
        }
    }

    /**
     * Everything the turn-grain measures produce for one trace. {@code callSiteId} is the ENTRY POINT's call site, the
     * bucket key. {@code measurements} holds every measure of this grain, absent rather than missing.
     */
    public record TurnMetrics(
            String callSiteId,
            Completion completion,
            @Nullable TokenUsage tokens,
            Map<String, Measurement> measurements,
            Workload workload) {

        /** An unknown measure name reads as not reported rather than throwing. */
        public Measurement measurement(String measure) {
            return measurements.getOrDefault(measure, Measurement.absent(Absence.BUCKET_NOT_REPORTED));
        }

        /**
         * {@code cache_read / (cache_read + input)}: a prompt-prefix edit that stops the cache hitting reads as a
         * collapse from ~0.8 to ~0.0, which the raw count hides behind traffic volume. No prompt means no ratio, not
         * zero.
         */
        /**
         * Null is NOT MEASURED, never zero. The share is in PERCENT for the sketch's geometric bins
         * ({@link MetricTokens#CACHE_READ_PCT}); {@link #cacheReadRatio()} stays a fraction.
         */
        public TokenReadings tokenReadings() {
            return new TokenReadings(
                    raw(measurement(Measure.TOK_INPUT)),
                    raw(measurement(Measure.TOK_OUTPUT)),
                    raw(measurement(Measure.TOK_CACHE_READ)),
                    raw(measurement(Measure.TOK_CACHE_WRITE)),
                    pct(cacheReadRatio()));
        }

        private static @Nullable Double raw(Measurement measurement) {
            return measurement instanceof Measurement.Present p ? p.value() : null;
        }

        private static @Nullable Double pct(Measurement measurement) {
            return measurement instanceof Measurement.Present p ? p.value() * 100.0 : null;
        }

        public Measurement cacheReadRatio() {
            TokenUsage summed = tokens;
            if (summed == null) return Measurement.absent(Absence.BUCKET_NOT_REPORTED);
            if (!measurement(Measure.TOK_INPUT).isPresent()
                    || !measurement(Measure.TOK_CACHE_READ).isPresent()) {
                return Measurement.absent(Absence.BUCKET_NOT_REPORTED);
            }
            double prompt = (double) summed.inputTokens() + summed.cacheReadTokens();
            if (prompt <= 0) return Measurement.absent(Absence.BUCKET_NOT_REPORTED);
            return Measurement.present(summed.cacheReadTokens() / prompt, Provenance.DERIVED);
        }
    }

    public record TokenReadings(
            @Nullable Double input,
            @Nullable Double output,
            @Nullable Double cacheRead,
            @Nullable Double cacheWrite,
            @Nullable Double cacheReadPct) {}

    /**
     * Null is unknown, never zero: folding an instrumentation gap in as zero would manufacture a workload collapse.
     * {@code inputTokens} is the same {@link TokenUsage} sum {@code tok_input} reads.
     */
    public record Workload(
            @Nullable Double inputTokens,
            @Nullable Double userMsgChars,
            @Nullable Double priorTurns) {

        /** For a subject that has no workload of its own, such as a tool span. */
        public static final Workload NONE = new Workload(null, null, null);
    }

    /**
     * {@code callSiteId} is the entry point of the TURN this span belongs to, not the bucket key, for the §6.1
     * suppression rule. {@code bucketKey} is an {@link ActionSymbol} so latency and drift name a tool the same way.
     */
    public record ToolMetrics(
            String traceId,
            String observationId,
            String callSiteId,
            String bucketKey,
            String eventAt,
            Measurement duration) {}

    /**
     * One {@link TurnMetrics} per head, in head order, from two queries per page. A head whose trace was deleted still
     * comes back with every measure absent, because a page that silently shrinks lets the cursor skip unmeasured
     * traffic. Settling is the caller's business.
     */
    public List<TurnMetrics> turnMetrics(String projectId, List<TraceHead> heads, Tally tally) {
        List<String> traceIds = heads.stream().map(TraceHead::traceId).toList();

        Map<String, TurnFacts> facts = new HashMap<>();
        for (TurnFacts f : repository.turnFacts(projectId, traceIds)) {
            facts.put(f.traceId(), f);
        }
        Map<String, List<LeafUsage>> leaves = new HashMap<>();
        for (LeafUsage leaf : repository.leafUsage(projectId, traceIds)) {
            leaves.computeIfAbsent(leaf.traceId(), k -> new ArrayList<>()).add(leaf);
        }

        List<TurnMetrics> out = new ArrayList<>(heads.size());
        for (TraceHead head : heads) {
            TurnFacts f = facts.get(head.traceId());
            Completion completion = completionOf(f);
            Spend spend = spendOf(leaves.getOrDefault(head.traceId(), List.of()));

            Map<String, Measurement> measurements = new LinkedHashMap<>();
            measurements.put(Measure.TURN_DURATION, turnDuration(f, completion));
            measurements.put(Measure.COST, cost(f, spend));
            measurements.putAll(spend.buckets());

            tally.observed(completion);
            measurements.forEach(tally::observed);
            out.add(new TurnMetrics(
                    head.callSiteId(),
                    completion,
                    spend.tokens(),
                    Map.copyOf(measurements),
                    workloadOf(f, measurements)));
        }
        return out;
    }

    private static Workload workloadOf(@Nullable TurnFacts f, Map<String, Measurement> measurements) {
        if (f == null) return Workload.NONE;
        Double inputTokens = measurements.get(Measure.TOK_INPUT) instanceof Measurement.Present p ? p.value() : null;
        Long userMsgChars = f.userMsgChars();
        Long priorTurns = f.priorTurns();
        return new Workload(
                inputTokens,
                userMsgChars == null ? null : (double) userMsgChars,
                priorTurns == null ? null : (double) priorTurns);
    }

    private static Completion completionOf(@Nullable TurnFacts f) {
        if (f == null || !f.hasRoot()) return Completion.NO_ROOT_SPAN;
        return f.rootUnterminated() ? Completion.UNTERMINATED : Completion.COMPLETED;
    }

    /**
     * The ROOT span's own {@code ended_at - started_at}, never the trace envelope and never {@code trace.latency_ms}
     * (generated from that envelope): async children outlive their parent and inflated p95 by ~10%. The root span is
     * what Jaeger, Tempo and Datadog report.
     */
    private static Measurement turnDuration(@Nullable TurnFacts f, Completion completion) {
        if (f == null) return Measurement.absent(Absence.NO_END_TIME);
        Double derived = f.rootMillis();
        // Both are read so the value and the counted category can never disagree.
        if (derived == null || completion != Completion.COMPLETED) {
            return Measurement.absent(Absence.NO_END_TIME);
        }
        // Its own category: a skewed clock needs different action from a span that never ended.
        if (derived < 0) return Measurement.absent(Absence.NEGATIVE_INTERVAL);
        return Measurement.present(derived, Provenance.DERIVED);
    }

    /**
     * {@code trace.total_cost} unless it is negative or the trace has {@code unpriced_spans > 0}: SUM skips nulls, so a
     * price-book gap would read as a cost improvement. Falling through, {@link #spendOf} abstains instead.
     */
    private static Measurement cost(@Nullable TurnFacts f, Spend spend) {
        if (f == null) return spend.cost();
        Integer unpriced = f.unpricedSpans();
        if (unpriced != null && unpriced > 0) return spend.cost();
        BigDecimal column = f.rollupTotalCost();
        if (column != null && column.signum() >= 0) {
            return Measurement.present(column.doubleValue(), Provenance.COLUMN);
        }
        return spend.cost();
    }

    private record Spend(
            Measurement cost,
            Map<String, Measurement> buckets,
            @Nullable TokenUsage tokens) {}

    /**
     * Off typed columns, already made disjoint by {@code IngestPricer}. Null means unreported and a stored 0 is real: a
     * cache-read count falling to zero is the regression this exists to catch. Cache writes also need the model to bill
     * cache creation. One unpriced leaf abstains the whole turn rather than reporting a partial sum.
     */
    private Spend spendOf(List<LeafUsage> leaves) {
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheReadTokens = 0;
        long cacheWriteTokens = 0;
        BigDecimal usd = BigDecimal.ZERO;
        boolean anyReported = false;
        boolean unpriced = false;
        boolean reportsInput = false;
        boolean reportsOutput = false;
        boolean reportsCacheRead = false;
        boolean reportsCacheWrite = false;

        for (LeafUsage leaf : leaves) {
            anyReported = true;
            // Each bucket is read ONCE into a local: a null check then an unbox are two reads of a @Nullable accessor.
            Long in = leaf.inputTokens();
            Long out = leaf.outputTokens();
            Long cacheRead = leaf.cacheReadTokens();
            Long cacheWrite = leaf.cacheWriteTokens();
            if (in != null) {
                reportsInput = true;
                inputTokens += in;
            }
            if (out != null) {
                reportsOutput = true;
                outputTokens += out;
            }
            if (cacheRead != null) {
                reportsCacheRead = true;
                cacheReadTokens += cacheRead;
            }
            if (cacheWrite != null) {
                cacheWriteTokens += cacheWrite;
                reportsCacheWrite |= prices.reportedModelBillsCacheCreation(leaf.model());
            }
            // The price recorded at ingest is the ONLY source of dollars; there is deliberately no re-pricing.
            // Read-time pricing moved every bucket against its reference at a rate refresh and read as a fleet-wide
            // regression.
            BigDecimal stored = leaf.totalCost();
            if (leaf.unpriced() || stored == null || stored.signum() < 0) {
                unpriced = true;
            } else {
                usd = usd.add(stored);
            }
        }

        Map<String, Measurement> buckets = new LinkedHashMap<>();
        buckets.put(Measure.TOK_INPUT, bucket(reportsInput, inputTokens));
        buckets.put(Measure.TOK_OUTPUT, bucket(reportsOutput, outputTokens));
        buckets.put(Measure.TOK_CACHE_READ, bucket(reportsCacheRead, cacheReadTokens));
        buckets.put(Measure.TOK_CACHE_WRITE, bucket(reportsCacheWrite, cacheWriteTokens));

        Measurement cost;
        if (!anyReported) {
            cost = Measurement.absent(Absence.BUCKET_NOT_REPORTED);
        } else if (unpriced) {
            cost = Measurement.absent(Absence.UNPRICED_MODEL);
        } else {
            cost = Measurement.present(usd.doubleValue(), Provenance.COLUMN);
        }
        TokenUsage summedTokens =
                anyReported ? new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens) : null;
        return new Spend(cost, buckets, summedTokens);
    }

    /**
     * Reported zero is a value, and the most valuable one: a cache-read count falling to zero IS the prompt-prefix
     * regression.
     */
    private static Measurement bucket(boolean reported, long tokens) {
        return reported
                ? Measurement.present(tokens, Provenance.DERIVED)
                : Measurement.absent(Absence.BUCKET_NOT_REPORTED);
    }

    /**
     * One query per page. Failures stay in the tool's latency population, so a tool that starts failing fast reads as a
     * shift. Running spans abstain rather than being filtered, so a tool that increasingly hangs does not read as fewer
     * fast calls.
     */
    public List<ToolMetrics> toolMetrics(String projectId, List<TraceHead> heads, Tally tally) {
        List<String> traceIds = heads.stream().map(TraceHead::traceId).toList();
        Map<String, String> callSites = new HashMap<>();
        for (TraceHead head : heads) {
            callSites.put(head.traceId(), head.callSiteId());
        }

        List<ToolSpanFacts> spans = repository.toolSpanFacts(projectId, traceIds);
        List<ToolMetrics> out = new ArrayList<>(spans.size());
        for (ToolSpanFacts span : spans) {
            Measurement duration = toolDuration(span);
            tally.observed(Measure.TOOL_DURATION, duration);
            out.add(new ToolMetrics(
                    span.traceId(),
                    span.observationId(),
                    // Unreachable, but a stray span's scope genuinely is unattributed.
                    callSites.getOrDefault(span.traceId(), BehaviorSubstrateRepository.UNATTRIBUTED),
                    ActionSymbol.of(span.kind(), span.name()),
                    span.eventAt(),
                    duration));
        }
        return out;
    }

    /**
     * Column-preferred like the turn grain, with the backwards-interval guard; see {@link Absence#NEGATIVE_INTERVAL}.
     */
    private static Measurement toolDuration(ToolSpanFacts span) {
        Long column = span.rollupLatencyMs();
        if (column != null && column >= 0) return Measurement.present(column, Provenance.COLUMN);
        Double derived = span.spanMillis();
        if (derived == null || span.unterminated()) return Measurement.absent(Absence.NO_END_TIME);
        if (derived < 0) return Measurement.absent(Absence.NEGATIVE_INTERVAL);
        return Measurement.present(derived, Provenance.DERIVED);
    }

    /**
     * Per-measure counts of where a pass's values came from and why they abstained: the only thing that distinguishes a
     * measure abstaining on all traffic from a quiet week. Not thread-safe; one Tally per pass.
     */
    public static final class Tally {

        private final Map<String, Counts> byMeasure = new LinkedHashMap<>();
        private final Map<Completion, Long> completions = new EnumMap<>(Completion.class);

        public void observed(String measure, Measurement measurement) {
            Counts counts = byMeasure.computeIfAbsent(measure, k -> new Counts());
            switch (measurement) {
                case Measurement.Present p -> {
                    if (p.provenance() == Provenance.COLUMN) counts.fromColumn++;
                    else counts.derived++;
                }
                case Measurement.Absent a -> counts.absent.merge(a.reason(), 1L, Long::sum);
            }
        }

        public void observed(Completion completion) {
            completions.merge(completion, 1L, Long::sum);
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            byMeasure.forEach((measure, counts) -> {
                if (sb.length() > 0) sb.append("; ");
                sb.append(measure)
                        .append(" n=")
                        .append(counts.fromColumn + counts.derived)
                        .append(" (column ")
                        .append(counts.fromColumn)
                        .append(", derived ")
                        .append(counts.derived)
                        .append(')');
                if (!counts.absent.isEmpty()) {
                    sb.append(" absent ").append(counts.absent);
                }
            });
            if (!completions.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("turns ").append(completions);
            }
            return sb.toString();
        }

        private static final class Counts {
            private long fromColumn;
            private long derived;
            private final Map<Absence, Long> absent = new EnumMap<>(Absence.class);
        }
    }
}
