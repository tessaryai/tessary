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
 * <p>So the cards state, per classifier: what it measures, what it compared against, what each role it
 * writes means HERE, and what an absent role legitimately signifies.
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
            ## tool_error — a Bernoulli CUSUM over one tool's calls

            **Measures** the fraction of one tool's calls that failed, accumulated call by call (in
            practice hour by hour: the detector folds hourly tallies, so its onset resolves to the hour
            the accumulator left zero, not to a single call).

            **Compares against** that tool's own pinned in-control rate — a fitted number, not a stretch
            of traffic. The spell runs from the onset to now.

            Each call counts as an independent trial: parallel calls sharing one upstream failure each
            add their own evidence, so check the witness timestamps for simultaneity.

            **Evidence**
            - `member` — every call of the tool since onset, failing and healthy alike, at span grain.
              This is the DENOMINATOR. It is enumerated in full, never sampled.
            - `witness` — the failing subset of exactly that population, also at span grain. The
              NUMERATOR. `finding_evidence` carries no outcome column, so the role is what says a call
              failed.

            Together they let you recompute the rate the finding claims. Sample them if you like — but
            say which stride or draw you took, because a claim about a population cannot be audited
            against a sample whose selection rule the reader does not know.

            The two counts answer to different clocks: `n_cur` counts to the detector's watermark, the
            last hour bucket it folded, while `member` enumerates to the moment the finding was written.

            **The payload's `patterns`** describes the current failures — their signatures and how many
            of each.

            **Absent roles**
            - No `baseline`, and this is correct rather than missing. A CUSUM has one reference and it
              is a fitted rate; there is no window of rows it was compared against.
            - No `exemplar`. Nothing here is a designated way in — picking one trace for you would
              bias what you conclude. Page the population and choose your own.
            """;

    private static final String METRIC_DRIFT = """
            ## duration_drift / cost_drift — two windows compared as distributions

            **Measures** a distribution of per-turn or per-tool durations (or costs) over a closed
            window, against a reference. One finding is one closed window.

            **Compares against** one of two references, and the cause key's last segment says which.
            This is the single most important thing to read before interpreting the evidence:

            - `:pinned` — a window a human pinned as normal. Its rows were stored, so they are here.
            - `:previous` — a rolling control: one slot per UTC day over 21 days, each an exact merge
              of every window that closed in it, weighted by a seven-day half-life, with days a
              confirmed regression ran through excluded. The name is legacy; it has not meant "the
              previous window" since that design was retired for normalising away step changes.

            **Evidence**
            - `member` — every sample folded into the closed window, in fold order. Span grain for a
              tool-grain measure, trace grain for a turn-grain one. Written once, when the finding
              opened: a persisting regression re-fires on later windows, and appending those would
              merge distinct populations into a pile matching none of them.
            - `baseline` — the pinned window's rows, on the `:pinned` arm ONLY.

            **Absent roles**
            - No `baseline` on a `:previous` finding is correct: a weighted ring of daily histograms
              has no rows behind it. What it compared against is described on the finding's payload
              instead, under `control` — days used, days excluded as confirmed, oldest day, half-life.
              A `:pinned` finding with no `baseline` is a genuine defect; a `:previous` one is not.
            - No `witness`: every sample in the window is a member of the shifted population, and
              nothing marks an individual one as the failure.
            - No `exemplar`, for the same reason as tool_error.
            """;

    private static final String BEHAVIOR_DRIFT = """
            ## behavior_drift — a trajectory scored against a fitted n-gram model

            **Measures** how surprising a trace's sequence of steps is under the call site's own fitted
            profile: an omission, a novelty, or a high-surprisal path.

            **Compares against** the profile — counts, not rows.

            **Evidence**
            - `exemplar` — the trace the firing was recorded on.
            - `member` — the batch's firings for this cause, accumulated across batches, at trace
              grain. A cause builds its population over time, so this is the union of every batch that
              fired it, not one batch's worth.

            **Absent roles**
            - No `baseline`, by construction. The reference is a fitted model; a model is counts, and
              there is nothing to enumerate on that side. A zero here is a statement about the method,
              not a lost write.
            """;

    private static final String SOP_CONFORMANCE = """
            ## sop_conformance — an obligation checked against the turns it applied to

            **Measures** how often an SOP rule was honoured on the turns where it was in force. Two
            different claims share this classifier, and `conformance_kind` says which: a `drift` finding
            says conformance FELL, a `baseline` finding says it was never high.

            **Compares against** either the bundle's fitted expectation model plus a stored activation
            count (the windowed drift test), or the very turns the fit ran on (the fit-time audit).

            **Evidence** — all at trace grain, all refreshed on every pass, because the window rolls and
            the union across sweeps is the traffic the deficit has actually been seen over.
            - `exemplar` — the violating turns, ranked most-surprising-first. The order is part of the
              claim.
            - `member` — the activations the violation count is a fraction of.
            - `baseline` — the fit-time audit's reference activations, where one exists as rows.
            - `changepoint` — where the deficit starts concentrating. Descriptive, not a test. No other
              classifier writes this role.

            **Absent roles**
            - No `baseline` on a windowed drift finding is correct: its reference is a fitted
              expectation model, and no set of rows survives it.
            """;

    private static final String ARMED_SIGNAL = """
            ## {key} — a per-observation detector armed on a threshold

            **Measures** whether individual observations trip this detector, and fires a finding when
            enough of them do inside one window.

            **Compares against** a threshold on the count, not against another stretch of traffic.

            **Evidence**
            - `member` — the observations that fired, written in the same transaction as the finding, so
              a finding never exists without the evidence that justified it.

            **Absent roles**
            - No `baseline`: nothing here is a two-window comparison, so there is no before side to
              enumerate.
            - No `exemplar`: the population is the claim, and every member of it is equally a way in.
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
            BuiltInDetector.Kind.SOP_CONFORMANCE, SOP_CONFORMANCE);

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
    static final java.util.Set<String> ARMED_SIGNAL_KEYS = java.util.Set.of(
            BuiltInDetector.Kind.FRUSTRATION,
            BuiltInDetector.Kind.SECRET_LEAK,
            BuiltInDetector.Kind.MALFORMED_OUTPUT,
            BuiltInDetector.Kind.GROUNDEDNESS);
}
