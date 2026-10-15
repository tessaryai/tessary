// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * One cause that fired, not one firing: the shared claim every classifier files. {@link #causeKey} is scoped so one
 * partial unique index ({@code ux_finding_live}) covers every classifier; {@link #nativeCauseKey()} returns the
 * classifier's own half, which a case's identity is cut on.
 */
public record FindingRow(
        String id,
        String projectId,
        /** A persisted string, never renamed. */
        String classifierKey,
        String causeKey,
        String subjectKind,
        String subjectId,
        @Nullable String subjectLabel,
        @Nullable String callSiteId,
        String status,
        /** Moves only across an observed recovery. */
        String onsetAt,
        String lastSeenAt,
        @Nullable String title,
        @Nullable String basis,
        @Nullable Double severity,
        /** In the classifier's own unit. */
        long sampleCount,
        /** Detector state, never a ruling: analysis writes the {@code triage_*} columns. */
        @Nullable String payloadJson,
        /** Avoids a {@code count(*)}. Refs age out, so a live count may come in under this, never over. */
        @Nullable String evidenceCountsJson,
        @Nullable String sinceVersionId,
        @Nullable String escalatedAt,
        /**
         * A cost drop is as {@code positive} a claim as a rise. Null is not a ruling: every value was written by a run
         * that actually established it.
         */
        @Nullable String triageVerdict,
        @Nullable String triageAction,
        @Nullable String triageSummary,
        @Nullable String triageCitationsJson,
        @Nullable String triagedAt,
        /** A human verdict outranks any later machine one. */
        @Nullable String humanVerdictAt,
        /** Stays {@link Status#OPEN} while that case is unresolved. */
        @Nullable String caseId,
        String createdAt,
        String updatedAt) {

    /** Zero means the detector had nothing to enumerate on that side; see {@link FindingEvidenceRow.Role#BASELINE}. */
    public long evidenceCount(String role) {
        return FindingPayload.count(evidenceCountsJson, role);
    }

    public FindingClaim claim() {
        return new FindingClaim(
                id,
                projectId,
                classifierKey,
                causeKey,
                subjectKind,
                subjectId,
                subjectLabel,
                callSiteId,
                onsetAt,
                lastSeenAt,
                title,
                basis,
                severity,
                sampleCount,
                payloadJson,
                evidenceCountsJson,
                createdAt);
    }

    public String nativeCauseKey() {
        String native0 = payloadText("native_cause_key");
        return native0 == null ? causeKey : native0;
    }

    /**
     * Carried in the payload because {@link #classifierKey} is what shared readers route on. Falls back to
     * {@link #classifierKey}, never null, since every caller switches on it.
     */
    public String causeKind() {
        String kind = payloadText("cause_kind");
        return kind == null ? classifierKey : kind;
    }

    public @Nullable String baselineId() {
        return SubjectKind.METRIC_BASELINE.equals(subjectKind) ? subjectId : null;
    }

    /**
     * {@code __global__} when unscoped, the same string the unscoped writers persist: two spellings of the global scope
     * would stop an allowlist row matching.
     */
    public String workflowKey() {
        String key = payloadText("workflow_key");
        return key == null || key.isBlank() ? GLOBAL_WORKFLOW : key;
    }

    public static final String GLOBAL_WORKFLOW = "__global__";

    public @Nullable String payloadText(String key) {
        return FindingPayload.text(payloadJson, key);
    }

    /** An empty object when absent or malformed: the finding is still true. */
    public JsonNode payload() {
        return FindingPayload.tree(payloadJson);
    }

    /** Open or closed only; {@link #triageVerdict} tells an untriaged finding from a positively-ruled one. */
    public static final class Status {
        private Status() {}

        public static final String OPEN = "open";
        public static final String CLOSED = "closed";
    }

    /**
     * Recorded at filing rather than recomputed, because detection rows age out and a leaked credential must not then
     * read as low confidence.
     */
    public static final class Confidence {
        private Confidence() {}

        public static final String PAYLOAD_KEY = "confidence";
        public static final String HIGH = "high";
        public static final String LOW = "low";
    }

    public boolean highConfidence() {
        return Confidence.HIGH.equals(payloadText(Confidence.PAYLOAD_KEY));
    }

    /** Persisted strings, never renamed. */
    public static final class Cause {
        private Cause() {}

        /**
         * A population moved against its own past; slow or expensive alone is not bad. {@link #sampleCount} is traffic
         * observed, not traces labelled.
         */
        public static final String DISTRIBUTION_SHIFT = "distribution_shift";

        /** Written by {@code ClassifierArming}, scoped to the classifier or to one call site and facet. */
        public static final String ARMED_WINDOW = "armed_window";

        /**
         * Its own kind, not {@link #RATE_SHIFT}: the correction loop would treat that as a tool's rate and write a tool
         * reference.
         */
        public static final String MALFORMED_RATE = "malformed_rate";

        /** Ruled positive at filing and never triaged, so no correction verb reads it as a tool's rate. */
        public static final String FRUSTRATION_RATE = "frustration_rate";

        /** Filed unruled for triage, as {@link #MALFORMED_RATE} is. */
        public static final String GROUNDEDNESS_RATE = "groundedness_rate";

        /** Not {@link #DISTRIBUTION_SHIFT}, which would open a case naming the wrong detector. */
        public static final String RATE_SHIFT = "rate_shift";
    }

    public static final class SubjectKind {
        private SubjectKind() {}

        public static final String METRIC_BASELINE = "metric_baseline";
        public static final String TOOL = "tool";

        /** A per-span classifier has no fitted state, so a burst of matches is about the classifier itself. */
        public static final String CLASSIFIER = "classifier";
    }

    /** Whether the finding is true, and nothing else. */
    public static final class TriageVerdict {
        private TriageVerdict() {}

        /** The one verdict that opens a case. */
        public static final String POSITIVE = "positive";

        /**
         * Logged against the detector, since a run of these is a calibration problem. The only verdict that moves
         * detector state (see {@code ToolErrorService.foldRuledNegative}). Closes the finding: a real cause fires
         * again.
         */
        public static final String NEGATIVE = "negative";

        /**
         * Not derived from {@link TriageAction}: that would move a baseline on any verdict that closes, not only the
         * one asserting the traffic was ordinary.
         */
        public static boolean movesDetectorState(@Nullable String verdict) {
            return NEGATIVE.equals(verdict);
        }
    }

    /** No "needs review": every ruling opens a case or closes the finding. */
    public static final class TriageAction {
        private TriageAction() {}

        public static final String OPENED_CASE = "opened_case";
        public static final String CLOSED = "closed";

        public static String of(String verdict) {
            return TriageVerdict.POSITIVE.equals(verdict) ? OPENED_CASE : CLOSED;
        }
    }
}
