// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * The classifier-specific half of a human resolution: the detector-state write that says "this cause is
 * fine here" (or "it is not, and keep firing"), for the causes that HAVE such a state.
 *
 * <p>Everything generic about a resolution stays in {@code BehaviorTriageSource.resolve} around this
 * call — the reachability guard, the status write, the annotation on the exemplar, the baseline
 * changelog row, the ops log and the re-read. What varies is what "expected" means to the detector:
 * behaviour drift writes an allowlist row and, for a novelty, moves a gram's state; metric drift re-pins
 * a reference; tool error pins a rate. The last two are open and stay inline, because a reference is a
 * number rather than a fitted state and there is nothing to extract.
 *
 * <p><b>An absent resolver still resolves the finding.</b> Without a registered resolver for a cause
 * kind, the status write, the annotation and the changelog row all still happen — the human's judgement
 * is recorded, only the detector-state half is skipped, which is the right degradation for an edition
 * that does not ship the detector that could have written it.
 */
public interface CauseResolver {

    /**
     * Whether this resolver owns the cause kind. Ids are unique per cause, so the first owner wins and
     * the iteration ends.
     */
    boolean owns(String causeKind);

    /**
     * Apply the detector-state half. Called inside the caller's transaction, after the reachability
     * guard and BEFORE the status write, so a failure here leaves the finding un-resolved rather than
     * resolved with the detector still firing on it.
     *
     * @param now the caller's clock, so the finding's {@code human_verdict_at} and the changelog entry
     *     announcing the same judgement carry one timestamp rather than two
     * @return the classifier key the correction annotation is attributed to, or null to fall back to the
     *     detector kind. The resolver answers this because it is the only thing here that knows WHICH
     *     classifier row owns the fitted state it just wrote — a project can hold two rows for one
     *     detector, and the annotation names the one whose epoch this cause hangs off, not the first
     *     row with a matching detector.
     */
    @Nullable
    String apply(String projectId, FindingRow finding, boolean expected, @Nullable String userId, String now);
}
