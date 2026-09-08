// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

import java.util.List;

/**
 * A detector's adapter into the case object. Implementations answer one question — "what is firing
 * right now for this project?" — and know nothing about case lifecycle, numbering or persistence.
 *
 * <p>This is the extension point the brief calls cause-neutrality. A new detector becomes a case
 * producer by implementing this interface and adding a {@code detector} constant; Triage, the case
 * page, resolve/mute and the activity trail all pick it up with no further change.
 *
 * <p><b>The contract is "currently firing", not "newly fired".</b> {@link #detect} returns the full
 * live set every pass, and {@link CaseReconciler} closes the cases whose detections have dropped out.
 * A source that reported only new detections would leave every case it ever opened to be closed by
 * hand.
 *
 * <p><b>And therefore: an empty live set is a CLAIM.</b> It says "everything I was firing on has
 * recovered", and the ledger acts on it by closing those cases and telling the operator they are
 * fine. A source that has been switched off, withheld or otherwise stopped looking must never say
 * that — it has no opinion, which is a different answer from "nothing is wrong". {@link
 * #withheldFor} is how it says so; see {@link CaseReconciler#reconcile}.
 */
public interface CaseSource {

    /** The {@link CaseRow.Detector} constant this source produces. */
    String detector();

    /** Everything this detector is firing on for {@code projectId}, right now. */
    List<CaseDetection> detect(String projectId);

    /**
     * Whether this source has NO OPINION about {@code projectId} right now, so reconciliation must
     * skip it entirely and leave its existing cases exactly as they are.
     *
     * <p>The distinction this exists for: <b>withheld is not empty</b>. Returning an empty list from
     * {@link #detect} means every case this source opened has recovered, and the ledger closes them
     * and reports the project healthy. For a detector that has merely stopped looking — put into
     * shadow mode for validation, or whose capability was withdrawn — that is a silent false
     * all-clear on findings a human may be part-way through triaging, which is precisely the failure
     * these detectors exist to prevent. Withholding leaves those cases open and untouched for a
     * person to finish; no NEW case opens either, because the source is not consulted at all.
     *
     * <p>Default false: a source that never withholds keeps the original contract exactly, and the
     * reconciler's behaviour for every existing detector is unchanged.
     */
    default boolean withheldFor(String projectId) {
        return false;
    }
}
