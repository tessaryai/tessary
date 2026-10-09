// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

/**
 * Which instrument ruled on a finding. There is one left.
 *
 * <p>{@link #EVIDENCE_ONLY} is triage: an agent in a microVM with the finding's claim, this
 * platform's read surface, and a directory to write check scripts in. It rules on the evidence and
 * nothing else, which is the whole of its authority and also its limit — it can say a claim is true,
 * mis-measured or unsettled, and it cannot say why anything happened.
 *
 * <p><b>There were three, and two are gone.</b> {@code repo_grounded} cloned the project's repository
 * so the ruling could cite an invariant or a call site's declared intent. Triage audits a claim about
 * production traffic, and no source file settles whether such a claim is true; what the run actually
 * lacked was the traffic, which it now reads through MCP. The repository went to RCA, which asks the
 * question it answers. {@code grader} ran the call site's graders against the finding's cited traces;
 * grading was removed from the platform, so there are no rubrics left to run.
 *
 * <p>The enum is kept at one constant rather than folded into a literal: {@link #wire()} is on the
 * wire, and a second instrument is a plausible future.
 *
 * <p><b>Not {@code ModelLane.TRIAGE}.</b> That names one of the five jobs a project can point at a
 * model; this names which instrument answered one finding. They share the word because they describe
 * the same activity from opposite ends — which model runs it, versus what it was allowed to rule
 * against — and the persisted values have nothing in common.
 */
public enum TriageLane {

    /**
     * The triage agent: one microVM per finding, the finding's own two-file dossier, the platform's
     * read-only MCP surface for the substrate, and {@code checks/} for the scripts it writes.
     */
    EVIDENCE_ONLY("evidence_only");

    private final String wire;

    TriageLane(String wire) {
        this.wire = wire;
    }

    /**
     * The wire value — reported by the <em>Run analysis</em> press. Not persisted on the finding:
     * triage records what it ruled, not what it ruled with.
     */
    public String wire() {
        return wire;
    }
}
