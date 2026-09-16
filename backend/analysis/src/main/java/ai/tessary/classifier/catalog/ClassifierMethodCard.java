// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How each classifier works, in the words an agent auditing its finding needs — delivered as
 * {@code dossier/method.md} to triage and RCA.
 *
 * <h2>The problem it solves</h2>
 *
 * <p>{@code finding_evidence} has one role vocabulary and the roles do not mean the same thing across
 * classifiers. An empty {@code baseline} is CORRECT for {@code tool_error} (a CUSUM compares against a
 * fitted rate, and no window of rows exists), CORRECT for metric drift's rolling arm (a weighted ring
 * of per-day histograms, likewise no rows), and a DEFECT for metric drift's pinned arm (which stores
 * the window it pinned). An agent handed a role and a count, with no statement of method, reads the
 * same zero three ways and is wrong on two of them.
 *
 * <p>So the cards state, per classifier: what it measures, what it compared against, where the claim's
 * numbers sit in {@code get_finding}, what each role it writes means HERE, what an absent role
 * legitimately signifies, and — per cause the classifier files — what that cause actually asserts.
 *
 * <h2>Why here and not on {@code ClassifierModelModule}</h2>
 *
 * <p>The obvious home is a component beside {@code description}, and the compiler would then force a
 * new module to supply one. Two things argue against it. {@code description} is user-facing catalogue
 * copy re-synced onto project rows only when {@code version} advances — and a version bump rewrites
 * {@code configJson} wholesale, clobbering operator-edited thresholds — so text that wants to be
 * editable freely does not belong on that record. And these cards are a set: an author writing one
 * needs to see how the others phrase "an absent role means", because inconsistency between them is
 * exactly what would mislead an agent reading two findings in one investigation. Coverage is pinned by
 * a test instead of the compiler, which is the trade this makes deliberately.
 */
public final class ClassifierMethodCard {

    private ClassifierMethodCard() {}

    private static final String TOOL_ERROR = """
            ## tool_error: a Bernoulli CUSUM over one tool's calls

            **Measures** the fraction of one tool's calls that failed, accumulated call by call. In
            practice hour by hour: the detector folds hourly tallies, so its onset resolves to the hour
            the accumulator left zero, not to a single call.

            **Compares against** that tool's own pinned in-control rate, a fitted number, not a stretch
            of traffic. The spell runs from the onset to now.

            Each call counts as an independent trial: parallel calls sharing one upstream failure each
            add their own evidence, so check the witness timestamps for simultaneity.

            **The claim's numbers** are in `get_finding` under `toolError`: `refRate` and `curRate`
            (fractions), `nRef`, `nCur`, `failuresCur`, `deltaPp`, `statistic` against `threshold`,
            `effectSize`, `direction`, `onsetAt`, and `patterns` (each current failure signature with its
            count). `nCur` counts to the detector's watermark, the last hour bucket it folded, while
            `member` enumerates to the moment the finding was written, so the two can differ by a few
            calls. Two clocks, not a defect.

            **Evidence**
            - `member`: every call of the tool since onset, failing and healthy alike, at span grain. The
              denominator.
            - `witness`: the failing subset of that population, also at span grain. The numerator. Rows
              carry no outcome column; the role is what says a call failed, and `errorType` is its
              signature.

            **Absent roles**
            - No `baseline`, and that is correct: a CUSUM has one reference and it is a fitted rate; there
              is no window of rows it was compared against.
            - No `exemplar`: nothing here is a designated way in. Page the population and choose what to
              open.

            ### Cause: `rate_shift`

            One tool's failure rate has moved against its pinned in-control rate. No single call is being
            called wrong, and a tool failing sometimes is normal. The claim is that the calls since onset
            fail at a rate the reference does not explain, and it holds when the witnesses are the tool
            failing on the agent's own calls.

            A `negative` does more than close the finding: it folds this spell into the tool's reference,
            so this rate becomes the detector's new normal for this tool and a spell at it stops firing.
            """;

    private static final String METRIC_DRIFT = """
            ## duration_drift / cost_drift: two windows compared as distributions

            **Measures** a distribution of per-turn or per-tool durations (or costs) over a closed window,
            against a reference. One finding is one closed window. Duration is measured on the root step of
            a run; cost and tokens on a whole-run row are the trace's rollup, and `models` lists every model
            the run called.

            **Compares against** one of two references, and the cause key's last segment says which:

            - `:pinned`: a window a person pinned as normal. Its rows were stored and are here as
              `baseline`.
            - `:previous`: a rolling control: one slot per UTC day over 21 days, each an exact merge of
              every window that closed in it, weighted by a seven-day half-life, with days a confirmed
              regression ran through excluded. The name is legacy; it has not meant "the previous window"
              since that design was retired for normalising away step changes.

            **The claim's numbers** are in `get_finding` under `metric`: `ratio` and `w1Log` against
            `floor`, `direction`, `nRef` and `nCur`, `quantiles` (p50 and p95, then and now), `workload`
            (input tokens, user message length and prior turns per turn, then and now), `tokens` on a cost
            finding, `bucketKind` and `bucketKey`, the window, and on the `:previous` arm `control` (days
            used, days excluded as confirmed, oldest day, half-life). `explains` lists sibling shifts this
            one accounts for. The quantiles come from a log histogram with 5% bins, so quantiles you take
            from the rows will not match them to the digit.

            **Evidence**
            - `member`: every sample folded into the closed window, in fold order. Span grain for a
              tool-grain measure, whole-run rows for a turn-grain one. Written once, when the finding
              opened; a persisting regression re-fires on later windows and those are not appended.
            - `baseline`: the pinned window's rows, on the `:pinned` arm only.

            A tool bucket spans every call site that calls the tool, and each row carries its own
            `callSiteId`. On the `:previous` arm the reference period's traffic is not in the evidence but
            is still in the store: `list_spans` and `list_traces` with a `range` before the window reach
            it, within retention.

            **Absent roles**
            - No `baseline` on a `:previous` finding is correct: a weighted ring of daily histograms has no
              rows behind it, and `metric.control` describes what it was. A `:pinned` finding with no
              `baseline` is a defect.
            - No `witness`: every sample in the window is a member of the shifted population, and nothing
              marks one as the failure.
            - No `exemplar`, for the same reason as tool_error.

            ### Cause: `distribution_shift`

            A whole population of turns or tool calls sits measurably away from the reference: slower,
            faster, more expensive or cheaper. No individual trace is called wrong. A slow or expensive
            member is a member of the shifted population, not an anomaly in it, and the bar is the
            reference, not any absolute limit.

            The claim holds when the same kind of work moved. The `workload` pairs describe what users
            asked for, then and now, beside the measure that moved; the rows carry the models, tokens,
            call sites and timing behind it. Flat inputs with a moved measure is the agent running
            differently. Inputs that moved with the measure is the traffic changing.
            """;

    private static final String BEHAVIOR_DRIFT = """
            ## behavior_drift: a trajectory scored against a fitted n-gram model

            **Measures** how surprising a trace's sequence of steps is under the call site's own fitted
            profile: an omission, a novelty, or a high-surprisal path.

            **Compares against** the profile: counts, not rows.

            **Evidence**
            - `exemplar`: the trace the firing was recorded on.
            - `member`: this cause's firings across batches, at trace grain. A cause builds its population
              over time, so this is the union of every batch that fired it, not one batch's worth.

            **Absent roles**
            - No `baseline`, by construction. The reference is a fitted model; a model is counts, and there
              is nothing to enumerate on that side. A zero here is the method, not a lost write.

            ### Cause: `omission`

            The listed step or steps appear in almost every other trace this call site produces, and this
            trace performed none of them. The claim holds when the request was of the kind that gets those
            steps and the trace skipped them anyway. A request that never needed the step is the traffic
            differing, not the agent.

            ### Cause: `novelty`

            The listed action sequence is one this call site had not produced before. New is not wrong.
            The claim holds when the trace really took that sequence and nothing in the request explains
            why it would.

            ### Cause: `surprisal`

            The listed transition is one this call site makes far more rarely than its alternatives at
            that point. Rare is not wrong. The claim holds when the trace really took it and nothing in
            the request explains why it would.
            """;

    private static final String SOP_CONFORMANCE = """
            ## sop_conformance: an obligation checked against the turns it applied to

            **Measures** how often an SOP rule was honoured on the turns where it was in force. Two
            different claims share this classifier, and `conformance_kind` says which: a `drift` finding
            says conformance fell, a `baseline` finding says it was never high.

            **Compares against** either the bundle's fitted expectation model plus a stored activation
            count (the windowed drift test), or the very turns the fit ran on (the fit-time audit).

            **Evidence**, all at trace grain, all refreshed on every pass, because the window rolls and
            the union across sweeps is the traffic the deficit has actually been seen over.
            - `exemplar`: the violating turns, ranked most-surprising-first. The order is part of the
              claim.
            - `member`: the activations the violation count is a fraction of.
            - `baseline`: the fit-time audit's reference activations, where one exists as rows.
            - `changepoint`: where the deficit starts concentrating. Descriptive, not a test. No other
              classifier writes this role.

            **Absent roles**
            - No `baseline` on a windowed drift finding is correct: its reference is a fitted expectation
              model, and no set of rows survives it.

            ### Kind: `drift`

            Conformance to the rule fell on the turns where it applied. The claim holds when the
            `exemplar` turns really violate the rule as written and the turns where it applied are the
            same kind of turns as before.

            ### Kind: `baseline`

            Conformance to the rule was never high on the turns the fit ran on. The claim holds when the
            `exemplar` turns really violate the rule as written.
            """;

    private static final String ARMED_SIGNAL = """
            ## {key}: a per-observation detector armed on a threshold

            **Measures** whether individual observations trip this detector, and fires a finding when
            enough of them do inside one window.

            **Compares against** a threshold on the count, not another stretch of traffic.

            **The claim's numbers** are in `get_finding` under `armedWindow`: `basis` (`event_count` or
            `distinct_users`), `observed` against `threshold`, `windowSeconds`, the window, and
            `confidence` where the detector bands it.

            **Evidence**
            - `member`: the observations that fired, written in the same transaction as the finding, so a
              finding never exists without the evidence that justified it.

            **Absent roles**
            - No `baseline`: nothing here is a two-window comparison, so there is no before side to
              enumerate.
            - No `exemplar`: the population is the claim, and every member of it is equally a way in.

            ### Cause: `armed_window`

            Enough observations tripped the detector inside one window, and each `member` is one of them.
            The claim holds when the flagged observations, read from the spans themselves, are what the
            detector says they are and come from how the agent behaved rather than from what users
            brought to it.
            """;

    private static final String SECRET_LEAK = """
            ## secret_leak: a credential rule matched in one call site's output

            **Measures** whether an output contains a credential, using the gitleaks rule corpus. It reads
            redaction's record of what it replaced first, then scans the stored output, then looks for a
            bare `[REDACTED_*]` marker.

            **Compares against** nothing. One HIGH-confidence match opens the finding, per call site and
            rule (the cause key's pattern). HIGH means a rule anchored on a provider format such as
            `AKIA`; JWT, curl auth and Kubernetes secret rules stay LOW and never open one.

            **The claim's numbers** are in `get_finding` under `secretLeak`: `rule`, `confidence`,
            `leakCount`, `traceCount`, `firstAt`, `lastAt`, `keys` (each masked key with its leak and
            trace counts and whether any copy is stored raw), `basis`, `threshold`, `windowSeconds` and
            the window.

            **Evidence**
            - `witness`: the spans whose output matched, capped at 50. Each row carries its masked key
              (`secretKey`) and whether the stored copy is `redacted` or `raw` (`storedAs`). `get_span`
              shows the output; a redacted copy shows the marker where the value was.

            **Absent roles**
            - No `member` or `baseline`: nothing here is a rate or a comparison, so no population or
              before side exists.
            - No `exemplar`, for the same reason as tool_error.

            ### Cause: `armed_window`

            Enough matches tripped the rule inside one window, and each `witness` is one of them. The
            claim holds when the matched values are credentials the agent put in its output: a value in a
            live key's format, not a documented example, a placeholder, or text the user supplied.
            """;

    private static final String MALFORMED_OUTPUT = """
            ## malformed_output: a Bernoulli CUSUM over one call site's schema failures

            **Measures** the fraction of a call site's outputs that fail its declared output schema, read
            from the connected repository. An output fails when it is not JSON or violates the schema. For
            a gen_ai message envelope, the final assistant message is what gets validated. Only call sites
            with a declared schema are counted.

            **Compares against** that call site's fitted in-control rate. Same engine as tool_error, folded
            hour by hour.

            **The claim's numbers** are in `get_finding` under `malformedOutput`: `rate` (the same shape
            as tool_error's `toolError`: `refRate`, `curRate`, `nRef`, `nCur`, `failuresCur`, `statistic`,
            `threshold`, `effectSize`, `direction`, `onsetAt`), `fields` (the declared schema with a
            failure count per field), `notJson` and `other`. The denominator `nCur` is a count, not
            enumerated rows.

            **Evidence**
            - `witness`: failing outputs since onset, capped at 50. Each row carries its `violation`
              message.

            **Absent roles**
            - No `member`: the denominator is `rate.nCur`, not rows.
            - No `baseline`: the reference is a fitted rate, as with tool_error.
            - No `exemplar`, for the same reason as tool_error.

            ### Cause: `malformed_rate`

            The share of one call site's outputs failing its declared schema has risen above its fitted
            rate. Some failures are normal. The claim holds when the witnesses really fail the declared
            schema and the failures come from how the agent produces its output, not from what it was
            asked.
            """;

    /**
     * Cards for the classifiers that write findings through a detector of their own. The armed-signal
     * family shares one shape and is rendered from {@link #ARMED_SIGNAL} with its key substituted.
     */
    private static final Map<String, String> BY_KEY = Map.of(
            BuiltInDetector.Kind.TOOL_ERROR, TOOL_ERROR,
            BuiltInDetector.Kind.DURATION_DRIFT, METRIC_DRIFT,
            BuiltInDetector.Kind.COST_DRIFT, METRIC_DRIFT,
            BuiltInDetector.Kind.BEHAVIOR_DRIFT, BEHAVIOR_DRIFT,
            BuiltInDetector.Kind.SOP_CONFORMANCE, SOP_CONFORMANCE,
            BuiltInDetector.Kind.SECRET_LEAK, SECRET_LEAK,
            BuiltInDetector.Kind.MALFORMED_OUTPUT, MALFORMED_OUTPUT);

    /**
     * The card for one classifier key, or null when the key names nothing this knows about — a
     * user-authored regex or classifier row, whose method is whatever its author configured and which
     * this cannot describe for them.
     */
    public static @Nullable String forClassifier(@Nullable String classifierKey) {
        if (classifierKey == null || classifierKey.isBlank()) return null;
        String card = BY_KEY.get(classifierKey);
        if (card != null) return card;
        return ARMED_SIGNAL_KEYS.contains(classifierKey) ? ARMED_SIGNAL.replace("{key}", classifierKey) : null;
    }

    /** The built-in detectors that file through {@code ClassifierArming} rather than their own sweep. */
    static final java.util.Set<String> ARMED_SIGNAL_KEYS =
            java.util.Set.of(BuiltInDetector.Kind.FRUSTRATION, BuiltInDetector.Kind.GROUNDEDNESS);
}
