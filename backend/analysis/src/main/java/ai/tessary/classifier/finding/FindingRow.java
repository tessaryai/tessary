// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * One cause that fired, not one firing: the shared claim every classifier files. When a deploy
 * introduces a new tool, 500 traces fire on the same gram; that is one thing that happened, not
 * 500. What the classifier saw stays in its own tables and in {@link #payloadJson}; the
 * substrate it saw stays in {@code finding_evidence}, as references.
 *
 * <p>{@link #causeKey} is scoped: a single partial unique index ({@code ux_finding_live}) covers every
 * classifier, so the scope a cause is unique within lives inside the key itself, e.g.
 * {@code <baseline>:<key>} for metric drift (the baseline id carries environment scope; collapsing it would
 * silently merge findings across environments). {@link #nativeCauseKey()} gives back the classifier's own
 * half, which is what a case's identity is cut on.
 */
public record FindingRow(
        String id,
        String projectId,
        /** Which classifier filed this: a persisted string, never renamed. */
        String classifierKey,
        String causeKey,
        String subjectKind,
        String subjectId,
        @Nullable String subjectLabel,
        @Nullable String callSiteId,
        String status,
        /** The current spell's start. Moves only across an observed recovery, see the repository. */
        String onsetAt,
        String lastSeenAt,
        @Nullable String title,
        @Nullable String basis,
        @Nullable Double severity,
        /** How much traffic the claim has been observed over, in the classifier's own unit. */
        long sampleCount,
        /**
         * The classifier's own numbers, plus the native vocabulary the scoped {@link #causeKey} folds
         * in. Detector state, never a ruling: analysis writes onto the {@code triage_*} columns.
         */
        @Nullable String payloadJson,
        /**
         * {@code {role -> count}} of the {@code finding_evidence} refs written under this finding.
         * Avoids a {@code count(*)} over a table that may hold six figures for one finding. Not a
         * live count: refs age out with their substrate, so a query may come in under this number,
         * never over it.
         */
        @Nullable String evidenceCountsJson,
        @Nullable String sinceVersionId,
        @Nullable String escalatedAt,
        /**
         * Layer 2's ruling on the claim ({@link TriageVerdict}). Never a judgement about product
         * intent: a cost drop is as {@code positive} a claim as a cost rise, since "improvement"
         * needs evidence triage does not have.
         *
         * <p>Null is not a ruling. A run that did not happen, never reached the evidence, or did not
         * settle the question leaves this null and lets its job retry into the dead letter: every
         * value here was written by a run that actually established what it says.
         */
        @Nullable String triageVerdict,
        /** What the ruling did ({@link TriageAction}), fixed by the verdict and written with it. */
        @Nullable String triageAction,
        @Nullable String triageSummary,
        @Nullable String triageCitationsJson,
        @Nullable String triagedAt,
        /** When a human ruled. Their verdict outranks any later machine one. */
        @Nullable String humanVerdictAt,
        /** The case this finding opened or joined, or null while it backs none. Set once a ruling is
         *  positive; the finding stays {@link Status#OPEN} for as long as that case is unresolved. */
        @Nullable String caseId,
        String createdAt,
        String updatedAt) {

    /**
     * How many refs the detector wrote under one {@link FindingEvidenceRow.Role}, or 0 for a role it
     * wrote none under. Zero reads as "this detector had nothing to enumerate on that side": see
     * {@link FindingEvidenceRow.Role#BASELINE} for the detectors whose reference is a fitted summary.
     */
    public long evidenceCount(String role) {
        return FindingPayload.count(evidenceCountsJson, role);
    }

    /** The classifier's own cause key, with the uniqueness scope stripped back off. */
    public String nativeCauseKey() {
        String native0 = payloadText("native_cause_key");
        return native0 == null ? causeKey : native0;
    }

    /**
     * The behaviour vocabulary's cause kind ({@code distribution_shift} / {@code rate_shift} / ...). A
     * persisted string carried in the payload rather than as a column: it is one classifier family's
     * taxonomy, and {@link #classifierKey} is what every shared reader routes on.
     *
     * <p>Falls back to {@link #classifierKey} for a classifier with no such taxonomy. Non-null
     * rather than nullable: every caller here uses it as a switch selector or a log field, and a
     * null check at each site would only ever mean "some classifier we have not thought about".
     */
    public String causeKind() {
        String kind = payloadText("cause_kind");
        return kind == null ? classifierKey : kind;
    }

    /** The metric baseline this finding is about, or null when another classifier filed it. */
    public @Nullable String baselineId() {
        return SubjectKind.METRIC_BASELINE.equals(subjectKind) ? subjectId : null;
    }

    /**
     * The workflow scope a behaviour-drift cause was unique within. Folded into {@link #causeKey}
     * for uniqueness and kept in the payload for reading back, since the allowlist and the
     * human-ruling path both need it directly.
     *
     * <p>{@code __global__} for a classifier with no workflow scope, the same string the unscoped
     * writers persist: a null here would need spelling out as "the global one" at every call site,
     * and two spellings of the global scope is how an allowlist row stops matching its cause.
     */
    public String workflowKey() {
        String key = payloadText("workflow_key");
        return key == null || key.isBlank() ? GLOBAL_WORKFLOW : key;
    }

    /** The workflow scope of a cause that has none: persisted verbatim by the unscoped writers. */
    public static final String GLOBAL_WORKFLOW = "__global__";

    /** A top-level string member of {@link #payloadJson}, or null when absent or unreadable. */
    public @Nullable String payloadText(String key) {
        return FindingPayload.text(payloadJson, key);
    }

    /** The payload as a tree; an empty object when absent or malformed, a finding is still true. */
    public JsonNode payload() {
        return FindingPayload.tree(payloadJson);
    }

    /**
     * {@code finding.status} values: open or closed, and nothing else. A ruling freezes the row by
     * construction — see {@code ux_finding_live} — so status alone no longer distinguishes an
     * untriaged finding from a positively-ruled one still backing a case; {@link #triageVerdict} does
     * that.
     */
    public static final class Status {
        private Status() {}

        public static final String OPEN = "open";
        public static final String CLOSED = "closed";
    }

    /**
     * A faceted finding's detection band, carried in {@link #payloadJson}. Recorded when the finding is
     * filed rather than recomputed from detection rows, because those age out and a leaked credential's
     * finding must not read as low confidence once they do.
     */
    public static final class Confidence {
        private Confidence() {}

        public static final String PAYLOAD_KEY = "confidence";
        public static final String HIGH = "high";
        public static final String LOW = "low";
    }

    /** Whether this finding has ever counted a high-confidence detection. */
    public boolean highConfidence() {
        return Confidence.HIGH.equals(payloadText(Confidence.PAYLOAD_KEY));
    }

    /**
     * The behaviour-family cause vocabulary, carried in {@link #payloadJson}. Persisted strings,
     * never renamed: {@link #classifierKey} is what shared readers route on, and these say what
     * the classifier itself called the shape it saw.
     */
    public static final class Cause {
        private Cause() {}

        /**
         * Metric drift: a bucket's recent window sits measurably away from its own earlier window.
         * Not a per-trace label, since slow is not bad and expensive is not bad in isolation; what
         * can be judged is whether a population moved against its own past. {@link #sampleCount} is
         * how much traffic the shift was observed over, not how many traces were labelled.
         */
        public static final String DISTRIBUTION_SHIFT = "distribution_shift";

        /**
         * A per-span classifier crossed its own arming bar: N detections in a window. Written by
         * {@code ClassifierArming}, and scoped either to the whole classifier or, for a classifier that
         * facets its arming, to one call site and one facet of the detection.
         */
        public static final String ARMED_WINDOW = "armed_window";

        /**
         * A call site's outputs are failing their declared schema at a rate its own history does not
         * predict. Recomputed from an hourly aggregate on every pass, through tool_error's engine, but its own
         * kind rather than {@link #RATE_SHIFT}: the correction loop reads that kind as a tool's rate and writes
         * a tool reference, which is not something a call site's finding may ever reach.
         */
        public static final String MALFORMED_RATE = "malformed_rate";

        /**
         * A call site's share of conversations frustrated with the agent rose above the rate it learned as its
         * normal. Recomputed through tool_error's engine with a conversation as the trial, and ruled positive
         * when filed: it never goes through triage, so no correction verb ever reads it as a tool's rate.
         */
        public static final String FRUSTRATION_RATE = "frustration_rate";

        /**
         * A call site's share of traces with an answer the groundedness model flagged rose above the rate it
         * learned as its normal. Recomputed through tool_error's engine with a trace as the trial, and filed
         * unruled for triage, as {@link #MALFORMED_RATE} is.
         */
        public static final String GROUNDEDNESS_RATE = "groundedness_rate";

        /**
         * A tool's failure rate moved against its own in-control level: the {@code tool_error}
         * classifier's only cause. Deliberately not filed under {@link #DISTRIBUTION_SHIFT}, since
         * a tool-error finding under that kind would open a case labelled metric_drift and name the
         * wrong detector.
         */
        public static final String RATE_SHIFT = "rate_shift";
    }

    /** {@code finding.subject_kind} values: what the claim is about, typed. */
    public static final class SubjectKind {
        private SubjectKind() {}

        public static final String METRIC_BASELINE = "metric_baseline";
        public static final String TOOL = "tool";

        /**
         * A per-span classifier, {@code subject_id} being the signal row's id. The other two kinds
         * are fitted state a classifier compares traffic against; a per-span classifier has none, it
         * matches spans against its own definition, so a burst of matches is about the classifier
         * itself.
         */
        public static final String CLASSIFIER = "classifier";
    }

    /**
     * {@code finding.triage_verdict} values: what Layer 2 concluded about the claim, and nothing
     * else. These ask whether the finding is true: {@link #POSITIVE} it is, {@link #NEGATIVE} the
     * detector fired on something that is not there.
     */
    public static final class TriageVerdict {
        private TriageVerdict() {}

        /** The claim holds. The one verdict that opens a case, and the default findings-page filter. */
        public static final String POSITIVE = "positive";

        /**
         * The claim does not hold: the population was mis-measured, the windows measured different
         * things, or the evidence does not support what the detector asserted. Logged against the
         * detector that fired, since a run of these is a calibration problem, not a product one.
         *
         * <p>The only verdict that moves detector state. See {@code ToolErrorService.foldRuledNegative}:
         * folding a window into a reference asserts the traffic in it was normal, and only this verdict
         * asserts that. Closes the finding, deliberately: recurrence is the recovery, not a queue. A
         * cause that is real keeps firing and comes back for a second look; a cause that never fires
         * again cost nobody a decision.
         */
        public static final String NEGATIVE = "negative";

        /**
         * Whether a ruling may move the detector state behind the finding, today whether a
         * tool-error window folds into the tool's reference.
         *
         * <p>Deliberately not derived from {@link TriageAction}: a positive shares no action with a
         * negative, but the reverse — deriving the fold from the action alone — would move a baseline
         * on any verdict that happens to close, rather than only on the one that asserts the traffic
         * was ordinary.
         */
        public static boolean movesDetectorState(@Nullable String verdict) {
            return NEGATIVE.equals(verdict);
        }
    }

    /**
     * {@code finding.triage_action} values: the two things a triage run may end in. There is no
     * third, and deliberately no "needs review": every ruling either opens a case a person reads
     * or closes the finding.
     */
    public static final class TriageAction {
        private TriageAction() {}

        public static final String OPENED_CASE = "opened_case";
        public static final String CLOSED = "closed";

        /** The mapping, in the one place that has it: a positive opens, everything else closes. */
        public static String of(String verdict) {
            return TriageVerdict.POSITIVE.equals(verdict) ? OPENED_CASE : CLOSED;
        }
    }
}
