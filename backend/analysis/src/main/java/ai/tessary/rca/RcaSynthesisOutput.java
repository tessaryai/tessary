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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsing + validation of {@link AgenticRcaEngine}'s sandboxed run, so nothing the agent asserts reaches an
 * immutable report unchecked. One parser for every report kind:
 *
 * <ul>
 *   <li>A cause's trace and session ids are checked against this finding's flagged and baseline evidence. A hallucinated
 *       receipt is worse than none, so unknown ids are dropped, and a cause left citing nothing is dropped.</li>
 *   <li>A cause below {@code medium} is not a cause, so one whose confidence is not {@code high} or
 *       {@code medium} is dropped, and so is one without a valid {@code change}. An unknown {@code type} reads as
 *       {@code other}. Labels are compared ignoring case.</li>
 *   <li>Without a cloned repo there is no file or commit to point at, so {@code path} and {@code commit} are
 *       cleared.</li>
 *   <li>The verdict is {@code causes_identified} or {@code no_cause_found}; a {@code causes_identified} with no
 *       cause left is recorded as {@code no_cause_found}, with a note the report surfaces.</li>
 *   <li>Each {@code ruled_out} sentence becomes one {@link RuledOutCheck}.</li>
 * </ul>
 *
 * <p>Causes are stored {@code high} first, then by how many flagged rows they explain; a cause explains at least
 * the rows it cites. A blank summary falls back to the first cause's title, never to the raw reply.
 */
final class RcaSynthesisOutput {

    private static final Logger log = LoggerFactory.getLogger(RcaSynthesisOutput.class);

    private static final Set<String> CONFIDENCES = Set.of(Cause.HIGH, Cause.MEDIUM);

    // A stored cause's `change` is what tells the UI it is a new-format cause, so one without a valid
    // value would render under the old rules: it is dropped, never stored half-formed.
    private static final Set<String> CHANGES = Set.of("change", "standing");

    private static final Set<String> TYPES =
            Set.of("code", "prompt", "tool", "model", "traffic", "upstream", "data", "other");

    private static final int MAX_CAUSES = 4;

    private static final Comparator<Cause> HIGH_THEN_LARGEST = Comparator.<Cause>comparingInt(
                    c -> Cause.HIGH.equals(c.confidence()) ? 0 : 1)
            .thenComparing(Comparator.comparingInt(Cause::affectedCount).reversed());

    private RcaSynthesisOutput() {}

    record ReportBody(
            @Nullable String summary,
            @Nullable String verdict,
            @Nullable List<CauseBody> causes,
            @Nullable List<String> ruled_out,
            @Nullable String detailed_report) {}

    record CauseBody(
            @Nullable String title,
            @Nullable String change,
            @Nullable String type,
            @Nullable String confidence,
            @Nullable String what_happens,
            @Nullable String how_it_caused_this,
            @Nullable String next_step,
            @Nullable AttributionBody attribution,
            @Nullable List<String> evidence_trace_ids,
            @Nullable List<String> evidence_session_ids,
            @Nullable Integer affected_count) {}

    record AttributionBody(
            @Nullable String path,
            @Nullable String commit,
            @Nullable String excerpt) {}

    /** A validated run result. {@code summary} is null when the agent wrote none and no cause survived;
     *  {@code detailedReport} is null when the agent ignored its schema; {@code verdictNote} is non-null when
     *  the verdict was downgraded and explains why. */
    record Parsed(
            @Nullable String summary,
            String verdict,
            List<Cause> causes,
            List<RuledOutCheck> ruledOut,
            @Nullable String detailedReport,
            @Nullable String verdictNote) {}

    /**
     * Parse the agent's final JSON and validate it. Throws {@link TessaryException} when the text is not the
     * expected shape — a persisted immutable report must not degrade to unvalidated prose.
     *
     * @param citableTraceIds every trace id in this finding's evidence
     * @param citableSessionIds every session id in this finding's evidence
     * @param repoCloned whether the run had the repo to read
     */
    static Parsed parse(
            ObjectMapper mapper,
            String text,
            Set<String> citableTraceIds,
            Set<String> citableSessionIds,
            boolean repoCloned,
            String projectId) {
        ReportBody body = body(mapper, text, projectId);
        List<Cause> causes = new ArrayList<>();
        int unreceipted = 0;
        int belowMedium = 0;
        int unlabelled = 0;
        List<CauseBody> bodies = body.causes();
        for (CauseBody c : bodies == null ? List.<CauseBody>of() : bodies) {
            String title = c.title();
            if (title == null || title.isBlank()) continue;
            String confidence = label(c.confidence());
            if (!CONFIDENCES.contains(confidence)) {
                belowMedium++;
                continue;
            }
            String change = label(c.change());
            if (!CHANGES.contains(change)) {
                unlabelled++;
                continue;
            }
            String type = TYPES.contains(label(c.type())) ? label(c.type()) : "other";
            List<String> traces = kept(c.evidence_trace_ids(), citableTraceIds);
            List<String> sessions = kept(c.evidence_session_ids(), citableSessionIds);
            if (traces.isEmpty() && sessions.isEmpty()) {
                unreceipted++;
                continue;
            }
            Integer claimed = c.affected_count();
            causes.add(new Cause(
                    title,
                    confidence,
                    change,
                    type,
                    blankToNull(c.what_happens()),
                    blankToNull(c.how_it_caused_this()),
                    blankToNull(c.next_step()),
                    attribution(c.attribution(), repoCloned),
                    traces,
                    sessions,
                    Math.max(claimed == null ? 0 : claimed, Math.max(traces.size(), sessions.size()))));
        }
        if (unreceipted > 0 || belowMedium > 0 || unlabelled > 0) {
            log.warn(
                    "rca analysis project={} dropped {} cause(s) citing nothing of this finding, {} below medium"
                            + " and {} without a valid change",
                    projectId,
                    unreceipted,
                    belowMedium,
                    unlabelled);
        }
        // Stable, so the agent's own order breaks ties.
        causes.sort(HIGH_THEN_LARGEST);
        if (causes.size() > MAX_CAUSES) {
            log.warn("rca analysis project={} kept the first {} of {} causes", projectId, MAX_CAUSES, causes.size());
            causes.subList(MAX_CAUSES, causes.size()).clear();
        }

        // The verdict is a function of what survived, in both directions.
        boolean claimed = RcaReportRow.Verdict.CAUSES_IDENTIFIED.equals(body.verdict());
        String verdict =
                causes.isEmpty() ? RcaReportRow.Verdict.NO_CAUSE_FOUND : RcaReportRow.Verdict.CAUSES_IDENTIFIED;
        String verdictNote = null;
        if (claimed && causes.isEmpty()) {
            verdictNote = "> **Verdict downgraded by the platform.** The analysis returned `causes_identified`,"
                    + " but no cause survived validation: each needs high or medium confidence, a valid `change`,"
                    + " and a trace or session from this finding's flagged or baseline evidence. Recorded as"
                    + " `no_cause_found`.";
            log.warn("rca analysis project={} downgraded verdict causes_identified -> no_cause_found", projectId);
        } else if (!claimed && !causes.isEmpty()) {
            verdictNote = "> **Verdict corrected by the platform.** The analysis did not return `causes_identified`,"
                    + " but " + causes.size() + " cause(s) at high or medium confidence cited this finding's"
                    + " evidence. Recorded as `causes_identified`.";
            log.warn("rca analysis project={} corrected verdict {} -> causes_identified", projectId, body.verdict());
        }

        String bodyDetailed = body.detailed_report();
        String detailed = bodyDetailed == null || bodyDetailed.isBlank() ? null : bodyDetailed;
        return new Parsed(
                RcaDtos.summaryOf(body.summary(), causes),
                verdict,
                List.copyOf(causes),
                ruledOut(body.ruled_out()),
                detailed,
                verdictNote);
    }

    private static List<RuledOutCheck> ruledOut(@Nullable List<String> sentences) {
        if (sentences == null) return List.of();
        List<RuledOutCheck> out = new ArrayList<>();
        for (String sentence : sentences) {
            if (sentence == null || sentence.isBlank()) continue;
            out.add(RuledOutCheck.ruledOut(out.size() + 1, sentence.strip()));
        }
        return List.copyOf(out);
    }

    private static @Nullable Attribution attribution(@Nullable AttributionBody a, boolean repoCloned) {
        if (a == null) return null;
        String path = repoCloned ? blankToNull(a.path()) : null;
        String commit = repoCloned ? blankToNull(a.commit()) : null;
        String excerpt = blankToNull(a.excerpt());
        if (path == null && commit == null && excerpt == null) return null;
        return new Attribution(null, path, commit, excerpt);
    }

    private static List<String> kept(@Nullable List<String> ids, Set<String> citable) {
        if (ids == null) return List.of();
        return new LinkedHashSet<>(ids).stream().filter(citable::contains).toList();
    }

    /** A label as the schema spells it: the model may capitalise or pad an enum value. */
    private static String label(@Nullable String v) {
        return v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String v) {
        return v == null || v.isBlank() ? null : v;
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

    /**
     * Bind one candidate body; null only for blank input.
     *
     * <p><b>Unknown properties are ignored on purpose</b>, and that is a deliberate departure from the
     * {@code @Primary} mapper's strictness ({@code JacksonConfig} keeps
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} on). The response schema does not set
     * {@code additionalProperties: false}, so a model is free to add a field, and {@link ReportBody}
     * and {@link CauseBody} are closed records — one stray key anywhere in the tree rejected an
     * otherwise complete investigation. Every other validation in this class already drops what it
     * cannot accept (hallucinated ids, a confidence below medium) rather than failing the run; binding
     * did the opposite, which contradicted the class's own design.
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
