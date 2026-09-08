// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.RcaError;
import ai.tessary.evals.rca.RcaDtos.Hypothesis;
import ai.tessary.evals.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsing + validation of {@link AgenticRcaEngine}'s sandboxed run, so nothing the agent asserts
 * reaches an immutable report unchecked:
 *
 * <ul>
 *   <li>Every hypothesis's evidence trace ids are checked against the finding's own evidence refs,
 *       both sides — a hallucinated receipt is worse than none, so unknown ids are dropped (and an
 *       all-hallucinated hypothesis survives with no receipts rather than fabricated ones).</li>
 *   <li>Checklist assessments are matched to the ids {@link RcaChecklist} actually measured; an
 *       invented check id is dropped the same way an invented trace id is.</li>
 *   <li>The verdict is normalized to the five values the {@code rca_report_verdict_check}
 *       constraint allows, and the two comparative verdicts carry a burden of proof: a
 *       {@code traffic_shift} or {@code behavior_change} that cites no baseline-side trace is a
 *       comparison whose "before" side was never observed, so it is downgraded to
 *       {@code inconclusive} (with a note the report surfaces) rather than persisted as fact. A
 *       bad RCA is worse than no RCA.</li>
 * </ul>
 */
final class RcaSynthesisOutput {

    private static final Logger log = LoggerFactory.getLogger(RcaSynthesisOutput.class);

    private RcaSynthesisOutput() {}

    record ReportBody(
            @Nullable String summary,
            @Nullable String verdict,
            @Nullable List<HypothesisBody> hypotheses,
            @Nullable List<ChecklistBody> checklist,
            @Nullable String detailed_report) {}

    record HypothesisBody(
            @Nullable String title,
            @Nullable String confidence,
            @Nullable String rationale,
            @Nullable List<String> evidence_trace_ids) {}

    record ChecklistBody(
            @Nullable String check,
            @Nullable String assessment,
            @Nullable String detail) {}

    /** One assessment the agent returned, already matched to a measured check id. */
    record ChecklistAssessment(String check, String assessment, String detail) {}

    /** A validated run result. {@code detailedReport} is null when the agent ignored its schema;
     *  {@code verdictNote} is non-null when the verdict was downgraded and explains why. */
    record Parsed(
            String summary,
            String verdict,
            List<Hypothesis> hypotheses,
            List<ChecklistAssessment> checklist,
            @Nullable String detailedReport,
            @Nullable String verdictNote) {}

    /**
     * Parse the agent's final JSON and validate it. Throws {@link EvalsException} when the text is
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
                    text == null ? 0 : text.length(),
                    abbreviate(text));
            throw new EvalsException(RcaError.UPSTREAM_FAILED, failure, "analysis was not the expected JSON shape");
        }
        Set<String> citable = new HashSet<>(baselineTraceIds);
        citable.addAll(flaggedTraceIds);
        String verdict = normalizeVerdict(body.verdict());
        var rawHypotheses = body.hypotheses();
        List<Hypothesis> hypotheses = rawHypotheses == null
                ? List.of()
                : rawHypotheses.stream()
                        .filter(h -> {
                            String t = h.title();
                            return t != null && !t.isBlank();
                        })
                        .map(h -> validated(h, citable, projectId))
                        .toList();
        String verdictNote = null;
        if (isComparative(verdict) && !baselineTraceIds.isEmpty() && citesNone(hypotheses, baselineTraceIds)) {
            // The two comparative verdicts assert how the flagged side differs from the baseline one.
            // With baseline-side evidence available but uncited, the "before" half of that comparison
            // was never demonstrated — record the honest answer instead. (When the baseline side has no
            // citable traces at all, there is nothing to demand and the verdict stands on the rest of
            // its evidence.)
            verdictNote = "> **Verdict downgraded by the platform.** The analysis returned `" + verdict
                    + "` — a claim about how the flagged side differs from the baseline one — but no hypothesis"
                    + " cited a baseline-side trace as evidence, so the baseline half of that comparison is"
                    + " unproven. Recorded as `inconclusive`; the finding's `baseline` evidence refs list what"
                    + " was available to cite.";
            log.warn(
                    "rca analysis project={} downgraded verdict {} -> inconclusive: no baseline-side evidence cited",
                    projectId,
                    verdict);
            verdict = RcaReportRow.Verdict.INCONCLUSIVE;
        }
        List<ChecklistAssessment> checklist = validatedChecklist(body.checklist(), measuredChecks, projectId);
        String bodySummary = body.summary();
        String bodyDetailed = body.detailed_report();
        String summary = bodySummary == null || bodySummary.isBlank() ? text : bodySummary;
        String detailed = bodyDetailed == null || bodyDetailed.isBlank() ? null : bodyDetailed;
        return new Parsed(summary, verdict, hypotheses, checklist, detailed, verdictNote);
    }

    /** The verdicts that compare the flagged side against the baseline one — the ones that carry a
     *  baseline-side burden of proof. */
    private static boolean isComparative(String verdict) {
        return RcaReportRow.Verdict.TRAFFIC_SHIFT.equals(verdict)
                || RcaReportRow.Verdict.BEHAVIOR_CHANGE.equals(verdict);
    }

    private static boolean citesNone(List<Hypothesis> hypotheses, Set<String> baselineTraceIds) {
        return hypotheses.stream().flatMap(h -> h.evidenceTraceIds().stream()).noneMatch(baselineTraceIds::contains);
    }

    /** Constrain to the five verdicts the DB check constraint allows; a schema-ignoring model falls
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
                    RuledOutCheck.Assessment.normalize(item.assessment()),
                    item.detail() == null ? "" : item.detail()));
        }
        if (dropped > 0) {
            log.warn("rca analysis project={} dropped {} assessment(s) of unmeasured check(s)", projectId, dropped);
        }
        return out;
    }

    private static Hypothesis validated(HypothesisBody h, Set<String> citableTraceIds, String projectId) {
        List<String> cited = h.evidence_trace_ids() == null ? List.of() : h.evidence_trace_ids();
        List<String> kept = cited.stream().filter(citableTraceIds::contains).toList();
        if (kept.size() < cited.size()) {
            log.warn(
                    "rca synthesize project={} dropped {} hallucinated evidence id(s) on '{}'",
                    projectId,
                    cited.size() - kept.size(),
                    h.title());
        }
        String rawConfidence = h.confidence();
        String confidence =
                switch (rawConfidence == null ? "" : rawConfidence) {
                    case "high", "medium", "low" -> rawConfidence;
                    default -> "low";
                };
        return new Hypothesis(h.title(), confidence, h.rationale() == null ? "" : h.rationale(), kept);
    }

    /**
     * Bind one candidate body; null only for blank input.
     *
     * <p><b>Unknown properties are ignored on purpose</b>, and that is a deliberate departure from the
     * {@code @Primary} mapper's strictness ({@code JacksonConfig} keeps
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} on). The response schema does not set
     * {@code additionalProperties: false}, so a model is free to add a field, and {@link ReportBody},
     * {@link HypothesisBody} and {@link ChecklistBody} are all closed records — one stray key anywhere
     * in the tree rejected an otherwise complete investigation. Every other validation in this class
     * already drops what it cannot accept (hallucinated trace ids, invented check ids) rather than
     * failing the run; binding did the opposite, which contradicted the class's own design.
     */
    private static @Nullable ReportBody bind(ObjectMapper mapper, @Nullable String candidate)
            throws java.io.IOException {
        if (candidate == null || candidate.isBlank()) return null;
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
    private static String extractObject(@Nullable String text) {
        if (text == null) return "";
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        return (open < 0 || close <= open) ? "" : text.substring(open, close + 1);
    }

    /** A bounded prefix for the failure log — never the whole body, which carries the agent's full
     *  markdown report over the traces it read. */
    private static String abbreviate(@Nullable String text) {
        if (text == null || text.isBlank()) return "<empty>";
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
