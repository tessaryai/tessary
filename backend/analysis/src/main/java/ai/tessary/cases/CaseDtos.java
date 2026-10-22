// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.GroundednessDetail;
import ai.tessary.classifier.finding.ClassifierDetail;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.classifier.malformed.MalformedOutputEvidence.MalformedDetail;
import ai.tessary.classifier.metric.MetricFindingEvidence.ShiftDetail;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakDetail;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaDtos.RcaRunView;
import ai.tessary.rca.RcaReportRepository.CaseLead;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Snake_case on the wire. */
public final class CaseDtos {

    private CaseDtos() {}

    public record CaseView(
            String id,
            String reference,
            String detector,
            @JsonProperty("subject_kind") String subjectKind,
            @JsonProperty("subject_id") String subjectId,
            @JsonProperty("subject_label") String subjectLabel,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            String metric,
            String state,
            String title,
            /** In the detector's own terms; detectors do not share a threshold, so this is never normalized. */
            String basis,
            /** 0..1 for ordering only; clients must not render it as a number. */
            double severity,
            @JsonProperty("onset_at") String onsetAt,
            @JsonProperty("current_value") @Nullable Double currentValue,
            @JsonProperty("baseline_value") @Nullable Double baselineValue,
            @Nullable Double delta,
            @JsonProperty("opened_at") String openedAt,
            @JsonProperty("last_seen_at") String lastSeenAt,
            @JsonProperty("resolved_at") @Nullable String resolvedAt,
            @Nullable String resolution,
            @JsonProperty("resolution_reason") @Nullable String resolutionReason,
            @JsonProperty("resolved_by") @Nullable String resolvedBy,
            /** Set only on a resolved frustration or groundedness case. */
            @Nullable String disposition,
            /**
             * The newest finding stands in for the case's numbers until the multi-finding case page (issue #76) ships.
             */
            @JsonProperty("finding_count") long findingCount,
            /**
             * Null for an archived case that predates {@code finding.case_id}. On the list row because the classifiers
             * queue resolves finding to case without a fetch per case.
             */
            @JsonProperty("latest_finding_id") @Nullable String latestFindingId,
            /** A pressed case is locked against new findings joining it. */
            @JsonProperty("locked_at") @Nullable String lockedAt,
            /** Null is the common case: most cases have never been analyzed. */
            @Nullable String cause,
            /**
             * Carried beside {@code cause} because they can disagree: an {@code inconclusive} run still summarizes its
             * leads.
             */
            @JsonProperty("rca_verdict") @Nullable String rcaVerdict,
            /** {@code queued}, {@code running} or {@code stopped} from the newest report; null when none is waiting. */
            @JsonProperty("rca_run_state") @Nullable String rcaRunState,
            /** When that state began. */
            @JsonProperty("rca_run_since") @Nullable String rcaRunSince) {

        public static CaseView of(CaseRow row) {
            return of(row, null);
        }

        public static CaseView of(CaseRow row, @Nullable CaseLead lead) {
            return of(row, lead, null);
        }

        /** @param run where the newest report's analysis stands; a done one leaves no mark */
        public static CaseView of(CaseRow row, @Nullable CaseLead lead, @Nullable RcaRunView run) {
            RcaRunView mark = run != null && !RcaRunView.DONE.equals(run.state()) ? run : null;
            return new CaseView(
                    row.id(),
                    row.reference(),
                    row.detector(),
                    row.subjectKind(),
                    row.subjectId(),
                    row.subjectLabel(),
                    row.callSiteId(),
                    row.metric(),
                    row.state(),
                    row.title(),
                    row.basis(),
                    row.severity(),
                    row.onsetAt(),
                    row.currentValue(),
                    row.baselineValue(),
                    row.delta(),
                    row.openedAt(),
                    row.lastSeenAt(),
                    row.resolvedAt(),
                    row.resolution(),
                    row.resolutionReason(),
                    row.resolvedBy(),
                    row.disposition(),
                    row.findingCount(),
                    row.latestFindingId(),
                    row.lockedAt(),
                    lead == null ? null : lead.summary(),
                    lead == null ? null : lead.verdict(),
                    mark == null ? null : mark.state(),
                    mark == null ? null : RcaRunView.since(mark));
        }
    }

    /**
     * {@code cases} is one worst-first list: nothing in this product is claimed. {@code watching} proves the silence is
     * coverage.
     */
    public record TriageView(
            List<CaseView> cases,
            @JsonProperty("recently_resolved") List<CaseView> recentlyResolved,
            WatchingView watching) {}

    /**
     * No {@link WatchingView}: coverage is a fact about the project, not a page. The cursor is stamped with its
     * ordering, so replaying it under a different {@code state} restarts at page one.
     */
    public record CasesPage(
            List<CaseView> cases,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /**
     * The proof line behind "Nothing needs you". Withheld classifiers are not counted. {@code tracesLastDay} separates
     * "nothing is wrong" from "nothing is arriving". {@code tracesTotal} and {@code openFindings} are null when not
     * counted, which is distinct from zero.
     */
    public record WatchingView(
            long classifiers,
            @JsonProperty("call_sites") long callSites,
            @JsonProperty("traces_last_day") long tracesLastDay,
            @JsonProperty("traces_total") @Nullable Long tracesTotal,
            @JsonProperty("open_findings") @Nullable Long openFindings) {}

    public record CaseEventView(
            String id,
            String kind,
            @Nullable String actor,
            String summary,
            @JsonProperty("created_at") String createdAt) {

        public static CaseEventView of(CaseEventRow row) {
            return new CaseEventView(row.id(), row.kind(), row.actor(), row.summary(), row.createdAt());
        }
    }

    public record CitationView(String path, String reason) {}

    /**
     * Every field reads off the case's newest finding, so case and finding cannot disagree. {@code byHuman} needs no
     * machine and so carries no citations.
     */
    public record CaseRulingView(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("ruled_by") String ruledBy,
            /** Composed server-side so a case, a Triage row and a Slack message phrase it the same way. */
            @JsonProperty("ruled_by_sentence") String ruledBySentence,
            @Nullable String verdict,
            @Nullable String action,
            @Nullable String summary,
            List<CitationView> citations,
            @JsonProperty("triaged_at") @Nullable String triagedAt,
            @JsonProperty("by_human") boolean byHuman) {}

    /**
     * {@code role} and {@code rank} pass through from {@code finding_evidence}; {@code rank} is null on rows written
     * before ranks existed.
     */
    public record CaseExemplarView(
            @JsonProperty("trace_id") String traceId,
            String role,
            @Nullable Integer rank,
            @Nullable String name,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            @JsonProperty("duration_ms") @Nullable Long durationMs,
            @JsonProperty("cost_usd") @Nullable Double costUsd,
            @JsonProperty("started_at") @Nullable String startedAt) {}

    /**
     * No triage field: every finding here was ruled positive to join, and the MCP case read must not tell RCA how
     * triage ruled.
     */
    public record CaseFindingView(
            String id,
            String title,
            @JsonProperty("window_opened_at") String windowOpenedAt,
            @JsonProperty("window_closed_at") String windowClosedAt,
            @JsonProperty("created_at") String createdAt) {}

    /**
     * The case page's whole read. {@code rcaReportId} without {@code rca} means a run is still going and the client
     * should poll. The availability flags are answered by the server so clients never compare detector strings. The
     * cause-kind blocks are null for every other detector, and both drawable blocks null is a normal state, not a
     * missing chart.
     */
    public record CaseDetailView(
            @JsonProperty("case") CaseView caseView,
            List<CaseEventView> events,
            /**
             * The one an RCA run is triggered on; the run reads every finding. Null only on archived cases that never
             * had one.
             */
            @JsonProperty("latest_finding_id") @Nullable String latestFindingId,
            List<CaseFindingView> findings,
            /** The case's worst window: the one that moved furthest from its reference. */
            @JsonProperty("worst_finding_id") @Nullable String worstFindingId,
            @Nullable CaseRulingView ruling,
            List<CaseExemplarView> exemplars,
            @JsonProperty("rca_report_id") @Nullable String rcaReportId,
            @Nullable RcaReportView rca,
            @Nullable ShiftDetail metric,
            @JsonProperty("tool_error") @Nullable RateDetail toolError,
            @JsonProperty("malformed_output") @Nullable MalformedDetail malformedOutput,
            @JsonProperty("secret_leak") @Nullable SecretLeakDetail secretLeak,
            @Nullable FrustrationDetail frustration,
            @Nullable GroundednessDetail groundedness,
            @JsonProperty("classifier_detail") @Nullable ClassifierDetail classifierDetail,
            @JsonProperty("rca_available") boolean rcaAvailable,
            @JsonProperty("absorb_available") boolean absorbAvailable,
            @JsonProperty("detector_available") boolean detectorAvailable) {}

    /**
     * {@code disposition} only on a frustration or groundedness case: {@code false_alarm} also stops the cited
     * conversations or answers counting as flagged.
     */
    public record ResolveCaseRequest(
            @Nullable @Size(max = 500) String reason,
            @Nullable @Pattern(regexp = "fixed|false_alarm") String disposition) {}
}
