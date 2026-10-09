// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsing + validation of {@link AgenticRcaEngine}'s sandboxed run, so nothing the agent asserts
 * reaches an immutable report unchecked. Every report kind returns the same {@link Cause} shape; what a
 * cause must cite differs by kind:
 *
 * <ul>
 *   <li>A metric cause's trace ids are checked against the finding's own evidence refs, both sides — a
 *       hallucinated receipt is worse than none, so unknown ids are dropped, and an all-hallucinated cause
 *       survives with no receipts rather than fabricated ones.</li>
 *   <li>Checklist assessments are matched to the ids {@link RcaChecklist} actually measured; an
 *       invented check id is dropped the same way an invented trace id is.</li>
 *   <li>The verdict is normalized to the five metric-movement values the {@code rca_report_verdict_check}
 *       constraint allows, and the two comparative verdicts carry a burden of proof: a
 *       {@code traffic_shift} or {@code behavior_change} whose causes cite no baseline-side trace is a
 *       comparison whose "before" side was never observed, so it is downgraded to
 *       {@code inconclusive} (with a note the report surfaces) rather than persisted as fact. A
 *       bad RCA is worse than no RCA.</li>
 *   <li>A frustration report ({@link #parseFrustration}) has no baseline side. Each cause's session ids are
 *       checked against the finding's session refs and its trace ids against the flagged turns; a cause left
 *       citing no session is dropped, and a {@code causes_identified} verdict with no cause left is
 *       downgraded to {@code no_cause_found}.</li>
 *   <li>A groundedness report ({@link #parseGroundedness}) is the same with traces in place of sessions:
 *       each cause's trace ids are checked against the finding's traces with a flagged answer, and a cause
 *       left citing none is dropped.</li>
 * </ul>
 *
 * <p>Causes are stored proven first ({@code high}), then by how many sessions or traces they affect. A blank
 * summary falls back to the first cause's title, never to the raw reply.
 */
final class RcaSynthesisOutput {

    private static final Logger log = LoggerFactory.getLogger(RcaSynthesisOutput.class);

    private static final Comparator<Cause> PROVEN_THEN_LARGEST = Comparator.<Cause>comparingInt(
                    c -> Cause.CONFIDENCES.indexOf(c.confidence()))
            .thenComparing(Comparator.comparingInt(Cause::affectedCount).reversed());

    private RcaSynthesisOutput() {}

    record ReportBody(
            @Nullable String summary,
            @Nullable String verdict,
            @Nullable List<ChecklistBody> checklist,
            @Nullable List<CauseBody> causes,
            @Nullable String detailed_report) {}

    record CauseBody(
            @Nullable String title,
            @Nullable String confidence,
            @Nullable String what_changed,
            @Nullable String how_it_caused_this,
            @Nullable String next_step,
            @Nullable AttributionBody attribution,
            @Nullable List<String> evidence_trace_ids,
            @Nullable List<String> evidence_session_ids,
            @Nullable Integer affected_count) {}

    record AttributionBody(
            @Nullable String kind,
            @Nullable String path,
            @Nullable String commit,
            @Nullable String excerpt) {}

    record ChecklistBody(
            @Nullable String check,
            @Nullable String question,
            @Nullable String assessment,
            @Nullable String detail) {}

    /** One assessment the agent returned, already matched to a measured check id. */
    record ChecklistAssessment(String check, @Nullable String question, String assessment, String detail) {}

    /** A validated run result. {@code summary} is null when the agent wrote none and no cause survived;
     *  {@code detailedReport} is null when the agent ignored its schema; {@code verdictNote} is non-null when
     *  the verdict was downgraded and explains why. */
    record Parsed(
            @Nullable String summary,
            String verdict,
            List<Cause> causes,
            List<ChecklistAssessment> checklist,
            @Nullable String detailedReport,
            @Nullable String verdictNote) {}

    /**
     * Parse the agent's final JSON and validate it. Throws {@link TessaryException} when the text is
     * not the expected shape — a persisted immutable report must not degrade to unvalidated prose.
     *
     * @param baselineTraceIds citable trace ids on the finding's {@code baseline} side (the "before")
     * @param flaggedTraceIds citable trace ids on the side the detector flagged
     * @param measuredChecks the check ids {@link RcaChecklist} produced — the assessment whitelist
     */
    static Parsed parse(
            ObjectMapper mapper,
            String text,
            Set<String> baselineTraceIds,
            Set<String> flaggedTraceIds,
            Set<String> measuredChecks,
            String projectId) {
        ReportBody body = body(mapper, text, projectId);
        Set<String> citable = new HashSet<>(baselineTraceIds);
        citable.addAll(flaggedTraceIds);
        String verdict = normalizeVerdict(body.verdict());
        List<Cause> causes =
                validatedCauses(body, (c, title) -> metricCause(c, title, citable, projectId), projectId, "");
        String verdictNote = null;
        if (isComparative(verdict) && !baselineTraceIds.isEmpty() && citesNone(causes, baselineTraceIds)) {
            // The two comparative verdicts assert how the flagged side differs from the baseline one.
            // With baseline-side evidence available but uncited, the "before" half of that comparison
            // was never demonstrated — record the honest answer instead. (When the baseline side has no
            // citable traces at all, there is nothing to demand and the verdict stands on the rest of
            // its evidence.)
            verdictNote = "> **Verdict downgraded by the platform.** The analysis returned `" + verdict
                    + "` — a claim about how the flagged side differs from the baseline one — but no cause"
                    + " cited a baseline-side trace as evidence, so the baseline half of that comparison is"
                    + " unproven. Recorded as `inconclusive`; the finding's `baseline` evidence refs list what"
                    + " was available to cite.";
            log.warn(
                    "rca analysis project={} downgraded verdict {} -> inconclusive: no baseline-side evidence cited",
                    projectId,
                    verdict);
            verdict = RcaReportRow.Verdict.INCONCLUSIVE;
        }
        return parsed(body, verdict, causes, measuredChecks, projectId, verdictNote);
    }

    /**
     * Parse and validate a frustration run. There is no baseline side to demand a citation from; the
     * receipts are the finding's frustrated sessions and the turns that fired inside them.
     *
     * @param flaggedTraceIds the finding's witness trace refs, the turns that fired
     * @param sessionIds the finding's witness session refs, the frustrated conversations
     */
    static Parsed parseFrustration(
            ObjectMapper mapper,
            String text,
            Set<String> flaggedTraceIds,
            Set<String> sessionIds,
            Set<String> measuredChecks,
            String projectId) {
        return parseCauses(
                mapper,
                text,
                measuredChecks,
                projectId,
                (c, title) -> frustrationCause(c, title, flaggedTraceIds, sessionIds),
                "session",
                "frustrated session",
                "`witness` session refs");
    }

    /**
     * Parse and validate a groundedness run. Like a frustration run it has no baseline side; the receipts are
     * the finding's traces with a flagged answer, and there are no sessions to cite.
     *
     * @param flaggedTraceIds the finding's witness trace refs, the traces with a flagged answer
     */
    static Parsed parseGroundedness(
            ObjectMapper mapper,
            String text,
            Set<String> flaggedTraceIds,
            Set<String> measuredChecks,
            String projectId) {
        return parseCauses(
                mapper,
                text,
                measuredChecks,
                projectId,
                (c, title) -> groundednessCause(c, title, flaggedTraceIds),
                "trace",
                "trace with a flagged answer",
                "`witness` trace refs");
    }

    /** How one kind of report turns an agent's cause into a validated one, or null to drop it. */
    @FunctionalInterface
    private interface CauseValidator {
        @Nullable
        Cause validate(CauseBody body, String title);
    }

    /**
     * The shared half of the two causes reports: validate each cause and downgrade a {@code causes_identified}
     * verdict that no cause survived.
     *
     * @param receipt what a cause must cite, for the log line
     * @param receiptPhrase the same in the downgrade note
     * @param refs the finding's refs the downgrade note points at
     */
    private static Parsed parseCauses(
            ObjectMapper mapper,
            String text,
            Set<String> measuredChecks,
            String projectId,
            CauseValidator validator,
            String receipt,
            String receiptPhrase,
            String refs) {
        ReportBody body = body(mapper, text, projectId);
        List<Cause> causes = validatedCauses(body, validator, projectId, receipt);
        String verdict = RcaReportRow.Verdict.CAUSES_IDENTIFIED.equals(body.verdict())
                ? RcaReportRow.Verdict.CAUSES_IDENTIFIED
                : RcaReportRow.Verdict.NO_CAUSE_FOUND;
        String verdictNote = null;
        if (RcaReportRow.Verdict.CAUSES_IDENTIFIED.equals(verdict) && causes.isEmpty()) {
            verdictNote = "> **Verdict downgraded by the platform.** The analysis returned `causes_identified`,"
                    + " but no cause cited a " + receiptPhrase + " from this finding's evidence, so none survived."
                    + " Recorded as `no_cause_found`; the finding's " + refs + " list what was"
                    + " available to cite.";
            log.warn("rca analysis project={} downgraded verdict causes_identified -> no_cause_found", projectId);
            verdict = RcaReportRow.Verdict.NO_CAUSE_FOUND;
        }
        return parsed(body, verdict, causes, measuredChecks, projectId, verdictNote);
    }

    /** Validate each titled cause, drop the ones {@code validator} rejects, and rank the rest. */
    private static List<Cause> validatedCauses(
            ReportBody body, CauseValidator validator, String projectId, String receipt) {
        List<Cause> causes = new ArrayList<>();
        int dropped = 0;
        List<CauseBody> bodies = body.causes();
        for (CauseBody c : bodies == null ? List.<CauseBody>of() : bodies) {
            String title = c.title();
            if (title == null || title.isBlank()) continue;
            Cause cause = validator.validate(c, title);
            if (cause == null) {
                dropped++;
            } else {
                causes.add(cause);
            }
        }
        if (dropped > 0) {
            log.warn(
                    "rca analysis project={} dropped {} cause(s) that cited no {} of this finding",
                    projectId,
                    dropped,
                    receipt);
        }
        // Stable, so the agent's own order breaks ties.
        causes.sort(PROVEN_THEN_LARGEST);
        return List.copyOf(causes);
    }

    private static Parsed parsed(
            ReportBody body,
            String verdict,
            List<Cause> causes,
            Set<String> measuredChecks,
            String projectId,
            @Nullable String verdictNote) {
        List<ChecklistAssessment> checklist = validatedChecklist(body.checklist(), measuredChecks, projectId);
        String bodyDetailed = body.detailed_report();
        String detailed = bodyDetailed == null || bodyDetailed.isBlank() ? null : bodyDetailed;
        return new Parsed(RcaDtos.summaryOf(body.summary(), causes), verdict, causes, checklist, detailed, verdictNote);
    }

    /** One metric cause with its trace ids filtered to either side of this finding. Never dropped: a cause
     *  whose every id was invented survives with no receipts rather than fabricated ones. */
    private static Cause metricCause(CauseBody c, String title, Set<String> citableTraceIds, String projectId) {
        List<String> cited = distinct(c.evidence_trace_ids());
        List<String> kept = cited.stream().filter(citableTraceIds::contains).toList();
        if (kept.size() < cited.size()) {
            log.warn(
                    "rca synthesize project={} dropped {} hallucinated evidence id(s) on '{}'",
                    projectId,
                    cited.size() - kept.size(),
                    title);
        }
        Integer claimed = c.affected_count();
        return cause(c, title, kept, List.of(), Math.max(claimed == null ? 0 : claimed, 0));
    }

    /** One frustration cause with its receipts filtered to this finding's refs, or null when no session survives. */
    private static @Nullable Cause frustrationCause(
            CauseBody c, String title, Set<String> flaggedTraceIds, Set<String> sessionIds) {
        List<String> sessions = distinct(c.evidence_session_ids()).stream()
                .filter(sessionIds::contains)
                .toList();
        if (sessions.isEmpty()) return null;
        List<String> traces = distinct(c.evidence_trace_ids()).stream()
                .filter(flaggedTraceIds::contains)
                .toList();
        Integer claimed = c.affected_count();
        return cause(c, title, traces, sessions, Math.max(claimed == null ? 0 : claimed, sessions.size()));
    }

    /** One groundedness cause with its traces filtered to this finding's flagged ones, or null when none survives.
     *  Session ids are not receipts here, so any the agent returned are dropped. */
    private static @Nullable Cause groundednessCause(CauseBody c, String title, Set<String> flaggedTraceIds) {
        List<String> traces = distinct(c.evidence_trace_ids()).stream()
                .filter(flaggedTraceIds::contains)
                .toList();
        if (traces.isEmpty()) return null;
        Integer claimed = c.affected_count();
        return cause(c, title, traces, List.of(), Math.max(claimed == null ? 0 : claimed, traces.size()));
    }

    private static Cause cause(CauseBody c, String title, List<String> traces, List<String> sessions, int affected) {
        return new Cause(
                title,
                confidence(c.confidence()),
                blankToNull(c.what_changed()),
                blankToNull(c.how_it_caused_this()),
                blankToNull(c.next_step()),
                attribution(c.attribution()),
                traces,
                sessions,
                affected);
    }

    private static @Nullable Attribution attribution(@Nullable AttributionBody a) {
        if (a == null) return null;
        String kind = a.kind() != null && Attribution.KINDS.contains(a.kind()) ? a.kind() : Attribution.UNKNOWN;
        return new Attribution(kind, blankToNull(a.path()), blankToNull(a.commit()), blankToNull(a.excerpt()));
    }

    private static List<String> distinct(@Nullable List<String> ids) {
        return ids == null ? List.of() : List.copyOf(new LinkedHashSet<>(ids));
    }

    private static @Nullable String blankToNull(@Nullable String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static String confidence(@Nullable String raw) {
        return switch (raw == null ? "" : raw) {
            case "high", "medium", "low" -> raw;
            default -> "low";
        };
    }

    /** Bind the agent's reply, or throw: a persisted immutable report must not degrade to unvalidated prose. */
    private static ReportBody body(ObjectMapper mapper, String text, String projectId) {
        ReportBody body = null;
        Exception failure = null;
        try {
            body = bind(mapper, text);
        } catch (Exception e) {
            // Keep the FIRST failure as the cause: it is the one that names the offending property or
            // offset in the reply the agent actually sent. The fence-stripped salvage below reports a
            // miss as null rather than throwing, because its own failure says nothing this one did not.
            failure = e;
            body = salvage(mapper, extractObject(text));
        }
        if (body == null) {
            // The most expensive failure in this file: it lands at the END of a run that already spent
            // its wall clock and its tokens, and until now it said only "not the expected JSON shape",
            // which does not distinguish prose from a fence from a truncated reply. A short, bounded
            // prefix is what makes the next one diagnosable without re-running the investigation.
            log.warn(
                    "rca analysis project={} unparseable body ({} chars), starts: {}",
                    projectId,
                    text.length(),
                    abbreviate(text));
            throw new TessaryException(RcaError.UPSTREAM_FAILED, failure, "analysis was not the expected JSON shape");
        }
        return body;
    }

    /** The verdicts that compare the flagged side against the baseline one — the ones that carry a
     *  baseline-side burden of proof. */
    private static boolean isComparative(String verdict) {
        return RcaReportRow.Verdict.TRAFFIC_SHIFT.equals(verdict)
                || RcaReportRow.Verdict.BEHAVIOR_CHANGE.equals(verdict);
    }

    private static boolean citesNone(List<Cause> causes, Set<String> baselineTraceIds) {
        return causes.stream().flatMap(c -> c.evidenceTraceIds().stream()).noneMatch(baselineTraceIds::contains);
    }

    /** Constrain to the five metric-movement verdicts; a schema-ignoring model falls
     *  to inconclusive, which is also the honest value for "no change located". All five are the
     *  agent's to assign — nothing here rules a cause in or out on its behalf. */
    static String normalizeVerdict(@Nullable String verdict) {
        return switch (verdict == null ? "" : verdict) {
            case RcaReportRow.Verdict.BEHAVIOR_CHANGE,
                    RcaReportRow.Verdict.TRAFFIC_SHIFT,
                    RcaReportRow.Verdict.DEFINITION_CHANGE,
                    RcaReportRow.Verdict.MODEL_CHANGE -> verdict;
            default -> RcaReportRow.Verdict.INCONCLUSIVE;
        };
    }

    /** Keep only assessments of checks that were actually measured, first one wins on a duplicate —
     *  an assessment of an invented check id is as unfounded as a hallucinated trace receipt. */
    private static List<ChecklistAssessment> validatedChecklist(
            @Nullable List<ChecklistBody> items, Set<String> measuredChecks, String projectId) {
        if (items == null) return List.of();
        List<ChecklistAssessment> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int dropped = 0;
        for (ChecklistBody item : items) {
            if (item.check() == null || !measuredChecks.contains(item.check())) {
                dropped++;
                continue;
            }
            if (!seen.add(item.check())) continue;
            out.add(new ChecklistAssessment(
                    item.check(),
                    blankToNull(item.question()),
                    RuledOutCheck.Assessment.normalize(item.assessment()),
                    item.detail() == null ? "" : item.detail()));
        }
        if (dropped > 0) {
            log.warn("rca analysis project={} dropped {} assessment(s) of unmeasured check(s)", projectId, dropped);
        }
        return out;
    }

    /**
     * Bind one candidate body; null only for blank input.
     *
     * <p><b>Unknown properties are ignored on purpose</b>, and that is a deliberate departure from the
     * {@code @Primary} mapper's strictness ({@code JacksonConfig} keeps
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} on). The response schema does not set
     * {@code additionalProperties: false}, so a model is free to add a field, and {@link ReportBody},
     * {@link CauseBody} and {@link ChecklistBody} are all closed records — one stray key anywhere
     * in the tree rejected an otherwise complete investigation. Every other validation in this class
     * already drops what it cannot accept (hallucinated trace ids, invented check ids) rather than
     * failing the run; binding did the opposite, which contradicted the class's own design.
     */
    private static @Nullable ReportBody bind(ObjectMapper mapper, String candidate) throws java.io.IOException {
        if (candidate.isBlank()) return null;
        return mapper.readerFor(ReportBody.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(candidate);
    }

    /** The second attempt, over the brace-delimited slice: a miss is null, not a throw, so the caller
     *  can keep the first (more informative) failure as the exception's cause. */
    private static @Nullable ReportBody salvage(ObjectMapper mapper, String candidate) {
        try {
            return bind(mapper, candidate);
        } catch (Exception e) {
            return null;
        }
    }

    /** The substring from the first '{' to the last '}', or "" if none — strips surrounding prose.
     *  Mirrors {@code BehaviorTriageVerdict#extractObject}, the triage lane's equivalent; kept local
     *  rather than shared because the two lanes' envelope handling is otherwise unrelated. Only ever
     *  reached when the text is not already clean JSON, so a well-formed reply never goes near it. */
    private static String extractObject(String text) {
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        return (open < 0 || close <= open) ? "" : text.substring(open, close + 1);
    }

    /** A bounded prefix for the failure log — never the whole body, which carries the agent's full
     *  markdown report over the traces it read. */
    private static String abbreviate(String text) {
        if (text.isBlank()) return "<empty>";
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
