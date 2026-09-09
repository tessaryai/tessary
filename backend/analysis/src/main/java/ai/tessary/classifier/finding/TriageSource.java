// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A finding store's adapter into the findings surface and the Layer-2 triage pipeline — the
 * {@code CaseSource} pattern one stage earlier. Two stores hold escalatable findings, and each answers
 * for its own rows here so that {@link FindingService} knows no table at all.
 *
 * <p><b>It is a full adapter, not a triage hook.</b> The seam started as {@code analyze} alone, with the
 * list merge, the detail projection and the resolve branch left inline in what is now
 * {@link FindingService} — three conformance-shaped special cases inside the generic service, which is
 * the coupling #839 removed. Every one of them is a method below, so the service iterates uniformly and
 * a third store is an implementation rather than three more branches.
 *
 * <p>Everything downstream of the press is shared and stays shared: one job kind ({@code triage}), one
 * dedupe key shape, one worker, and one rolling per-project cap —
 * {@code BehaviorTriageJobRepository#countEnqueuedSince} counts by project and kind, so a conformance
 * escalation spends from the same allowance a behaviour one does, hand-pressed included.
 *
 * <p><b>Registration order is the wire order.</b> {@code BehaviorTriageSource} is {@code @Order(0)} and
 * {@code ConformanceTriageSource} is {@code @Order(10)}; Spring sorts the injected {@code List} by that,
 * and it decides both the order rows appear on the findings page and which source answers first for a
 * given id. It is not declaration order, bean name or classpath order, and it must not become any of
 * them.
 *
 * <p><b>The ownership contract, on every {@code Optional}-returning method.</b> Return
 * {@link Optional#empty()} when the finding id is not this source's — including when this source holds
 * the row but the org's flag layer withholds its classifier, which must read as not-found rather than
 * leak by id. Throw a domain error only once ownership is established (e.g.
 * {@code FINDING_HAS_NO_EVIDENCE}): ids are unique per store, so the first source that claims one ends
 * the iteration.
 */
public interface TriageSource {

    /** Which store this is, as the {@code finding_kind} the job payload records. */
    String kind();

    /**
     * Findings automatic mode may escalate for this project: open, never escalated or triaged,
     * carrying an exemplar trace, and observed over at least {@code minObservations} samples — the
     * recurrence bar, read in each store's own unit (behaviour's trace count, conformance's window
     * activations). Strongest first, because the caller truncates to a budget.
     */
    List<Escalatable> listAutoEscalatable(String projectId, long minObservations, int limit);

    /**
     * Press <em>Run analysis</em> on one finding this source owns, or {@link Optional#empty()} when
     * the id is not this source's to press. This is the SAME method behind the button and behind
     * {@link TriageAutoEscalator} — once-per-cause, payload assembly and the ops log live here
     * exactly once per store.
     */
    Optional<BehaviorAnalysisView> analyze(String projectId, String findingId);

    /**
     * This source's rows for one page of the findings surface, already narrowed by the flag layer and by
     * whichever of the filters apply to it. Concatenated with every other source's, in {@code @Order}.
     *
     * <p>A source that this page's narrowing excludes returns an empty list rather than being skipped by
     * the caller: which filters apply to a store is the store's own knowledge.
     */
    List<BehaviorFindingView> list(
            String projectId,
            @Nullable String status,
            @Nullable String callSiteId,
            @Nullable String detector,
            boolean confirmedOnly);

    /**
     * Open findings this source is holding below the Layer-2 bar — un-triaged, or ruled
     * legitimate/unclear. Summed across sources into one count beside the list, so "nothing here" can
     * never be confused with "nothing got through". Zero is a legitimate answer for a store with no
     * such state.
     */
    long countWithheld(String projectId, @Nullable String callSiteId, boolean confirmedOnly);

    /**
     * One finding's own page, or empty when the id is not this source's. Note that this is deliberately
     * NOT the mirror of {@link #resolve}: a shared-table row keyed to another source's classifier is
     * disclaimed here (only that source's projection carries the fields its page renders) while it is
     * still claimed there. Both asymmetries are lifted verbatim from the pre-seam service.
     */
    Optional<BehaviorFindingDetailView> detail(String projectId, String findingId);

    /**
     * Record the human judgement on one finding this source owns, or empty when the id is not its own.
     * Runs inside the caller's transaction — the detector-state write, the status, the annotation and
     * the changelog entry are one judgement.
     */
    Optional<BehaviorFindingView> resolve(String projectId, String findingId, String action, @Nullable String userId);

    /**
     * The dossier and prompt for one claimed triage job, or empty when the job is not this source's — or
     * is, and its finding has been resolved while the job waited. The two cases are deliberately not
     * distinguished: both mean there is nothing to rule on and nowhere to write an answer, and both take
     * the worker's {@code markDone} path, which is exactly what the pre-seam worker did.
     */
    Optional<TriageBrief> brief(BehaviorTriageJobRow job);

    /**
     * Write the ruling onto this source's finding, plus whatever detector bookkeeping a ruling licenses
     * in this store. Called only on the source whose {@link #brief} produced the run.
     */
    void recordVerdict(
            String projectId,
            String findingId,
            BehaviorTriageVerdict verdict,
            @Nullable String citationsJson,
            String now);

    /**
     * One finding automatic mode may escalate. It used to carry an exemplar trace id, which nothing
     * ever read: the escalator passes the finding id to {@code analyze} and the agent pages the
     * population itself.
     */
    record Escalatable(String findingId, String classifierKey) {}
}
