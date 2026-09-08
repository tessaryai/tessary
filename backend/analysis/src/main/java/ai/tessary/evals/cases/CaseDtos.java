// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

import ai.tessary.evals.classifier.metric.MetricFindingEvidence.ShiftDetail;
import ai.tessary.evals.classifier.toolerror.ToolErrorEvidence.RateDetail;
import ai.tessary.evals.rca.RcaDtos.RcaReportView;
import ai.tessary.evals.rca.RcaReportRepository.CaseLead;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTOs for the case surface — Triage and the case page. Snake_case on the wire. */
public final class CaseDtos {

    private CaseDtos() {}

    /** One case, as both the Triage row and the case page's verdict header read it. */
    public record CaseView(
            String id,
            /** The display id a human quotes: {@code C-118}. */
            String reference,
            String detector,
            @JsonProperty("subject_kind") String subjectKind,
            @JsonProperty("subject_id") String subjectId,
            @JsonProperty("subject_label") String subjectLabel,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            String metric,
            String state,
            String title,
            /** Why this crossed <i>its own</i> detector's bar, in that detector's terms. The ranked list
             *  mixes detectors that do not share a threshold, so this is never normalized away. */
            String basis,
            /** 0..1, for ordering the list. Comparable across detectors by construction and by nothing
             *  else — clients must not render it as a number. */
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
            @JsonProperty("muted_at") @Nullable String mutedAt,
            @JsonProperty("muted_by") @Nullable String mutedBy,
            /**
             * The finding this case was opened from, or null for a case that came in some other way.
             *
             * <p>Carried on the LIST row, not only on {@link CaseDetailView}, because the direction the
             * classifiers queue reads it in is the reverse one: a finding triage ruled {@code positive}
             * opened a case, and the queue's row for that finding has to name it. Without this the only
             * way to resolve finding → case is a fetch per open case.
             */
            @JsonProperty("finding_id") @Nullable String findingId,
            /**
             * The leading hypothesis of the finished RCA behind this case, or null where none has run,
             * none finished, or the run reached no hypothesis at all.
             *
             * <p>The TITLE, not the summary: the schema already asks for a short noun phrase there, and a
             * queue row has one line to spend. Null is the common case and is not a gap — most cases have
             * never been analysed, and a row that invented a cause for one would be worse than a quiet row.
             */
            @Nullable String cause,
            /**
             * That run's verdict. Carried BESIDE the cause because the two disagree in the case that
             * matters: an {@code inconclusive} run still names a leading hypothesis, and presenting that
             * as the cause states an attribution the analysis explicitly declined to make.
             */
            @JsonProperty("rca_verdict") @Nullable String rcaVerdict) {

        /** A case with no analysis behind it, or none this caller looked up. */
        public static CaseView of(CaseRow row) {
            return of(row, null);
        }

        public static CaseView of(CaseRow row, @Nullable CaseLead lead) {
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
                    row.mutedAt(),
                    row.mutedBy(),
                    row.findingId(),
                    lead == null ? null : lead.cause(),
                    lead == null ? null : lead.verdict());
        }
    }

    /**
     * Triage's whole read.
     *
     * @param cases the open cases, worst first. One list — no claimed/unclaimed bands, because nothing
     *     in this product is claimed.
     * @param muted live cases someone has silenced. Carried in full rather than as a count so the
     *     "muted" filter renders without a second round trip; the set is small by construction.
     * @param recentlyResolved the last week's closures — Triage's one quiet history line.
     * @param watching what the all-clear state says to prove the silence is real coverage rather than
     *     nothing being watched.
     */
    public record TriageView(
            List<CaseView> cases,
            List<CaseView> muted,
            @JsonProperty("recently_resolved") List<CaseView> recentlyResolved,
            WatchingView watching) {}

    /**
     * One keyset page of cases, filtered by state / detector / call site.
     *
     * <p><b>Flat, and carrying no coverage block.</b> {@link TriageView}'s three fixed buckets answer "show me
     * everything at once", which is what a screen wants and what a paged reader cannot do; this shape answers
     * "give me the next N cases matching a filter". The {@link WatchingView} that rides along with the triage
     * buckets deliberately does NOT ride along here — coverage is a fact about the project, not about a page
     * of it, and repeating it on every page would invite a reader to treat page 3's copy as a fresh
     * measurement. It moved to the project read.
     *
     * @param nextCursor pass back as {@code cursor} for the next page; null when this was the last one. Opaque
     *     and stamped with the ordering it was minted under, so a cursor replayed against a different
     *     {@code state} restarts at page one rather than resuming from a point that is not on the new order.
     */
    public record CasesPage(
            List<CaseView> cases,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {}

    /**
     * The proof line behind "Nothing needs you": what is actually watching, in the terms the launch
     * product watches in.
     *
     * <p>It used to count graders and call sites, which was the honest answer when a grader set was the
     * only thing that ever noticed anything. It is the wrong answer now and wrong in the worst
     * direction: {@code graders_enabled} is off for every launch partner, so the one screen whose job is
     * to prove the silence is real coverage would have told them <i>"No graders are watching production
     * yet"</i> while three classifiers swept their traffic all week (launch requirement E5).
     *
     * @param classifiers enabled classifiers this org actually has — withheld built-ins are not counted,
     *     because a classifier the flag layer is holding back is not watching anything.
     * @param callSites call sites the classifiers are watching across.
     * @param tracesLastDay traffic seen in the last 24h. Not a number any surface prints on its own: it
     *     is what separates two silences a reader must never confuse — nothing is wrong, versus nothing
     *     is arriving — and the second of those is a warning ON a screen, never the whole screen.
     * @param tracesTotal every trace this project has ever kept, or {@code null} when the caller said it
     *     would not be shown. Only the empty Triage screen prints it, so {@link
     *     CaseService#watching(String, boolean)} skips the count entirely for a project with an open
     *     queue. {@code null} means "not counted" and is a different fact from {@code 0} — a reader that
     *     renders one as the other is a bug.
     * @param openFindings live findings — {@code status IN ('open','blocked')}, the same set the
     *     findings API's {@code ?status=open} returns, so the count a case screen offers and the list it
     *     sends the reader to cannot disagree. {@code null} under the same rule as {@code tracesTotal}.
     */
    public record WatchingView(
            long classifiers,
            @JsonProperty("call_sites") long callSites,
            @JsonProperty("traces_last_day") long tracesLastDay,
            @JsonProperty("traces_total") @Nullable Long tracesTotal,
            @JsonProperty("open_findings") @Nullable Long openFindings) {}

    /** One entry in the activity trail. */
    public record CaseEventView(
            String id,
            String kind,
            /** The acting user's email, or null when the system acted. */
            @Nullable String actor,
            String summary,
            @JsonProperty("created_at") String createdAt) {

        public static CaseEventView of(CaseEventRow row) {
            return new CaseEventView(row.id(), row.kind(), row.actor(), row.summary(), row.createdAt());
        }
    }

    /** One thing a ruling rests on: an evidence pointer, a repo path, or a check script the agent ran. */
    public record CitationView(String path, String reason) {}

    /**
     * <b>Who said this was real, in their own words</b> — the case's answer to "why does anyone think
     * this matters" (launch requirements E1 and E6).
     *
     * <p>A case in Triage exists because something ruled its detection real: a person pressing
     * <em>Real deviation</em>, or a triage run finding the finding's claim {@code positive}. The ruling
     * itself — the sentence and its citations — was written to the finding and rendered nowhere a
     * person paged about the case would look.
     *
     * <p>Nothing here is computed. Every field is read straight off the finding named by
     * {@code eval_case.finding_id}, so the case and the finding cannot disagree about what was ruled.
     *
     * @param verdict the triage verdict, always {@code positive} for a machine ruling that reached a case
     *     (nothing else opens one), and null when a human ruled
     * @param action what the ruling did — {@code opened_case} here by construction, carried so a client
     *     never has to re-derive the verdict→action mapping
     * @param byHuman a person pressed <em>Real deviation</em>. The strongest ruling available and the
     *     only one that needed no machine — it carries no citations, and a client showing an empty
     *     citation list for it would read as a weak ruling rather than the strongest one.
     */
    public record CaseRulingView(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("ruled_by") String ruledBy,
            /** The whole claim as one sentence, composed server-side so a case, a Triage row and a
             *  Slack message cannot phrase the same ruling three ways. */
            @JsonProperty("ruled_by_sentence") String ruledBySentence,
            @Nullable String verdict,
            @Nullable String action,
            @Nullable String summary,
            List<CitationView> citations,
            @JsonProperty("triaged_at") @Nullable String triagedAt,
            @JsonProperty("by_human") boolean byHuman) {}

    /**
     * <b>One trace the classifier recorded</b>, carrying the role it recorded it under (launch
     * requirement E2). A case says a population moved; these are the references its finding pinned, so
     * the claim can be checked rather than taken.
     *
     * <p>{@code role} and {@code rank} are the {@code finding_evidence} columns, passed through
     * unchanged rather than collapsed into a side. The distinction they carry is the one a reader needs:
     * a {@code baseline} trace is a member of the population the classifier compared AGAINST, an {@code
     * exemplar} is the member a Layer-2 escalation was pointed at, and a {@code member} or {@code
     * witness} is another instance of the same thing. The old {@code side}/{@code exemplar} pair could
     * say only "before or after, recorded or sampled", which put a witness and an exemplar in one bucket
     * and had no word at all for a baseline.
     *
     * @param role one of the persisted evidence roles — {@code exemplar}, {@code member}, {@code
     *     baseline}, {@code witness}, {@code changepoint}.
     * @param rank the classifier's own order within that role; null on a row written before ranks were.
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
     * The case page's whole read.
     *
     * @param ruling who ruled the detection real, and on what — null for an archived case whose
     *     detector opened without triage, or whose finding has since been deleted.
     * @param exemplars the traces the finding pinned as evidence, each carrying its role — the whole of
     *     what a case can show, since nothing is sampled at read time any more.
     * @param rcaReportId the most recent RCA on this case's subject, if one has been run. Kept beside
     *     {@code rca} for provenance and for the poll: a report still running has an id here and nothing in
     *     {@code rca} yet, which is exactly how a client knows to come back.
     * @param rca that report in full — verdict, hypotheses, the ruled-out checklist and the agent's markdown
     *     write-up — inlined once it has finished, and null while it is pending or when there is none.
     *     <p><b>Inlined rather than pointed at, deliberately.</b> The report IS the answer to "why is this
     *     case open", and the case page is where a person asks that. Leaving it a bare id made the answer a
     *     second round trip for the UI and a second gated tool for an agent, which is how a written
     *     investigation goes unread. An org that cannot run RCA needs no special case here: it has no report
     *     rows, so this is null for the same reason it is null on a case nobody has analysed.
     * @param rcaAvailable whether an RCA can be run for this case at all — i.e. whether there is a
     *     finding behind it, since RCA is a finding-analysis lane. False on archived cases from the
     *     retired detectors, which have no finding by construction. The server answers because the
     *     server is what knows: a client that decided this by comparing {@code detector} against a
     *     hardcoded string would have to be edited every time a detector is added, which is exactly the
     *     coupling {@link CaseSource} exists to prevent.
     * @param absorbAvailable whether <em>Legitimate — absorb</em> can be pressed: there is a finding
     *     behind this case, its detector is one the org still has, and the case is live. Same argument
     *     as {@code rcaAvailable} — the server owns the answer.
     * @param detectorAvailable whether the classifier that opened this case is one the org still has.
     *     False for a case left open when a capability went off; every action on it is withheld, and the
     *     page says why rather than offering buttons that 404 (launch requirement E4, and the residual
     *     segment D left open).
     * @param metric the measured shift behind a {@code distribution_shift} finding, and null on every
     *     other cause kind. Typed exactly as {@code BehaviorFindingDetailView} types it, and parsed by the
     *     same {@code MetricFindingEvidence#detail}, so the finding page and the case page draw the same
     *     figure from the same bytes. Only the wire spelling differs, because this file is snake_case and
     *     that one is not.
     * @param toolError the same for a {@code rate_shift} finding.
     *     <p><b>Inlined for the reason {@code rca} is.</b> A case states a magnitude in its title and its
     *     basis, and until now the only honest picture of that magnitude lived one navigation away on the
     *     finding. The case already loads the finding row to build {@code ruling} and {@code exemplars},
     *     so this costs no query — it stops discarding a column already in hand.
     *     <p>Both null is the normal state for a detector whose shift has no drawable shape, and a client
     *     must render that as the fact it is rather than as a missing chart.
     */
    public record CaseDetailView(
            @JsonProperty("case") CaseView caseView,
            List<CaseEventView> events,
            /** The finding this case is about — the subject an RCA run is anchored on. Null only on an
             *  archived case from a retired detector, which by construction never had one. */
            @JsonProperty("finding_id") @Nullable String findingId,
            @Nullable CaseRulingView ruling,
            List<CaseExemplarView> exemplars,
            @JsonProperty("rca_report_id") @Nullable String rcaReportId,
            @Nullable RcaReportView rca,
            @Nullable ShiftDetail metric,
            @JsonProperty("tool_error") @Nullable RateDetail toolError,
            @JsonProperty("rca_available") boolean rcaAvailable,
            @JsonProperty("absorb_available") boolean absorbAvailable,
            @JsonProperty("detector_available") boolean detectorAvailable) {}

    /** Closing a case. The reason is required and is the point of the record. */
    public record ResolveCaseRequest(
            @NotBlank @Size(max = 500) String reason) {}
}
