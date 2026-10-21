// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.RunSummary;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.GroundednessDetail;
import ai.tessary.classifier.finding.BehaviorTriageJobRepository.TriageJobState;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.classifier.malformed.MalformedOutputEvidence.MalformedDetail;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakDetail;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import ai.tessary.classifier.worker.ArmedWindowEvidence;
import ai.tessary.classifier.worker.ArmedWindowEvidence.ArmedWindowDetail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * camelCase on the wire, unlike the snake_case {@code .tessary/} bundle DTOs: this is a product read model, not the
 * synthesis contract.
 */
public final class BehaviorDtos {

    private static final com.fasterxml.jackson.databind.ObjectReader CITATIONS =
            new ObjectMapper().readerFor(new TypeReference<List<BehaviorTriageVerdict.Citation>>() {});

    private BehaviorDtos() {}

    /** {@code lane} is always the same value now that triage reads no repository. */
    public record BehaviorFindingsView(List<BehaviorFindingView> findings, String lane) {}

    /**
     * Counts only; {@code refs} stays empty for wire compatibility. {@code counts} is live and {@code recordedCounts}
     * is what was written, so live below recorded is retention. Every role appears in both, zeros included.
     */
    public record FindingEvidencePage(
            List<Object> refs,
            @Nullable String nextCursor,
            boolean rowsOmitted,
            Map<String, Long> counts,
            Map<String, Long> recordedCounts) {}

    /**
     * One evidence ref joined to the span it names. Span fields go null together when the substrate aged out or a
     * trace-grain ref has no root; tool spans never have tokens or cost.
     */
    public record EvidenceSpanView(
            String role,
            @Nullable Integer rank,
            @Nullable String sessionId,
            @Nullable String traceId,
            @Nullable String spanId,
            @Nullable String name,
            @Nullable String kind,
            @Nullable String status,
            @Nullable String level,
            @Nullable String errorType,
            @Nullable String startedAt,
            @Nullable Long latencyMs,
            @Nullable Long totalTokens,
            @Nullable Double totalCost,
            List<String> models,
            boolean notRolledUp,
            boolean partialCost,
            boolean staleTotals,
            @Nullable String callSiteId,
            /** Null where the payload aged out. */
            @Nullable String inputPreview,
            @Nullable String outputPreview,
            /** Filled only on a secret-leak finding's evidence. */
            @Nullable String secretKey,
            @Nullable String storedAs,
            /** Filled only on a malformed-output finding's evidence. */
            @Nullable String violation) {

        public static EvidenceSpanView of(FindingEvidenceRepository.SpanRef r) {
            return new EvidenceSpanView(
                    r.role(),
                    r.rank(),
                    r.sessionId(),
                    r.traceId(),
                    r.spanId(),
                    r.name(),
                    r.kind(),
                    r.status(),
                    r.level(),
                    r.errorType(),
                    r.startedAt(),
                    r.latencyMs(),
                    r.totalTokens(),
                    r.totalCost(),
                    r.models(),
                    r.notRolledUp(),
                    r.partialCost(),
                    r.staleTotals(),
                    r.callSiteId(),
                    r.inputPreview(),
                    r.outputPreview(),
                    r.secretKey(),
                    r.storedAs(),
                    r.violation());
        }
    }

    /** The counts ride every page, or ten rows of a huge population read as the whole of it. */
    public record FindingEvidenceSpanPage(
            List<EvidenceSpanView> rows,
            @Nullable String nextCursor,
            Map<String, Long> counts,
            Map<String, Long> recordedCounts) {}

    /**
     * At most one of the cause-kind blocks is set; none for behaviour drift or an unreadable blob, and the finding
     * still renders.
     */
    public record BehaviorFindingDetailView(
            BehaviorFindingView finding,
            @Nullable ShiftDetail metric,
            @Nullable RateDetail toolError,
            /**
             * DB-backed, so supplied by the caller; see
             * {@link ai.tessary.classifier.malformed.MalformedOutputDetailService#detail}.
             */
            @Nullable MalformedDetail malformedOutput,
            /** DB-backed; see {@link ai.tessary.classifier.secretleak.SecretLeakDetailService#detail}. */
            @Nullable SecretLeakDetail secretLeak,
            /** For an armed-window classifier with no richer detail of its own; null for Secret Leak. */
            @Nullable ArmedWindowDetail armedWindow,
            /** DB-backed; see {@link ai.tessary.classifier.frustration.FrustrationDetailService#detail}. */
            @Nullable FrustrationDetail frustration,
            /** DB-backed; see {@link ai.tessary.classifier.detector.groundedness.GroundednessDetailService#detail}. */
            @Nullable GroundednessDetail groundedness,
            /** Supplied by the owning adapter through {@link FindingAdapters#detail}. */
            @Nullable ClassifierDetail classifierDetail) {

        public static BehaviorFindingDetailView of(
                FindingRow row,
                @Nullable MalformedDetail malformedOutput,
                @Nullable SecretLeakDetail secretLeak,
                @Nullable FrustrationDetail frustration,
                @Nullable GroundednessDetail groundedness,
                @Nullable ClassifierDetail classifierDetail,
                @Nullable TriageJobState job,
                @Nullable RunSummary run) {
            String evidence = row.payloadJson();
            return new BehaviorFindingDetailView(
                    BehaviorFindingView.of(row, job, run),
                    FindingRow.Cause.DISTRIBUTION_SHIFT.equals(row.causeKind())
                            ? MetricFindingEvidence.detail(evidence)
                            : null,
                    FindingRow.Cause.RATE_SHIFT.equals(row.causeKind()) ? ToolErrorEvidence.detail(evidence) : null,
                    malformedOutput,
                    secretLeak,
                    secretLeak == null && FindingRow.Cause.ARMED_WINDOW.equals(row.causeKind())
                            ? ArmedWindowEvidence.detail(evidence)
                            : null,
                    frustration,
                    groundedness,
                    classifierDetail);
        }
    }

    /** {@code already_escalated}: a second press lands on the existing job rather than buying a second run. */
    public record BehaviorAnalysisView(String jobId, String jobStatus, boolean alreadyEscalated, String lane) {}

    /**
     * {@code since_version_id} is deliberately not exposed: it is whatever version was running, and "new since this
     * release" would blame every release. {@code triageCitations} is what the ruling rests on; an uncited ruling is
     * never recorded.
     */
    public record BehaviorFindingView(
            String id,
            /** Null for a scope no call site owns, such as a tool. */
            @Nullable String callSiteId,
            String causeKind,
            String causeKey,
            /** Built by {@link FindingTitle}, the same code that names the case in Triage. */
            String title,
            /**
             * Derived rather than stored, since no column records which classifier wrote a row; exposed so clients do
             * not keep a third copy of the mapping.
             */
            @Nullable String detector,
            String workflowKey,
            String firstSeenAt,
            String lastSeenAt,
            long traceCount,
            String status,
            @Nullable String triageVerdict,
            @Nullable String triageAction,
            @Nullable String triageSummary,
            List<BehaviorTriageVerdict.Citation> triageCitations,
            @Nullable String triagedAt,
            String triageStatus,
            /** Where the run stands while it has not ruled; null before a run is asked for and once it rules. */
            @Nullable TriageProgress triageProgress,
            /** The newest triage run and its current step; null before the first run starts. */
            @Nullable TriageRunView triageRun,
            @Nullable String humanVerdictAt,
            /** Null for a negative verdict, or a positive still waiting on {@code CaseOpener}. */
            @Nullable String caseId) {

        /** A null verdict alone cannot tell "not escalated yet" from a run waiting, running or given up. */
        public static final class TriageStatus {
            private TriageStatus() {}

            public static final String PENDING = "pending";

            /** Asked for, and waiting for the worker to pick it up. */
            public static final String QUEUED = "queued";

            public static final String IN_FLIGHT = "in_flight";

            /** A run ended without a ruling and the next attempt waits out its backoff. */
            public static final String RETRYING = "retrying";

            /** Held until a person changes a setting; {@link TriageProgress#heldBy} names the refusal. */
            public static final String BLOCKED = "blocked";

            public static final String DONE = "done";

            /** Read from the owning job: a failed run records no verdict and nothing clears {@code escalated_at}. */
            public static final String FAILED = "failed";
        }

        /**
         * {@code attempts} counts the run in progress; {@code startedAt} is set only while one runs. {@code heldBy} is
         * the error code of the refusal a blocked run waits on. {@code queuedAt}, {@code retryAt} and
         * {@code revivableAt} are set only while queued, retrying and failed: when it was asked for, when the next
         * attempt is due, and when a press can run it again, {@code cooldownSeconds} after it stopped.
         */
        public record TriageProgress(
                int attempts,
                int maxAttempts,
                @Nullable String startedAt,
                @Nullable String heldBy,
                @Nullable String queuedAt,
                @Nullable String retryAt,
                @Nullable String revivableAt,
                long cooldownSeconds) {}

        /**
         * One triage run: {@code doing} is the step running now, {@code lastStep} the newest step's words and
         * {@code note} the agent's newest words between tool calls, each with its time from {@code startedAt};
         * {@code outcome} is how the run ended and {@code stopCode} the error code it ended on. Each is null while
         * nothing applies.
         */
        public record TriageRunView(
                String id,
                int attempt,
                String startedAt,
                @Nullable String endedAt,
                int stepCount,
                @Nullable String doing,
                @Nullable String lastStep,
                @Nullable Long lastStepMs,
                @Nullable String note,
                @Nullable Long noteMs,
                @Nullable String outcome,
                @Nullable String stopCode) {

            static @Nullable TriageRunView of(@Nullable RunSummary run) {
                if (run == null) return null;
                AgentRunRepository.Outcome outcome = run.outcome();
                return new TriageRunView(
                        run.id(),
                        run.attempt(),
                        run.startedAt(),
                        run.endedAt(),
                        run.stepCount(),
                        run.doing(),
                        run.lastStep(),
                        run.lastStepMs(),
                        run.note(),
                        run.noteMs(),
                        outcome == null ? null : outcome.wire(),
                        run.stopCode());
            }
        }

        private static String triageStatus(FindingRow row, @Nullable TriageJobState job) {
            if (row.triagedAt() != null) return TriageStatus.DONE;
            if (row.escalatedAt() == null) return TriageStatus.PENDING;
            // A job that ended with no ruling: the finding was closed while it waited, and nothing is left to run.
            if (job == null)
                return FindingRow.Status.OPEN.equals(row.status()) ? TriageStatus.IN_FLIGHT : TriageStatus.PENDING;
            if (BehaviorTriageJobRow.DEAD.equals(job.status())) return TriageStatus.FAILED;
            if (job.leaseOwner() != null) return TriageStatus.IN_FLIGHT;
            // The last attempt's failure reads as given up before the sweep marks the job dead.
            if (job.attempts() >= job.maxAttempts() && job.lastError() != null) return TriageStatus.FAILED;
            if (job.holdCode() != null) return TriageStatus.BLOCKED;
            return job.lastError() != null ? TriageStatus.RETRYING : TriageStatus.QUEUED;
        }

        private static @Nullable TriageProgress triageProgress(
                String status, @Nullable TriageJobState job, @Nullable RunSummary run) {
            if (job == null || TriageStatus.DONE.equals(status) || TriageStatus.PENDING.equals(status)) return null;
            return new TriageProgress(
                    job.attempts(),
                    job.maxAttempts(),
                    TriageStatus.IN_FLIGHT.equals(status) ? startedAt(job, run) : null,
                    TriageStatus.BLOCKED.equals(status) ? job.holdCode() : null,
                    TriageStatus.QUEUED.equals(status) ? job.queuedAt() : null,
                    TriageStatus.RETRYING.equals(status) ? job.leaseExpiresAt() : null,
                    TriageStatus.FAILED.equals(status) ? revivableAt(job) : null,
                    job.cooldownSeconds());
        }

        /** The open run's start; the claim's time only in the moment before the worker opens the run. */
        private static String startedAt(TriageJobState job, @Nullable RunSummary run) {
            return run != null && run.endedAt() == null ? run.startedAt() : job.updatedAt();
        }

        private static @Nullable String revivableAt(TriageJobState job) {
            try {
                return Instant.parse(job.updatedAt())
                        .plusSeconds(job.cooldownSeconds())
                        .toString();
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        /** A malformed blob degrades to none rather than failing the row. */
        public static List<BehaviorTriageVerdict.Citation> citations(@Nullable String json) {
            if (json == null || json.isBlank()) return List.of();
            try {
                return CITATIONS.readValue(json);
            } catch (Exception e) {
                return List.of();
            }
        }

        /** Carries no evidence: the population is paged through {@code GET /findings/{id}/evidence}. */
        public static BehaviorFindingView of(FindingRow row, @Nullable TriageJobState job, @Nullable RunSummary run) {
            String triageStatus = triageStatus(row, job);
            return new BehaviorFindingView(
                    row.id(),
                    row.callSiteId(),
                    row.causeKind(),
                    // The classifier's OWN key: a case cuts its identity on this string, so the scoped form would
                    // change what every case is about.
                    row.nativeCauseKey(),
                    FindingTitle.of(row),
                    row.classifierKey(),
                    row.workflowKey(),
                    row.onsetAt(),
                    row.lastSeenAt(),
                    row.sampleCount(),
                    row.status(),
                    row.triageVerdict(),
                    row.triageAction(),
                    row.triageSummary(),
                    citations(row.triageCitationsJson()),
                    row.triagedAt(),
                    triageStatus,
                    triageProgress(triageStatus, job, run),
                    TriageRunView.of(run),
                    row.humanVerdictAt(),
                    row.caseId());
        }

        public static BehaviorFindingView of(FindingRow row) {
            return of(row, null, null);
        }

        /**
         * RCA must reach its own conclusion, and "nothing happened here" is a supported one, so the ruling is removed,
         * and with it the triage run, whose step words show what triage read. {@code triageStatus} and {@code caseId}
         * survive: they carry no opinion about the claim.
         */
        public BehaviorFindingView withoutTriage() {
            return new BehaviorFindingView(
                    id,
                    callSiteId,
                    causeKind,
                    causeKey,
                    title,
                    detector,
                    workflowKey,
                    firstSeenAt,
                    lastSeenAt,
                    traceCount,
                    status,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    triageStatus,
                    triageProgress,
                    null,
                    // Nulled with the verdict, or it would tell RCA which way a person decided.
                    null,
                    caseId);
        }
    }

    /** Exactly three outcomes, and the third is silence: an unengaged customer's noise decays on its own. */
    public record BehaviorResolutionRequest(@NotBlank String action) {

        public static final String EXPECTED = "expected";
        public static final String NOT_EXPECTED = "not_expected";
    }
}
