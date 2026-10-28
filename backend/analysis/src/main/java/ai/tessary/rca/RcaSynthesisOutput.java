// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.Coverage;
import ai.tessary.rca.RcaDtos.RoleCount;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nothing the agent asserts reaches an immutable report unchecked: ids outside the finding's evidence are dropped (a
 * hallucinated receipt is worse than none), causes below {@code medium}, without a valid {@code change} or citing no
 * flagged row are dropped, each {@code affected_count} is held between the flagged rows its cause cites and the flagged
 * total, coverage counts are held to what was recorded, and without a cloned repo {@code path} and {@code commit} are
 * cleared. A {@code causes_identified} with no surviving cause becomes {@code no_cause_found} with a note.
 */
final class RcaSynthesisOutput {

    private static final Logger log = LoggerFactory.getLogger(RcaSynthesisOutput.class);

    private static final Set<String> CONFIDENCES = Set.of(Cause.HIGH, Cause.MEDIUM);

    // `change` tells the UI a cause is new-format, so one without a valid value is dropped, never stored half-formed.
    private static final Set<String> CHANGES = Set.of("change", "standing");

    private static final Set<String> TYPES = Set.of(
            "code", "prompt", "tool", "model", "deploy", "config", "infra", "traffic", "upstream", "data", "other");

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
            @Nullable CoverageBody coverage,
            @Nullable String detailed_report) {}

    record CoverageBody(
            @Nullable List<ReadBody> read, @Nullable String how_chosen) {}

    record ReadBody(@Nullable String role, @Nullable Integer count) {}

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

    record Parsed(
            @Nullable String summary,
            String verdict,
            List<Cause> causes,
            List<RuledOutCheck> ruledOut,
            @Nullable String detailedReport,
            @Nullable String verdictNote,
            @Nullable Coverage coverage,
            List<String> platformNotes) {}

    /** Throws when the text is not the expected shape: an immutable report must not degrade to unvalidated prose. */
    static Parsed parse(
            ObjectMapper mapper,
            String text,
            AgenticRcaEngine.Evidence evidence,
            boolean repoCloned,
            String projectId) {
        ReportBody body = body(mapper, text, projectId);
        List<Cause> causes = new ArrayList<>();
        List<String> platformNotes = new ArrayList<>();
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
            List<String> traces = kept(c.evidence_trace_ids(), evidence.citableTraceIds());
            List<String> sessions = kept(c.evidence_session_ids(), evidence.citableSessionIds());
            long flaggedTraces =
                    traces.stream().filter(evidence.flaggedTraceIds()::contains).count();
            long flaggedSessions = sessions.stream()
                    .filter(evidence.flaggedSessionIds()::contains)
                    .count();
            if (flaggedTraces == 0 && flaggedSessions == 0) {
                unreceipted++;
                continue;
            }
            // In session grain the cited traces are turns inside the cited sessions, not rows of their own.
            long cited = EvidenceSides.SESSIONS.equals(evidence.grain())
                    ? flaggedSessions
                    : Math.max(flaggedTraces, flaggedSessions);
            int ceiling = evidence.flaggedCount();
            int floor = (int) Math.min(Math.max(1, cited), ceiling);
            Integer claimed = c.affected_count();
            int count = Math.min(Math.max(claimed == null ? floor : claimed, floor), ceiling);
            if (claimed != null && claimed != count) {
                platformNotes.add(String.format(
                        Locale.ROOT,
                        "> **Count corrected by the platform.** \"%s\" claimed %d flagged rows; recorded as %d,"
                                + " between the %d flagged rows it cites and the %d flagged in the evidence.",
                        title,
                        claimed,
                        count,
                        floor,
                        ceiling));
            }
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
                    count,
                    ceiling));
        }
        if (unreceipted > 0 || belowMedium > 0 || unlabelled > 0) {
            log.warn(
                    "rca analysis project={} dropped {} cause(s) citing no flagged row of this finding, {} below medium"
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
                    + " and a flagged trace or session from this finding's evidence. Recorded as"
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
                verdictNote,
                coverage(body.coverage(), evidence.recordedByRole(), projectId),
                List.copyOf(platformNotes));
    }

    /** In reply order; an unknown or repeated role is dropped, and each count is held to what was recorded. */
    private static @Nullable Coverage coverage(
            @Nullable CoverageBody body, Map<String, Long> recorded, String projectId) {
        if (body == null || body.read() == null) {
            log.warn("rca analysis project={} returned no coverage", projectId);
            return null;
        }
        Map<String, RoleCount> read = new LinkedHashMap<>();
        for (ReadBody r : body.read()) {
            if (r == null) continue;
            String role = label(r.role());
            if (!FindingEvidenceRow.Role.ALL.contains(role) || read.containsKey(role)) continue;
            long ceiling = recorded.getOrDefault(role, 0L);
            long count = Math.min(Math.max(r.count() == null ? 0 : r.count(), 0), ceiling);
            read.put(role, new RoleCount(role, (int) count));
        }
        return new Coverage(List.copyOf(read.values()), blankToNull(body.how_chosen()));
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

    /** The model may capitalise or pad an enum value. */
    private static String label(@Nullable String v) {
        return v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static ReportBody body(ObjectMapper mapper, String text, String projectId) {
        ReportBody body = null;
        Exception failure = null;
        try {
            body = bind(mapper, text);
        } catch (Exception e) {
            // Keep the first failure as the cause: it names the offending property or offset.
            failure = e;
            body = salvage(mapper, extractObject(text));
        }
        if (body == null) {
            // This failure lands after a run spent its whole budget, so log a bounded prefix to tell prose, fence and
            // truncation apart.
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
     * Unknown properties are ignored on purpose, unlike the @Primary mapper: the schema allows extra fields, and one
     * stray key once rejected a complete investigation.
     */
    private static @Nullable ReportBody bind(ObjectMapper mapper, String candidate) throws java.io.IOException {
        if (candidate.isBlank()) return null;
        return mapper.readerFor(ReportBody.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(candidate);
    }

    /** A miss is null, not a throw, so the caller keeps the more informative first failure. */
    private static @Nullable ReportBody salvage(ObjectMapper mapper, String candidate) {
        try {
            return bind(mapper, candidate);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Kept local rather than shared with {@code BehaviorTriageVerdict#extractObject}: the lanes' envelope handling is
     * otherwise unrelated.
     */
    private static String extractObject(String text) {
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        return (open < 0 || close <= open) ? "" : text.substring(open, close + 1);
    }

    /** Never the whole body, which carries the agent's full report over the traces it read. */
    private static String abbreviate(String text) {
        if (text.isBlank()) return "<empty>";
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
