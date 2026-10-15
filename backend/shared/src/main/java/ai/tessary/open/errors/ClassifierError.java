// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum ClassifierError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "No classifier '%s'"),
    INVALID_MODE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid classifier mode: %s (expected 'discovery' or 'tracking')"),
    FINDING_NOT_FOUND(HttpStatus.NOT_FOUND, "No behaviour-drift finding '%s'"),
    // ux_finding_live drops a ruled finding, so zero rows means something else ruled first: moot rather than wrong.
    FINDING_CLOSED(HttpStatus.CONFLICT, "Finding '%s' has already been ruled on"),
    INVALID_RESOLUTION(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Invalid behaviour-drift resolution: %s (expected 'expected' or 'not_expected')"),
    NOT_METRIC_DRIFT(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Classifier '%s' has no window/threshold tuning — only cost_drift and duration_drift do"),
    NOT_FRUSTRATION(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Classifier '%s' has no frustration rate tuning — only the frustration classifier does"),
    UNKNOWN_CALL_SITE(HttpStatus.UNPROCESSABLE_ENTITY, "This project has no call site '%s'"),
    // Tool error buckets by tool across call sites, so a call-site scope would change what each bucket measures.
    // Refused, never ignored.
    CALL_SITE_SCOPE_UNSUPPORTED(
            HttpStatus.UNPROCESSABLE_ENTITY, "Classifier '%s' runs on every call site and cannot be limited to some"),
    INVALID_CHART_DAYS(HttpStatus.UNPROCESSABLE_ENTITY, "A chart range is 7 or 28 days, not %s"),
    CHART_SCOPE(HttpStatus.UNPROCESSABLE_ENTITY, "Pick one call site or one tool"),
    NOT_GROUNDEDNESS(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Classifier '%s' has no groundedness status — only the groundedness classifier does"),
    /** Enabling a classifier that calls a provider on the org's own key, while no such key is configured. */
    PROVIDER_REQUIRED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Classifier '%s' needs a TypeSafe or OpenRouter key. Add one under Settings, Providers, then enable it"),
    /** A reset while a worker holds the sweep job: that sweep would write its cursor back over the reset. */
    SWEEP_RUNNING(HttpStatus.CONFLICT, "Classifier '%s' is checking traces right now. Try the reset again in a minute"),
    // What blocks an analysis is citing no trace at all (nothing written, or everything aged out); a repo-less project
    // is ruled evidence-only rather than refused.
    FINDING_HAS_NO_EVIDENCE(HttpStatus.CONFLICT, "Finding '%s' cites no trace to anchor an analysis on"),
    // Thrown only by the triage worker, so a run that did not happen leaves triage_verdict NULL and retries rather than
    // permanently disqualifying the finding.
    TRIAGE_RUN_INCOMPLETE(HttpStatus.INTERNAL_SERVER_ERROR, "Triage of finding '%s' produced no ruling: %s"),
    // Separate from TRIAGE_RUN_INCOMPLETE: a launcher refusing everyone trips the breaker and refunds the attempt
    // instead of burning every job's attempts.
    TRIAGE_LAUNCHER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The triage launcher is not answering: %s"),

    /**
     * 401/403/404: a deployment mistake every identical request will hit, unlike a transient
     * {@link #TRIAGE_LAUNCHER_UNAVAILABLE}. Folded together, a key mismatch released jobs unspent at DEBUG forever.
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
