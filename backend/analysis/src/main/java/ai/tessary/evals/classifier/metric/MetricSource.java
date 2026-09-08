// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

import ai.tessary.evals.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.evals.classifier.metric.MetricSourceRepository.LeafUsage;
import ai.tessary.evals.classifier.metric.MetricSourceRepository.ToolSpanFacts;
import ai.tessary.evals.classifier.metric.MetricSourceRepository.TurnFacts;
import ai.tessary.evals.classifier.substrate.ActionSymbol;
import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository.TraceHead;
import ai.tessary.evals.vitals.TokenPriceBook;
import ai.tessary.evals.vitals.TokenUsage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one accessor every metric-drift measure is read through: column-preferred, with a derivation
 * behind it, and an explicit abstention when neither can answer.
 * ({@code classifiers/metric_drift/PROGRAM.md} §3.0, PLAN.md §2.)
 *
 * <p><b>Why a seam at all, rather than reading the rollup columns.</b> {@code trace.latency_ms},
 * {@code trace.total_cost} and {@code trace.total_tokens} are the intended source and are NULL on every
 * production row today — the v1 write path passed literal null for all three at the write
 * site, which {@link ai.tessary.evals.vitals.VitalsRepository} documents against production. A detector
 * that reads them bare abstains on 100% of traffic while looking correct in every unit test, because
 * fixtures populate what ingestion does not. So the derivation is not a hedge against an unlikely case,
 * it is the live path — and it stays the live path for two reasons that outlast the backfill: a
 * backfill will not reach historical rows, and the pull/upload ingest path supplies no span end time at
 * all.
 *
 * <p><b>Abstain, and say why.</b> Every measure returns {@link Measurement.Absent} carrying an
 * {@link Absence} rather than a null or a zero. Zero is the dangerous one: an unpriced model read as $0
 * turns a price-book gap into a cost improvement, the single failure that makes the number worse than
 * not having it. Reasons are counted into a {@link Tally} the sweep logs once per pass, so a measure
 * abstaining on all of its traffic costs one glance rather than one investigation — the failure
 * PROGRAM.md §13 opens with.
 *
 * <p><b>The bucket key is not resolved here.</b> It arrives on the {@link TraceHead} this is called
 * with, resolved once by {@code BehaviorSubstrateRepository.SELECT_TRACE_HEAD}'s lateral —
 * root-span-first with the {@code seq → started_at → created_at} fallback chain. Taking the head rather
 * than a bare trace id is deliberate: it makes re-deriving the entry point impossible at this seam, and
 * re-deriving is the one place the two classifiers could silently disagree about which bucket a trace
 * belongs to. That fallback chain exists because {@code seq} is NULL on every OTLP-ingested
 * observation, and without it 443 traces whose roots all carried one call site were scattered across
 * six.
 *
 * <p><b>Nothing is dropped.</b> A turn whose root span never ended cannot contribute a duration, but it
 * comes back with its {@link Completion} category and is counted. Excluding it silently would remove
 * precisely the traffic a duration detector most wants to see, and a rising number of stuck turns would
 * then read as improving latency.
 *
 * <p>Reads are batched per sweep page — one round trip per query per page, never one per trace.
 */
@Component
public class MetricSource {

    private final MetricSourceRepository repository;
    private final TokenPriceBook prices;

    public MetricSource(MetricSourceRepository repository, TokenPriceBook prices) {
        this.repository = repository;
        this.prices = prices;
    }

    // ---------------------------------------------------------------------------------------------
    // Vocabulary
    // ---------------------------------------------------------------------------------------------

    /**
     * Where a present value came from. Counted alongside the abstentions because it answers the one
     * operational question the rollup backfill will raise — "did it land, and for which measures" —
     * without anyone having to go and query the table.
     */
    public enum Provenance {
        /** The pre-computed rollup column held a usable value; nothing was derived. */
        COLUMN,
        /** The column was null (or not a measurement), so the value was computed from leaf facts. */
        DERIVED
    }

    /**
     * Why a measure has no value for a subject. Four reasons, and the set is deliberately small: each
     * one is a distinct thing an operator would DO about it.
     */
    public enum Absence {
        /**
         * The interval this measure is the length of has no end — a root span that never ended, a tool
         * span still running, or (rarer) a span a producer shipped without a start. The fix is
         * instrumentation, or nothing at all: an abandoned turn is a real thing that happened.
         */
        NO_END_TIME,

        /**
         * The span has both endpoints and they run BACKWARDS: {@code ended_at} before {@code started_at},
         * so the derived interval is negative. Clock skew between the host that stamped the start and the
         * host that stamped the end, or a re-exported span whose {@code started_at} was rewritten — both
         * routine in distributed tracing and neither a measurement.
         *
         * <p>This abstention is load-bearing rather than tidy. {@code log(-3)} is {@code NaN}, and
         * {@link MetricSketch#add} throws on NaN by contract, so a single skewed span folded into a window
         * would propagate out of the sweep uncaught, the job would fail before its cursor advanced, and
         * every retry would re-read the same page and hit the same span until the signal dead-lettered —
         * one bad span silencing every metric measure for the project. The counterpart guard on the ROLLUP
         * column is already there ({@code column >= 0}); production is 100% the derivation path today
         * (PROGRAM.md §3.0), so this is the one that fires.
         */
        NEGATIVE_INTERVAL,

        /**
         * At least one of the trace's generations carries no recorded cost, so the turn's cost is
         * unknown. It leaves the distribution rather than joining it at $0 — the posture vitals already
         * takes, counting unpriced calls rather than reading them as free.
         *
         * <p>Two things produce it, and an operator does the same thing about both. Either the
         * generation ran on a model {@link TokenPriceBook} carried no rate for when it arrived — fix the
         * book, and traffic from the next deploy onward is priced — or it predates
         * ingest-time pricing at all, in which case it abstains until it ages
         * out of the windows. Neither is repaired retroactively, and deliberately: dollars are recorded
         * at write time precisely so that nothing later rewrites what a call cost.
         */
        UNPRICED_MODEL,

        /**
         * The provider never reported the quantity this measure sums over, so there is no number — which
         * is not the same fact as a number that happens to be zero.
         *
         * <p>The case this exists for is {@code tok_cache_write}. Whether writing to a prompt cache is a
         * counted quantity at all is a per-model fact, and the model's rate is what settles it —
         * {@link TokenPriceBook#billsCacheCreation}, not a provider-family list, because the convention
         * now differs inside a single vendor ({@code gpt-5.6} bills cache creation, {@code gpt-4o}'s
         * automatic caching does not) and Gemini bills storage per hour rather than per written token.
         * Recording zero on any of those would say "no writes" when the truth is "not measured", and a
         * call site that switched provider would show a clean collapse in cache writes for purely
         * bookkeeping reasons.
         */
        BUCKET_NOT_REPORTED
    }

    /**
     * Whether a turn finished, as its own counted category rather than as a filter.
     *
     * <p>{@link #UNTERMINATED} and {@link #NO_ROOT_SPAN} both abstain on duration with
     * {@link Absence#NO_END_TIME}; they are told apart here because they mean different things about the
     * pipeline. The first is a turn that genuinely never completed. The second is a trace still
     * ARRIVING — a {@code trace} row exists as soon as its FIRST span does, and a batch exporter flushes
     * on span end, so the root (which outlives every child) ships last.
     *
     * <p>{@code NO_ROOT_SPAN} is load-bearing rather than a curiosity, and it is only truthful because
     * {@code MetricSourceRepository.turnFacts} refuses to count a not-yet-adopted child as a root (see
     * the {@code parent_external_span_id} clause there). It is what
     * {@code MetricDriftSweep.admissibleThrough} holds a page on: a turn read in the gap between its
     * first span and its root has no duration worth taking, and the cursor is forward-only, so stepping
     * over it loses it for good.
     */
    public enum Completion {
        COMPLETED,
        UNTERMINATED,
        NO_ROOT_SPAN
    }

    /**
     * One measure's reading for one subject: a value with its provenance, or an abstention with its
     * reason. Sealed and pattern-matched rather than a nullable double, so a caller cannot quietly
     * forget the absent case — which is the entire point of not returning null.
     */
    public sealed interface Measurement {

        /**
         * A real measurement, in the measure's own units: milliseconds for the durations, USD for cost,
         * tokens for the four buckets. Raw, never logged — the sketch takes the logarithm, and the
         * evidence blob reports quantiles in the units a human reads.
         */
        record Present(double value, Provenance provenance) implements Measurement {

            /**
             * {@code ln(value)}, the space every {@link MetricSketch} works in. A value of exactly zero
             * gives {@code -inf} and lands in the sketch's underflow counter by design: zero cache-read
             * tokens is the collapse this program exists to catch, so it is a sample, not an error.
             */
            public double logValue() {
                return Math.log(value);
            }
        }

        /** No value, and why. */
        record Absent(Absence reason) implements Measurement {}

        static Measurement present(double value, Provenance provenance) {
            return new Present(value, provenance);
        }

        static Measurement absent(Absence reason) {
            return new Absent(reason);
        }

        /** Whether a value exists — for callers that only branch rather than destructure. */
        default boolean isPresent() {
            return this instanceof Present;
        }
    }

    /**
     * Everything the turn-grain measures produce for one trace, plus the key they are filed under.
     *
     * @param callSiteId the ENTRY POINT's call site, straight off the head — the bucket key of
     *     PROGRAM.md §2.1. A trace legitimately spans several call sites, so a baseline scoped to a
     *     child would model "traces that happened to contain this tool" rather than "traffic that
     *     entered here".
     * @param eventAt the trace's own start, falling back to ingest time — the clock windows are CUT on.
     *     The sweep's keyset cursor stays on {@code created_at}; two clocks, two jobs.
     * @param projectVersionId the deploy this turn ran under. Not part of the key — it is what the
     *     pinned reference hangs on, so a deploy re-pins the reference instead of resetting the window.
     * @param tokens the four (disjoint) buckets summed over the trace's generations, or null when none
     *     reported usage. Carried so a caller can take a ratio without re-summing and arriving at a subtly
     *     different number.
     * @param measurements keyed by the PERSISTED measure name ({@link Measure}). Every measure this
     *     grain owns is present as a key, with an absent reading rather than a missing entry.
     * @param workload what the USER asked for on this turn, carried beside what the agent did with it.
     *     Never a measure and never a covariate — see {@link MetricWorkload}.
     */
    public record TurnMetrics(
            String traceId,
            String callSiteId,
            String eventAt,
            @Nullable String projectVersionId,
            Completion completion,
            @Nullable TokenUsage tokens,
            Map<String, Measurement> measurements,
            Workload workload) {

        /** One measure's reading. An unknown measure name reads as not reported rather than throwing. */
        public Measurement measurement(String measure) {
            return measurements.getOrDefault(measure, Measurement.absent(Absence.BUCKET_NOT_REPORTED));
        }

        /**
         * Cache-read share of the prompt, {@code cache_read / (cache_read + input)} — the form
         * PROGRAM.md §3.3 asks for cache to be watched in.
         *
         * <p>The most common silent cost regression is a prompt-prefix edit that stops the cache
         * hitting. On the ratio that reads as a clean collapse from ~0.8 to ~0.0; on the raw cache-read
         * count it is indistinguishable from a quiet week, because the count moves with traffic volume
         * and the ratio does not.
         *
         * <p>A turn that reported no prompt at all has no ratio — not a ratio of zero — so it abstains
         * with {@link Absence#BUCKET_NOT_REPORTED}, which is the same statement the buckets it is built
         * from are already making.
         */
        /**
         * The four token buckets and the cache-read share as plain numbers, each null where this turn
         * abstained — the form {@link MetricTokens} folds and the {@code cost} finding's evidence prints.
         *
         * <p>Null is <b>not measured</b>, and it is load-bearing rather than tidy: {@code tok_cache_write}
         * abstains wherever cache creation is not a billed quantity, and folding that in as a zero would
         * say "no writes" about a population nobody counts writes for.
         *
         * <p>The share is carried in PERCENT, because that is the unit its sketch's geometric bins resolve
         * well — see {@link MetricTokens#CACHE_READ_PCT}. {@link #cacheReadRatio()} stays the fraction, so
         * anything reading the ratio as a ratio is unaffected.
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

    /**
     * One turn's cost decomposition, in raw units — tokens, and the cache-read share in percent — each
     * null where the turn abstained. What {@link MetricTokens} folds; see {@link TurnMetrics#tokenReadings}.
     */
    public record TokenReadings(
            @Nullable Double input,
            @Nullable Double output,
            @Nullable Double cacheRead,
            @Nullable Double cacheWrite,
            @Nullable Double cacheReadPct) {}

    /**
     * One turn's workload readings, in raw units, each null when nothing reported it.
     *
     * <p>Null is <b>unknown</b>, never zero. A producer that ships no input text has an unknown message
     * length rather than an empty message, and folding the unknown in as zero would manufacture a
     * workload collapse out of an instrumentation gap — which is precisely the false "the traffic
     * changed" story this block exists to rule out.
     *
     * <p>{@code inputTokens} is read off the same {@link TokenUsage} sum the {@code tok_input} measure
     * is, rather than re-summed here, so the evidence blob and the measure cannot report two different
     * prompt sizes for one turn.
     */
    public record Workload(
            @Nullable Double inputTokens,
            @Nullable Double userMsgChars,
            @Nullable Double priorTurns) {

        /**
         * Nothing reported. The reading for a subject that HAS no workload of its own rather than one
         * whose workload was not captured — a tool span, whose window deliberately folds none (see
         * {@code MetricDriftSweep.toolSamples}).
         */
        public static final Workload NONE = new Workload(null, null, null);
    }

    /**
     * One dispatchable span's duration, filed under its tool bucket.
     *
     * @param callSiteId the entry point of the TURN this span belongs to — not this bucket's key.
     *     Carried so the §6.1 suppression rule can ask whether a tool shift accounts for the turn shift
     *     on the same call site, which needs both grains in hand at once.
     * @param bucketKey an {@link ActionSymbol} {@code kind:normalized-name}, so latency buckets and
     *     drift's alphabet name the same tool the same way.
     * @param eventAt the SPAN's own start, falling back to ingest time — not the trace's.
     */
    public record ToolMetrics(
            String traceId,
            String observationId,
            String callSiteId,
            String bucketKey,
            String eventAt,
            Measurement duration) {}

    // ---------------------------------------------------------------------------------------------
    // Turn grain
    // ---------------------------------------------------------------------------------------------

    /**
     * Every turn-grain measure for one sweep page — {@code turn_duration}, {@code cost} and the four
     * token buckets — one {@link TurnMetrics} per head, in the order the heads were given.
     *
     * <p>Two queries for the whole page, never two per trace. A head whose trace row has since been
     * deleted still comes back, with {@link Completion#NO_ROOT_SPAN} and every measure absent, because a
     * page that silently shrinks is how a forward-only cursor advances past traffic nobody measured.
     *
     * <p><b>The settle window is the caller's business, and it is not uniform.</b> Cost and the token
     * buckets SUM over a trace's spans, so they need every span to have arrived and the sweep windows
     * them on {@code trace_settle_seconds}; measuring early reads as cheap, which surfaces as a
     * permanent drift toward cheaper whenever ingest lags. Duration needs no settle horizon at all — it
     * is read off the root span, whose arrival IS the completion signal — and applying one there delays
     * every duration finding for nothing (PROGRAM.md §5).
     *
     * @param tally accumulates provenance and abstention counts. The sweep owns one per pass and hands
     *     the same instance to every page, so its summary describes the pass rather than its last page.
     */
    public List<TurnMetrics> turnMetrics(String projectId, List<TraceHead> heads, Tally tally) {
        if (heads.isEmpty()) return List.of();
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
                    head.traceId(),
                    head.callSiteId(),
                    head.eventAt(),
                    head.projectVersionId(),
                    completion,
                    spend.tokens(),
                    Map.copyOf(measurements),
                    workloadOf(f, measurements)));
        }
        return out;
    }

    /**
     * The turn's workload readings, assembled from the facts already in hand — no extra round trip, and
     * no second derivation of a number a measure has already computed.
     */
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
     * The turn's wall-clock duration in milliseconds: the ROOT span's own
     * {@code ended_at - started_at}, and only that.
     *
     * <p><b>Never {@code max(ended_at) - min(started_at)} over the trace.</b> Measured against
     * production that envelope inflated p95 by ~10% versus the root span, because async children outlive
     * their parent. The root span encloses its children and is what Jaeger, Tempo and Datadog report as
     * trace duration, so this and the vitals percentiles cannot disagree about what a turn took.
     *
     * <p><b>Which is why {@code trace.latency_ms} is not read here, populated though it now is.</b>
     * Every other measure on this row prefers the rollup column, because for a sum or a count the
     * worker's replacement recompute IS the answer. Duration is the exception: {@code trace.ended_at} is
     * folded as a {@code max} over the trace's spans and {@code latency_ms} is generated from it, so that
     * column is exactly the envelope this method exists to reject. Preferring it was harmless in v1 only
     * because nothing ever wrote it — the preference was dead code that read as a rule — and promoting
     * the rollups to real numbers would have turned it into a silent ~10% p95 inflation on every turn
     * carrying an async child. The trace's timers stay the right source for spend and for settle; they
     * are the wrong source for how long the user waited.
     *
     * <p>The backwards-interval guard therefore lives on the derivation, which is where it always
     * mattered: a root whose end precedes its start is routine clock skew across hosts, and laundering
     * one into a sample would sit in the sketch's underflow counter forever. See
     * {@link Absence#NEGATIVE_INTERVAL} for what folding one in would cost.
     */
    private static Measurement turnDuration(@Nullable TurnFacts f, Completion completion) {
        if (f == null) return Measurement.absent(Absence.NO_END_TIME);
        Double derived = f.rootMillis();
        // The derivation is null exactly when an endpoint is missing, which `completion` has already
        // named; both are read so the value and the counted category can never disagree.
        if (derived == null || completion != Completion.COMPLETED) {
            return Measurement.absent(Absence.NO_END_TIME);
        }
        // Both endpoints present but running backwards. Its own category, not NO_END_TIME: the end time
        // is right there, and what an operator does about a skewed clock is nothing like what they do
        // about a span that never ended.
        if (derived < 0) return Measurement.absent(Absence.NEGATIVE_INTERVAL);
        return Measurement.present(derived, Provenance.DERIVED);
    }

    /**
     * The turn's cost in USD: {@code trace.total_cost} when the worker has written a total that means
     * anything, otherwise the sum over the trace's generations.
     *
     * <p>Unlike duration, the rollup IS the right shape here — cost is a sum over the trace's spans and
     * that is precisely what §7.2 recomputes. Two things disqualify it, and neither is a matter of
     * taste. A NEGATIVE total is not a cost. And a total computed over a trace holding
     * {@code unpriced_spans > 0} is a sum with a hole in it: {@code SUM} skips the nulls, so a turn where
     * one generation ran on a model the book has no rate for reports the price of the OTHER generations
     * and reads as cheaper than the same turn last week. That is the one failure that makes this measure
     * worse than not having it — a price-book gap arriving as a cost improvement — and it is exactly
     * what {@code trace.unpriced_spans} is carried for. Falling through hands the decision to
     * {@link #spendOf}, which abstains rather than reporting a partial sum.
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

    // ---------------------------------------------------------------------------------------------
    // Cost and the token buckets
    // ---------------------------------------------------------------------------------------------

    /**
     * What one trace's generations summed to: the derived cost reading, the four bucket readings, and
     * the disjoint usage both were computed from.
     */
    private record Spend(
            Measurement cost,
            Map<String, Measurement> buckets,
            @Nullable TokenUsage tokens) {}

    /**
     * Sum a trace's generations into a cost and the four token buckets.
     *
     * <p><b>Off typed columns, not a parsed blob.</b> This used to normalize every leaf through
     * {@link TokenUsage} because the v1 write path rewrote every provider onto one
     * {@code gen_ai.usage.*} vocabulary while leaving OpenAI's cache-INCLUSIVE input count in place —
     * so an OpenAI generation arrived wearing Anthropic key names and trusting the spelling billed its
     * cache reads twice. v2 applies that correction ONCE, at write time in {@code IngestPricer}, so the
     * stored buckets are already disjoint and this is a plain addition.
     *
     * <p><b>NULL is how a producer says "I do not report this".</b> The old code could not use the
     * normalized values for that question — the blob parser collapsed an absent key to 0, making
     * "reported zero cache writes" and "reports no cache writes at all" identical — so it read raw key
     * spellings out of the blob alongside the normalized sum. The typed columns answer it directly: null
     * means unreported, and a stored 0 is a real measurement, which is the whole point, because a
     * cache-read count FALLING to zero is the prompt-prefix regression this program exists to catch.
     *
     * <p><b>Cache writes still need two gates.</b> A present column is necessary but not sufficient: an
     * SDK that stamps every usage attribute it knows about writes a literal 0 for a provider whose
     * caching is automatic, and recording "zero writes" for a quantity nobody meters is a measurement of
     * our own instrumentation. Where creation is genuinely billed, a 0 is real and stays one.
     *
     * <p><b>Root spans carry no model</b>, which is why this sums over leaves while the bucket key comes
     * from the root's entry-point call site. Never try to read a model off the root.
     *
     * <p><b>One unpriced leaf abstains the whole turn.</b> Pricing the rest and reporting the partial
     * sum would understate that turn's spend by an unknown amount and put a plausible number into the
     * distribution — worse than the honest gap, and the same reason vitals counts unpriced spans rather
     * than reading them as free.
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
            // Each bucket is read ONCE into a local. The accessors are @Nullable, so a null check on one
            // call and an unbox on the next are two reads as far as any analyser is concerned — and the
            // unboxing is what would NPE.
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
            // The second gate: the model has to be one the book bills for cache creation. See above.
            if (cacheWrite != null) {
                cacheWriteTokens += cacheWrite;
                reportsCacheWrite |= prices.billsCacheCreation(leaf.model());
            }
            // The price this generation was billed at when it arrived, written by IngestPricer against
            // the book in force then, and the ONLY source of dollars here.
            //
            // There is deliberately no re-pricing fallback. A cost recomputed at read time is a cost
            // that moves with the deploy: the pinned reference held dollars from an older book, so a
            // rate refresh shifted every bucket against its own reference at once and read as a
            // fleet-wide regression nothing had caused. Reading only what was recorded makes a stored
            // cost a fact about what that call was billed at — which is also the only form of it worth
            // reconciling an invoice against — and it makes a later price change a real change in
            // spend rather than a retroactive edit to history.
            //
            // The cost of that: a generation on a model the book could not price is unscoreable and
            // stays unscoreable, which cost_source states outright rather than leaving to be inferred.
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
            // Always COLUMN: every dollar here was recorded on the span at ingest.
            cost = Measurement.present(usd.doubleValue(), Provenance.COLUMN);
        }
        TokenUsage summedTokens =
                anyReported ? new TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens) : null;
        return new Spend(cost, buckets, summedTokens);
    }

    /**
     * A token bucket reads as a value when the provider reported it — <b>including when it reported
     * zero</b>, which is a measurement and the most valuable one this program has: a cache-read count
     * falling to zero IS the prompt-prefix regression. Absent means the family emits no such count at
     * all, which is a different sentence entirely.
     */
    private static Measurement bucket(boolean reported, long tokens) {
        return reported
                ? Measurement.present(tokens, Provenance.DERIVED)
                : Measurement.absent(Absence.BUCKET_NOT_REPORTED);
    }

    // ---------------------------------------------------------------------------------------------
    // Tool grain
    // ---------------------------------------------------------------------------------------------

    /**
     * Every dispatchable span of one sweep page, with its own duration — the {@code tool_duration}
     * subjects. One query for the page.
     *
     * <p>The bucket key is minted with {@code isError = false}, so a tool's failures stay in the same
     * latency population as its successes. Splitting them would put half the samples in a bucket that
     * has no baseline, and it would hide the move worth catching: a tool that starts failing fast reads
     * as a shift in the one distribution rather than as traffic quietly migrating to a second one.
     *
     * <p>Spans still running abstain with {@link Absence#NO_END_TIME} rather than being filtered out,
     * for the same reason unfinished turns are — a tool that increasingly hangs must not read as a
     * shrinking sample of fast calls.
     */
    public List<ToolMetrics> toolMetrics(String projectId, List<TraceHead> heads, Tally tally) {
        if (heads.isEmpty()) return List.of();
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
                    // Every span came back FROM these trace ids, so the default is unreachable; it is
                    // spelled out rather than asserted because the scope of a stray span is genuinely
                    // unattributed, which is a bucket that already exists.
                    callSites.getOrDefault(span.traceId(), BehaviorSubstrateRepository.UNATTRIBUTED),
                    ActionSymbol.of(span.kind(), span.name(), false),
                    span.eventAt(),
                    duration));
        }
        return out;
    }

    /**
     * The span's own {@code ended_at - started_at}, column-preferred exactly as the turn grain is —
     * including the backwards-interval guard, which a tool span needs at least as much as a root does:
     * a tool call is the span most likely to have been stamped by a different host than the one that
     * recorded its return. See {@link Absence#NEGATIVE_INTERVAL}.
     */
    private static Measurement toolDuration(ToolSpanFacts span) {
        Long column = span.rollupLatencyMs();
        if (column != null && column >= 0) return Measurement.present(column, Provenance.COLUMN);
        Double derived = span.spanMillis();
        if (derived == null || span.unterminated()) return Measurement.absent(Absence.NO_END_TIME);
        if (derived < 0) return Measurement.absent(Absence.NEGATIVE_INTERVAL);
        return Measurement.present(derived, Provenance.DERIVED);
    }

    // ---------------------------------------------------------------------------------------------
    // Counters
    // ---------------------------------------------------------------------------------------------

    /**
     * Per-measure counts of what a sweep pass actually read: how many values came off a column, how many
     * were derived, and how many abstained for each reason.
     *
     * <p><b>This is an instrument, not bookkeeping.</b> The failure PROGRAM.md §13 opens with is a
     * measure that abstains on 100% of traffic and therefore never fires, while looking correct in every
     * unit test. Nothing about the findings distinguishes that from a quiet week — only these counters
     * do, which is why the sweep logs {@link #summary()} once per pass whether or not anything fired.
     *
     * <p>Mutable and not thread-safe: one Tally belongs to one sweep pass, which already runs under the
     * signal job's lease.
     */
    public static final class Tally {

        private final Map<String, Counts> byMeasure = new LinkedHashMap<>();
        private final Map<Completion, Long> completions = new EnumMap<>(Completion.class);

        /** Fold one reading in, under its persisted measure name. */
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

        /** Fold one turn's completion category in. */
        public void observed(Completion completion) {
            completions.merge(completion, 1L, Long::sum);
        }

        /** The measures this pass touched, in first-seen order. */
        public Set<String> measures() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(byMeasure.keySet()));
        }

        /** Values read straight off the rollup column — non-zero means the backfill has reached here. */
        public long fromColumn(String measure) {
            return counts(measure).fromColumn;
        }

        /** Values computed from leaf facts — the live path today, for every measure. */
        public long derived(String measure) {
            return counts(measure).derived;
        }

        public long present(String measure) {
            Counts c = counts(measure);
            return c.fromColumn + c.derived;
        }

        public long absent(String measure) {
            long total = 0;
            for (long n : counts(measure).absent.values()) total += n;
            return total;
        }

        public long absent(String measure, Absence reason) {
            return counts(measure).absent.getOrDefault(reason, 0L);
        }

        public long completions(Completion completion) {
            return completions.getOrDefault(completion, 0L);
        }

        /**
         * Share of this measure's subjects that produced no value, {@code 0.0} when none were seen at
         * all. A measure sitting at {@code 1.0} is the alarm this class exists for.
         */
        public double abstentionRate(String measure) {
            long seen = present(measure) + absent(measure);
            return seen == 0 ? 0.0 : (double) absent(measure) / seen;
        }

        /** One log line per pass: for each measure, where its values came from and why they did not. */
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

        private Counts counts(String measure) {
            return byMeasure.getOrDefault(measure, EMPTY);
        }

        /** The answer for a measure nothing was ever folded into. Read-only by construction. */
        private static final Counts EMPTY = new Counts();

        private static final class Counts {
            private long fromColumn;
            private long derived;
            private final Map<Absence, Long> absent = new EnumMap<>(Absence.class);
        }
    }
}
