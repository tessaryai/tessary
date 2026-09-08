// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

public enum ClassifierError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "No classifier '%s'"),
    UNKNOWN_DETECTOR(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown classifier detector: %s"),
    INVALID_MODE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid classifier mode: %s (expected 'discovery' or 'tracking')"),
    FINDING_NOT_FOUND(HttpStatus.NOT_FOUND, "No behaviour-drift finding '%s'"),
    INVALID_RESOLUTION(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Invalid behaviour-drift resolution: %s (expected 'expected' or 'not_expected')"),
    NOT_METRIC_DRIFT(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Classifier '%s' has no window/threshold tuning — only cost_drift and duration_drift do"),
    DETECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "No detection '%s' on this classifier"),
    /** The two ways a detection has nothing a grader run could evaluate — see ClassifierService#analyze. */
    DETECTION_NOT_GRADABLE(
            HttpStatus.CONFLICT, "Detection '%s' resolved no call site, so there are no graders to scope a run to"),
    NO_GRADERS_FOR_CALL_SITE(
            HttpStatus.CONFLICT, "Call site '%s' has no runnable grader, so a run would evaluate nothing"),
    // NO_REPO_TO_RULE_AGAINST used to sit here: a 409 on `Run analysis` for a project with no git
    // integration, back when the only Layer-2 lane rules against a committed spec. It is gone rather than
    // deprecated because the state it named cannot occur — a repo-less project is now ruled on the
    // evidence-only lane instead of refused (launch requirement B2), and a retired error code that can
    // still be thrown is worse than one that cannot be.
    // Named FINDING_HAS_NO_EXEMPLAR until the evidence roles were redesigned, and the rename is the
    // whole point: `exemplar` is no longer universal, so a finding can carry hundreds of witnesses and
    // members and still have had no exemplar to offer. What blocks an analysis is citing NO trace at
    // all — nothing written, or everything aged out — and that is what this now says.
    FINDING_HAS_NO_EVIDENCE(HttpStatus.CONFLICT, "Finding '%s' cites no trace to anchor an analysis on"),
    // Never reaches a controller: the triage worker is the only thrower, and it exists so that a run
    // which did not happen leaves triage_verdict NULL and lets the job retry. Before this, the engine
    // degraded to `unclear` at confidence 0 — a ruling recorded for a run nobody made, which permanently
    // disqualified the finding from ever being looked at again.
    TRIAGE_RUN_INCOMPLETE(HttpStatus.INTERNAL_SERVER_ERROR, "Triage of finding '%s' produced no ruling: %s"),
    // The launcher, not the run. Separated from TRIAGE_RUN_INCOMPLETE because the two want opposite
    // handling: a run that failed should spend an attempt and retry, while a launcher that is refusing
    // everyone will refuse the next finding too — so this one trips the breaker, refunds the attempt,
    // and stops the drain rather than burning every job's attempts against a door that is shut.
    TRIAGE_LAUNCHER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The triage launcher is not answering: %s"),

    /**
     * The launcher answered and REFUSED — 401/403 (the shared secret does not match) or 404 (its image
     * predates the route). Split out of {@link #TRIAGE_LAUNCHER_UNAVAILABLE} because the two need
     * opposite handling: an unreachable launcher is transient and worth retrying on a short breaker
     * cycle, while a refusal is a deployment mistake that every future identical request will hit the
     * same way. Folded together, a key mismatch released each job unspent at DEBUG on every tick — the
     * job never dead-lettered, never spent an attempt, and never logged anything an operator would
     * see, so a polling Case page waited on a run that could not start.
     */
    TRIAGE_LAUNCHER_MISCONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "The triage launcher refused the run: %s");

    private final HttpStatus status;
    private final String template;

    ClassifierError(HttpStatus status, String template) {
        this.status = status;
        this.template = template;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String template() {
        return template;
    }

    @Override
    public Class<? extends Enum<?>> declaringClass() {
        return ClassifierError.class;
    }
}
