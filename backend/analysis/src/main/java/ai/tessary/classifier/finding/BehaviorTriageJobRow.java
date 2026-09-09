// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.model.JobStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * One Layer-2 drift-triage job on the unified {@code job} table
 * ({@code kind='triage'}): the finding to analyze, carried in the {@code payload} jsonb.
 *
 * <p><b>No trace, no session, no deploy.</b> The payload used to carry an exemplar trace, the session it
 * belonged to and the version it ran under, all resolved from one sampled row. Nothing ever read the
 * first two, and the third existed only to name a commit in the prompt — one trace's deploy, stated as
 * the finding's. Handing an agent a chosen instance decides which one it anchors on, and it cannot tell
 * our pick from its own draw. The finding id is the whole job now; the population is behind MCP.
 *
 * <p>Natural key {@code dedupe_key = <project_id>:<finding_id>} (unique
 * {@code ux_job_triage}) — one run per look, and a finding only gets a second look when its cause
 * recurs past the re-open threshold.
 * Terminal {@code dead}, not {@code failed}: triage is advisory evidence, so a failed analysis must not
 * re-spend a microVM on every subsequent sweep — but neither may it be unreachable forever, which
 * {@code failed} made it. {@code BehaviorTriageJobRepository#enqueue} splices the shared dead-letter
 * cooldown gate, so a human pressing <em>Run triage</em> revives the job once its floor has passed and
 * the automatic paths (which cannot reach an escalated finding at all) still cannot.
 *
 * @param verdictId the drift verdict behind the finding, so the analysis can be attributed back to the
 *     detection that asked for it. Read off the FINDING, not off any trace.
 * @param findingKind which {@link TriageSource} enqueued this — the store the finding id
 *     resolves in. Null on every pre-seam job and on behaviour-table escalations, both of which mean
 *     {@code behavior_finding}.
 * @param conformanceJson the SOP context of a conformance-kind job, verbatim from the payload's
 *     {@code conformance} object — see {@link Conformance}. The rule sentence and obligation ride the
 *     JOB rather than being re-read at run time, because the SOP can be re-authored while the job
 *     waits and the ruling must be about the rule that fired.
 */
public record BehaviorTriageJobRow(
        String id,
        String projectId,
        String findingId,
        @Nullable String verdictId,
        String classifierKey,
        String status,
        @Nullable String leaseOwner,
        @Nullable String leaseExpiresAt,
        int attempts,
        @Nullable String lastError,
        String createdAt,
        String updatedAt,
        @Nullable String findingKind,
        @Nullable String conformanceJson) {

    /**
     * The terminal state an exhausted triage parks in — the cooldown-gated {@code dead} the signal and
     * embedding queues already use, so the one revival path ({@code enqueue}'s cooldown gate) and the
     * park statement cannot drift apart. Migration 0022 moved every pre-existing {@code failed} triage
     * row here, so this queue writes and reads exactly one terminal status.
     */
    public static final String DEAD = JobStatus.DEAD;

    /** Whether this job's finding lives in {@code conformance_finding} rather than the behaviour table. */
    public boolean isConformance() {
        return BuiltInDetector.Kind.SOP_CONFORMANCE.equals(findingKind);
    }

    /**
     * What the E2B triage agent needs to rule a conformance finding against the SOP instead of against
     * n-gram causes: the authored rule sentence, the expect/never obligation, and the evidence of
     * whichever KIND of finding this is.
     *
     * <p>A {@code drift} finding carries the tested window's numbers — the rates, the one-sided z and
     * p, and how many activations they were computed over. A {@code baseline} finding
     * ({@code conformance_finding.kind='baseline'}) carries none of them and carries
     * {@code n_violations} instead: it is a COUNT over the fit's own reference period, not a test,
     * and a payload that invented a z for it would be handing the triage agent a statistic that was
     * never computed.
     *
     * @param kind {@code drift} or {@code baseline}; absent on every pre-0072 payload, which means
     *     drift
     * @param nViolations how many violations a baseline audit pinned; 0 on a drift payload, whose
     *     exemplars are a selection rather than the population
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conformance(
            @JsonProperty("rule_key") String ruleKey,
            @JsonProperty("rule_sentence") String ruleSentence,
            @JsonProperty("obligation") String obligation,
            @JsonProperty("reference_rate") @Nullable Double referenceRate,
            @JsonProperty("current_rate") @Nullable Double currentRate,
            @JsonProperty("z") @Nullable Double z,
            @JsonProperty("p") @Nullable Double p,
            @JsonProperty("n_activations") long nActivations,
            @JsonProperty("kind") @Nullable String kind,
            @JsonProperty("n_violations") long nViolations) {

        public static final String KIND_DRIFT = "drift";
        public static final String KIND_BASELINE = "baseline";

        /** A windowed-drift payload — the shape this record has carried since it existed. */
        public static Conformance drift(
                String ruleKey,
                String ruleSentence,
                String obligation,
                double referenceRate,
                double currentRate,
                double z,
                double p,
                long nActivations) {
            return new Conformance(
                    ruleKey, ruleSentence, obligation, referenceRate, currentRate, z, p, nActivations, KIND_DRIFT, 0);
        }

        /** A fit-time baseline payload: a count over the reference period, with no test behind it. */
        public static Conformance baseline(
                String ruleKey, String ruleSentence, String obligation, long nActivations, long nViolations) {
            return new Conformance(
                    ruleKey,
                    ruleSentence,
                    obligation,
                    null,
                    null,
                    null,
                    null,
                    nActivations,
                    KIND_BASELINE,
                    nViolations);
        }

        public boolean isBaseline() {
            return KIND_BASELINE.equals(kind);
        }

        /** The tested window's numbers, present exactly on a drift payload. */
        public record DriftNumbers(double referenceRate, double currentRate, double z, double p) {}

        /** @throws IllegalStateException when this payload carries no window (a baseline) */
        public DriftNumbers driftNumbers() {
            if (referenceRate == null || currentRate == null || z == null || p == null) {
                throw new IllegalStateException("conformance payload for " + ruleKey + " carries no window numbers");
            }
            return new DriftNumbers(referenceRate, currentRate, z, p);
        }

        /** The payload's {@code conformance} object; null when absent or unreadable. */
        public static @Nullable Conformance parse(ObjectMapper mapper, @Nullable String json) {
            if (json == null || json.isBlank()) return null;
            try {
                return mapper.readValue(json, Conformance.class);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
