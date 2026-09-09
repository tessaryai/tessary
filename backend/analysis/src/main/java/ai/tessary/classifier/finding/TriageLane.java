// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

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
 * Track A removed grading from the platform, so there are no rubrics left to run.
 *
 * <p>The enum is kept at one constant rather than folded into a literal: {@link #wire()} is on the
 * wire, {@link #subject()} / {@link #against()} / {@link #label()} are the product copy every surface
 * shares, and a second instrument is a plausible future.
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
    EVIDENCE_ONLY(
            "evidence_only",
            "an evidence-only Layer-2 run",
            "the trace evidence alone",
            "Evidence-only",
            "evidence_pointer");

    private final String wire;
    private final String subject;
    private final String against;
    private final String label;
    private final String citationKind;

    TriageLane(String wire, String subject, String against, String label, String citationKind) {
        this.wire = wire;
        this.subject = subject;
        this.against = against;
        this.label = label;
        this.citationKind = citationKind;
    }

    /**
     * The wire value — reported by the <em>Run analysis</em> press. Not persisted on the finding:
     * triage records what it ruled, not what it ruled with.
     */
    public String wire() {
        return wire;
    }

    /**
     * How a sentence names the thing that ruled — "an evidence-only Layer-2 run". Product copy lives
     * here rather than in each surface so a case, a finding row and an alert cannot describe the same
     * lane in three different ways.
     */
    public String subject() {
        return subject;
    }

    /** What that run ruled against, for the second half of the same sentence. */
    public String against() {
        return against;
    }

    /** Two words for a chip, where a sentence does not fit — the case header's lane badge. */
    public String label() {
        return label;
    }

    /**
     * What this lane's citations ARE, so a reader knows what they are looking at before they look.
     * {@code evidence_pointer} citations are dotted paths into the finding's own evidence blob, the ids
     * the agent fetched, and the check scripts it ran.
     */
    public String citationKind() {
        return citationKind;
    }

    /**
     * Resolve a wire value, or null if it is absent or unrecognized. Null rather than a throw or a
     * default: the value arrives from a press that a stale tab may have composed, and guessing a lane
     * for one of those is how a request for one instrument quietly becomes a request for another.
     */
    public static @Nullable TriageLane fromWire(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TriageLane lane : values()) {
            if (lane.wire.equals(normalized)) return lane;
        }
        return null;
    }
}
