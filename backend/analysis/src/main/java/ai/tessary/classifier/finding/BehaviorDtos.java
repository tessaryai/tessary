// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.BehaviorTriageJobRepository.FailedTriage;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The wire shapes of the behaviour-drift read surface. Field names are camelCase on the wire here,
 * deliberately unlike the snake_case {@code .tessary/} bundle DTOs: nothing in this surface is part
 * of the synthesis output contract: it is a product read model consumed only by the app's own
 * Classifiers / Baseline-changelog views.
 */
public final class BehaviorDtos {

    /** Reader for the stored citation array; view-local so the DTO stays a pure mapping. */
    private static final com.fasterxml.jackson.databind.ObjectReader CITATIONS =
            new ObjectMapper().readerFor(new TypeReference<List<BehaviorTriageVerdict.Citation>>() {});

    private BehaviorDtos() {}

    /**
     * The findings page: what the Layer-2 gate let through, and enough context to read that honestly.
     *
     * @param withheld open findings the gate is holding: un-triaged, or ruled legitimate/unclear.
     *     Surfaced as a count so "nothing here" can never be confused with "nothing got through".
     * @param lane which Layer-2 lane this project's findings are ruled on, as
     *     {@link TriageLane#wire()}. Always the same value: triage reads no repository, so there
     *     is nothing left for it to vary with.
     */
    public record BehaviorFindingsView(List<BehaviorFindingView> findings, long withheld, String lane) {}

    /**
     * One row of {@code finding_evidence} on the wire: a reference into substrate, never a copy.
     *
     * <p>{@code grain} is redundant with which id is set and is sent anyway: a client rendering a mixed
     * list should not have to re-derive the rule that a span reference carries both ids (span identity
     * is the composite {@code (project_id, trace_id, id)}) while a trace reference carries one.
     *
     * @param role which set this belongs to: {@code exemplar} / {@code member} / {@code baseline} /
     *     {@code witness} / {@code changepoint}
     * @param rank the detector's own order within the role (conformance ranks exemplars
     *     most-surprising-first), or null where the set is unordered
     */
    public record EvidenceRefView(
            String grain,
            @Nullable String sessionId,
            @Nullable String traceId,
            @Nullable String spanId,
            String role,
            @Nullable Integer rank) {

        public static EvidenceRefView of(FindingEvidenceRow row) {
            return new EvidenceRefView(
                    row.grain(), row.sessionId(), row.traceId(), row.spanId(), row.role(), row.rank());
        }

        /** The trace-grain exemplars a legacy caller used to read off a single column. */
        public static List<EvidenceRefView> exemplarTraces(List<String> traceIds) {
            List<EvidenceRefView> out = new java.util.ArrayList<>();
            for (int i = 0; i < traceIds.size(); i++) {
                out.add(new EvidenceRefView("trace", null, traceIds.get(i), null, FindingEvidenceRow.Role.EXEMPLAR, i));
            }
            return List.copyOf(out);
        }
    }

    /**
     * A page of one finding's evidence refs, plus both readings of how big the set is.
     *
     * <p>What this returns is ids, never bodies: an agent follows a ref with {@code get_trace} /
     * {@code get_span} / {@code list_spans}, so a citation and the thing cited are the same identifier.
     *
     * <p>The two count maps disagree on purpose. {@code counts} is a live {@code count(*)}: what
     * survives and can still be opened. {@code recordedCounts} is {@code finding.evidence_counts},
     * written by the same call that inserted the rows. Live below recorded is retention (refs age
     * out with the substrate they point at); live above recorded cannot happen. Both zero for a
     * role is a fact about the detector, not a failed write: behaviour drift, conformance's
     * windowed drift test, and metric drift's rolling-control arm all compare against a fitted
     * summary and have no {@code baseline} rows to enumerate (see
     * {@link FindingEvidenceRow.Role#BASELINE}). Every role appears in both maps, zeros included,
     * so "none" is never indistinguishable from "not reported".
     *
     * @param rowsOmitted true when the caller asked for counts only, so an empty {@code refs}
     *     beside non-zero counts doesn't read as an evidence set that vanished.
     */
    public record FindingEvidencePage(
            List<EvidenceRefView> refs,
            @Nullable String nextCursor,
            boolean rowsOmitted,
            Map<String, Long> counts,
            Map<String, Long> recordedCounts) {}

    /**
     * One row of the finding page's evidence table: the ref, and the span it names.
     *
     * <p>The unit here is whatever the detector measured; for tool error and both drift measures
     * that is a span, one ref per call. Rendering these as traces would print the same trace four
     * times with four identical cells.
     *
     * <p>Every span field is nullable, and they go null together in two situations a reader must
     * tell apart from the ids beside them: the substrate aged out from under a ref that was real
     * when it was written, or the ref is trace-grain and the trace has no logical root to stand in
     * for it. {@code tokens} and {@code cost} are a third case, not a gap: a tool span has neither,
     * so those cells are empty on every tool-error row by construction.
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
            @Nullable String model,
            @Nullable String callSiteId,
            /** The head of what the span was given and what it returned: see
             *  {@code FindingEvidenceRepository.SpanRef}. Null where the payload aged out. */
            @Nullable String inputPreview,
            @Nullable String outputPreview) {

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
                    r.model(),
                    r.callSiteId(),
                    r.inputPreview(),
                    r.outputPreview());
        }
    }

    /**
     * A page of the evidence table, with the same two counters {@link FindingEvidencePage} carries.
     *
     * <p>The counts ride every page rather than the first only, because they are what the table's footer
     * says: a reader who has paged 40 of 27,142 rows needs the 27,142 in front of them, or ten rows of a
     * huge population read as the whole of it.
     */
    public record FindingEvidenceSpanPage(
            List<EvidenceSpanView> rows,
            @Nullable String nextCursor,
            Map<String, Long> counts,
            Map<String, Long> recordedCounts) {}

    /**
     * One finding with its evidence parsed: what the finding's own page is drawn from. A finding's
     * argument is quantitative (two distributions, the workload that did or did not move with them,
     * and for a rate shift the signature that took over), so this view renders the numbers
     * themselves rather than a prose summary of them.
     *
     * <p>Exactly one of {@code metric} and {@code toolError} is set, chosen by cause kind, and both
     * are null for a behaviour-drift cause (which carries no measured shift) or for any finding
     * whose blob is missing or unreadable. A caller renders the finding regardless: the headline
     * and the verdict don't depend on the evidence parsing, and a page that vanished because one
     * column was malformed would be a worse failure than a page with no chart on it.
     */
    public record BehaviorFindingDetailView(
            BehaviorFindingView finding,
            @Nullable ShiftDetail metric,
            @Nullable RateDetail toolError,
            /**
             * Set exactly on a BASELINE conformance finding, and the only evidence block that is not a
             * measured shift. Null on everything else, including a conformance DRIFT finding, whose
             * argument is the rates in its title.
             */
            @Nullable ConformanceBaselineView baseline) {

        public static BehaviorFindingDetailView of(FindingRow row) {
            String evidence = row.payloadJson();
            return new BehaviorFindingDetailView(
                    BehaviorFindingView.of(row),
                    FindingRow.Cause.DISTRIBUTION_SHIFT.equals(row.causeKind())
                            ? MetricFindingEvidence.detail(evidence)
                            : null,
                    FindingRow.Cause.RATE_SHIFT.equals(row.causeKind()) ? ToolErrorEvidence.detail(evidence) : null,
                    null);
        }
    }

    /**
     * What a rule was already failing when its detector was fitted: a count over the fit's own
     * reference period, with no test behind it.
     *
     * <p>Deliberately carries no rate, no z, and no expected-vs-observed pair: those were never
     * computed for a baseline, and printing {@code 0.0%} where a statistic should be would be
     * claiming one was.
     *
     * @param applicableTurns how many activations the violations were counted over
     * @param violatingTraceIds every violating trace, not a sample: the audit pins the population,
     *     and a ruling is only reproducible against the set the fit actually saw
     * @param fittedAt the epoch this audit describes ({@code first_seen_at}), when the rulebook
     *     was fitted rather than when anything changed
     */
    public record ConformanceBaselineView(
            String ruleKey, long applicableTurns, int violations, List<String> violatingTraceIds, String fittedAt) {}

    /**
     * What a hand-pressed <em>Run analysis</em> on a finding produced.
     *
     * <p>{@code already_escalated} says the finding had been handed to Layer 2 before: a second press
     * lands on the existing job rather than buying a second run, so reporting a fresh one would be a lie.
     * The UI reads it to say "already analyzed" instead.
     *
     * <p>{@code lane} is the lane selected at the moment of the press. Reported rather than left implicit
     * because the two lanes answer with different authority and a user pressing the button deserves to
     * know which one they are buying before the verdict lands on the row.
     */
    public record BehaviorAnalysisView(String jobId, String jobStatus, boolean alreadyEscalated, String lane) {}

    /**
     * One cause that fired, with the exemplar the Layer-2 escalation was pointed at.
     *
     * <p>{@code behavior_finding.since_version_id} is deliberately not exposed: the sweep sets it
     * to the exemplar's project version, which is the version that happened to be running, not
     * evidence that a deploy caused the finding. Rendering it as "new since &lt;sha&gt;" would
     * attribute every finding on a version-tagged project to a release.
     *
     * <p>The triage fields are exposed, and they are a decision rather than a second opinion: the
     * ruling decided whether a person ever sees this finding ({@code positive} opened a case,
     * {@code negative} and {@code unclear} closed it). It doesn't touch detector state: the status,
     * the allowlist, and the reference are still only a human's to move.
     *
     * <p>{@code triageCitations} carries what the ruling rests on: evidence pointers, repo paths,
     * and the agent's own check scripts. An uncited ruling is already downgraded to
     * {@code unclear}, so the citations are the reason a human should believe a cited one.
     */
    public record BehaviorFindingView(
            String id,
            /** Null for a finding filed against a scope no call site owns: an SOP rule, or a tool. */
            @Nullable String callSiteId,
            String causeKind,
            String causeKey,
            /**
             * The cause as a sentence (e.g. {@code "policy-gpt.member-chat turns are 1.47x more
             * expensive"}), built from this finding's own evidence by {@link FindingTitle}, which is
             * the same code that names the case this finding opens in Triage. Falls back to
             * {@link #causeKey()} for a cause that carries no measured magnitude.
             */
            String title,
            /**
             * The classifier that opened this finding, as a {@code BuiltInDetector.Kind}, or null for
             * a cause no classifier here claims.
             *
             * <p>Derived by the classifier itself rather than stored: three classifiers share this
             * table and no column records which wrote a row. It is exposed because the findings queue
             * tags every row with its detector, and a client re-deriving it from {@code causeKind}
             * would be a third copy of a mapping that must not drift.
             */
            @Nullable String detector,
            String workflowKey,
            String firstSeenAt,
            String lastSeenAt,
            long traceCount,
            /**
             * What the claim is based on: references into substrate, the finding's own evidence set,
             * in the order the detector wrote it. A reader who wants only the exemplar gets it as the
             * first element of {@code role='exemplar'}.
             *
             * <p>Empty is a real state, not a failure: a finding whose traces have aged out keeps its
             * claim and loses its evidence, and the page says so rather than 404ing.
             */
            List<EvidenceRefView> evidence,
            String status,
            @Nullable String triageVerdict,
            /**
             * What the ruling did: {@code opened_case} or {@code closed}. Fixed by the verdict, so it
             * carries no information the verdict doesn't; it is exposed because it is what the reader
             * is actually being told, and a client deriving it would be a second copy of the mapping.
             */
            @Nullable String triageAction,
            @Nullable String triageSummary,
            List<BehaviorTriageVerdict.Citation> triageCitations,
            @Nullable String triagedAt,
            String triageStatus,
            /** When a human ruled on this cause; null while it is still an unreviewed lead. */
            @Nullable String humanVerdictAt,
            /**
             * Firings since that ruling. Non-zero on a BLOCKED finding is the strongest thing this
             * feature can say: the agent is doing something its owner explicitly said it must not do,
             * and it has happened this many times since they said so.
             */
            long recurrencesSinceVerdict,
            /**
             * Which of the two claims an SOP-conformance row is making: {@code drift} ("this got
             * worse") or {@code baseline} ("this has always been broken"). Null for every finding
             * that is not one.
             *
             * <p>Exposed because the two are read differently and nothing else on the row says which:
             * a baseline's {@link #traceCount()} is the population its violations were counted over,
             * not a number of firings, and a reader who took it for one would read a fitted fact as a
             * recurring event.
             */
            @Nullable String conformanceKind) {

        /**
         * Where this finding is in the Layer-2 pipeline. A null verdict alone is ambiguous: it is
         * the state for "not escalated yet", "deferred by the sweep cap", "in flight", and "gave up
         * after maxAttempts" alike, and an operator reading the table cannot tell a finding nobody
         * has looked at from one the analysis failed on.
         */
        public static final class TriageStatus {
            private TriageStatus() {}

            /**
             * Not scheduled yet: the finding has not crossed the recurrence bar, the org has not
             * opted into autonomy, or nobody has pressed <em>Run analysis</em> on it.
             */
            public static final String PENDING = "pending";

            /** Scheduled; the microVM has not reported back yet. */
            public static final String IN_FLIGHT = "in_flight";

            /** A verdict was recorded. */
            public static final String DONE = "done";

            /**
             * Scheduled, and the run gave up: the job spent every attempt and dead-lettered.
             *
             * <p>Indistinguishable from {@link #IN_FLIGHT} on the {@code finding} table alone, because
             * a failed run records no verdict and nothing clears {@code escalated_at}. It is read
             * from the job that owns the finding.
             */
            public static final String FAILED = "failed";
        }

        private static String triageStatus(FindingRow row, @Nullable FailedTriage failed) {
            if (row.triagedAt() != null) return TriageStatus.DONE;
            if (row.escalatedAt() == null) return TriageStatus.PENDING;
            return failed == null ? TriageStatus.IN_FLIGHT : TriageStatus.FAILED;
        }

        /**
         * Parse the stored citations; a malformed blob degrades to none rather than failing the row.
         *
         * <p>Public because the conformance projection reads the same {@code triage_citations}
         * column through the same reader; two copies of "a bad blob means no citations" is one too
         * many.
         */
        public static List<BehaviorTriageVerdict.Citation> citations(@Nullable String json) {
            if (json == null || json.isBlank()) return List.of();
            try {
                return CITATIONS.readValue(json);
            } catch (Exception e) {
                return List.of();
            }
        }

        /**
         * The list form, carrying the finding's dead-lettered triage when it has one: read once for
         * the whole page, because the {@code finding} table cannot tell a failed run from a running
         * one.
         *
         * <p>Carries no evidence: a list render that attached every ref of every finding would ship
         * an unbounded population to draw a page that shows none of them. The population is paged
         * through {@code GET /findings/{id}/evidence}. {@code evidence} survives on this record
         * because the conformance projection fills it from its own {@code exemplar_trace_ids}
         * column, a handful of ids and not a population.
         */
        public static BehaviorFindingView of(FindingRow row, @Nullable FailedTriage failed) {
            return build(row, List.of(), failed);
        }

        /** The evidence-less form, for a caller rendering a row whose set it has not read. */
        public static BehaviorFindingView of(FindingRow row) {
            return build(row, List.of(), null);
        }

        private static BehaviorFindingView build(
                FindingRow row, List<EvidenceRefView> evidence, @Nullable FailedTriage failed) {
            return new BehaviorFindingView(
                    row.id(),
                    row.callSiteId(),
                    row.causeKind(),
                    // The classifier's OWN key, with the storage scope stripped back off. `cause_key`
                    // carries the profile or baseline it is unique within so one partial index can
                    // replace four; a case cuts its identity on this string, so shipping the scoped form
                    // would change what every case is ABOUT the moment the tables merged.
                    row.nativeCauseKey(),
                    FindingTitle.of(row),
                    row.classifierKey(),
                    row.workflowKey(),
                    row.onsetAt(),
                    row.lastSeenAt(),
                    row.sampleCount(),
                    evidence,
                    row.status(),
                    row.triageVerdict(),
                    row.triageAction(),
                    row.triageSummary(),
                    citations(row.triageCitationsJson()),
                    row.triagedAt(),
                    triageStatus(row, failed),
                    row.humanVerdictAt(),
                    row.recurrencesSinceVerdict(),
                    null);
        }

        /**
         * This view with the triage ruling removed, for the agent-facing surface.
         *
         * <p>Layer-3 RCA receives a finding id and nothing else: no ruling, no summary, no
         * rule-outs, not even the fact that a triage pass happened. "Nothing happened here" is a
         * supported RCA conclusion, and it is the only check on the triage gate, so an RCA that
         * read the ruling first would be worth nothing to the engineer who acts on it.
         *
         * <p>{@code triageStatus} survives deliberately: it says whether a ruling exists, which the
         * findings list needs to render at all, and it carries no opinion about the claim.
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
                    evidence,
                    status,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    triageStatus,
                    humanVerdictAt,
                    recurrencesSinceVerdict,
                    conformanceKind);
        }
    }

    /** One entry of the baseline changelog. */
    public record BehaviorBaselineEventView(
            String id,
            String event,
            @Nullable String workflowKey,
            @Nullable String gramKey,
            String occurredAt,
            @Nullable String detail) {

        public static BehaviorBaselineEventView of(BehaviorBaselineEventRow row) {
            return new BehaviorBaselineEventView(
                    row.id(), row.event(), row.workflowKey(), row.gramKey(), row.occurredAt(), row.detailJson());
        }
    }

    /**
     * The correction. Exactly three outcomes exist and the third is silence. The critical property
     * is that the default is right: an unengaged customer's noise decays on its own.
     */
    public record BehaviorResolutionRequest(@NotBlank String action) {

        public static final String EXPECTED = "expected";
        public static final String NOT_EXPECTED = "not_expected";
    }
}
