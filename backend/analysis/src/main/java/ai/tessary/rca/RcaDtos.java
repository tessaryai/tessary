// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTOs for the RCA surface. Snake_case on the wire. */
public final class RcaDtos {

    private RcaDtos() {}

    /**
     * One checklist item: a structural cause the movement was measured against, plus the analysis's
     * own call on what that measurement means.
     *
     * <p>Wire name and column stay {@code ruled_out} for continuity with reports written before the
     * checks became subjective.
     *
     * @param check the check id — {@link RcaChecklist.Measurement#check}
     * @param passed legacy view of the assessment: true only when it is {@code ruled_out}
     * @param detail the analysis's reasoning for its assessment
     * @param assessment {@code ruled_out} | {@code contributing} | {@code explains} | {@code unknown}
     * @param measurement the numbers that were measured, which the assessment is a judgment of
     * @param question the check as a plain question, as the analysis wrote it; null on a check it skipped
     *     and on reports written before the field existed
     */
    public record RuledOutCheck(
            String check,
            boolean passed,
            String detail,
            String assessment,
            @Nullable String measurement,
            @Nullable String question) {

        /** {@code assessment} values — how much of the movement this check accounts for. */
        public static final class Assessment {
            private Assessment() {}

            /** Measured and does not explain any of the movement. */
            public static final String RULED_OUT = "ruled_out";
            /** Part of the story, but not the whole of it. */
            public static final String CONTRIBUTING = "contributing";
            /** Accounts for the movement on its own. */
            public static final String EXPLAINS = "explains";
            /** The evidence does not settle it either way. */
            public static final String UNKNOWN = "unknown";

            static String normalize(@Nullable String v) {
                return switch (v == null ? "" : v) {
                    case RULED_OUT, CONTRIBUTING, EXPLAINS -> v;
                    default -> UNKNOWN;
                };
            }
        }

        /** An item the analysis assessed, over the measurement it was given. */
        public static RuledOutCheck assessed(
                String check, @Nullable String question, @Nullable String assessment, String detail, String measured) {
            String normalized = Assessment.normalize(assessment);
            return new RuledOutCheck(
                    check, Assessment.RULED_OUT.equals(normalized), detail, normalized, measured, question);
        }

        /** An item the analysis skipped — the measurement stands on its own, unjudged. */
        public static RuledOutCheck unassessed(String check, String measured) {
            return new RuledOutCheck(
                    check, false, "The analysis did not assess this check.", Assessment.UNKNOWN, measured, null);
        }
    }

    /**
     * One cause a report found, the same shape for every report kind: what changed (or, on a frustration or
     * groundedness report, what the agent did), how that produced what the classifier saw, and what to do
     * next. The prose fields are plain language for a reader with no context; the receipts are the id lists.
     *
     * @param confidence {@code high} when proven, {@code medium} or {@code low} for a lead
     * @param whatChanged null when the analysis wrote nothing, and on older reports that had no such field
     * @param howItCausedThis null on every report written before the field existed
     * @param nextStep for a proven cause, what to do; for a lead, what would confirm it
     * @param attribution null when the analysis attributed nothing
     * @param evidenceTraceIds trace ids from the finding's own evidence refs: either side on a metric
     *     cause, the flagged turns on a frustration one, the traces with a flagged answer on a groundedness
     *     one (at least one there)
     * @param evidenceSessionIds frustrated sessions from the finding's own evidence refs; at least one on a
     *     frustration cause, none on any other
     * @param affectedCount how many flagged sessions or traces show it; never fewer than a frustration or
     *     groundedness cause cites
     */
    public record Cause(
            String title,
            String confidence,
            @JsonProperty("what_changed") @Nullable String whatChanged,
            @JsonProperty("how_it_caused_this") @Nullable String howItCausedThis,
            @JsonProperty("next_step") @Nullable String nextStep,
            @Nullable Attribution attribution,
            @JsonProperty("evidence_trace_ids") List<String> evidenceTraceIds,
            @JsonProperty("evidence_session_ids") List<String> evidenceSessionIds,
            @JsonProperty("affected_count") int affectedCount) {

        public static final String HIGH = "high";
        public static final List<String> CONFIDENCES = List.of(HIGH, "medium", "low");
    }

    /**
     * The summary a reader sees, or null. A blank one falls back to the first cause's title. So does one that
     * is the agent's raw reply: before the fallback existed, a blank summary was replaced by the whole reply,
     * and those reports would otherwise print JSON where a sentence belongs.
     */
    public static @Nullable String summaryOf(@Nullable String summary, List<Cause> causes) {
        String s = summary == null ? "" : summary.strip();
        if (!s.isEmpty() && !s.startsWith("{") && !s.startsWith("```")) return summary;
        return causes.isEmpty() ? null : causes.get(0).title();
    }

    /**
     * Every shape a stored cause has had, bound leniently so an older row maps instead of reading as empty:
     * metric hypotheses ({@code rationale}), and frustration and groundedness causes before the shape was
     * unified ({@code what_the_agent_did}, {@code fix_suggestion}, {@code sessions_affected},
     * {@code traces_affected}).
     */
    record StoredCause(
            @Nullable String title,
            @Nullable String confidence,
            @Nullable String what_changed,
            @Nullable String how_it_caused_this,
            @Nullable String next_step,
            @Nullable Attribution attribution,
            @Nullable List<String> evidence_trace_ids,
            @Nullable List<String> evidence_session_ids,
            @Nullable Integer affected_count,
            @Nullable String rationale,
            @Nullable String what_the_agent_did,
            @Nullable String fix_suggestion,
            @Nullable Integer sessions_affected,
            @Nullable Integer traces_affected) {

        Cause toCause(String reportKind) {
            Integer legacyCount = RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES.equals(reportKind)
                    ? traces_affected
                    : sessions_affected;
            Integer count = affected_count != null ? affected_count : legacyCount;
            return new Cause(
                    title == null ? "" : title,
                    confidence == null ? "low" : confidence,
                    firstPresent(what_changed, rationale, what_the_agent_did),
                    firstPresent(how_it_caused_this),
                    firstPresent(next_step, fix_suggestion),
                    attribution,
                    evidence_trace_ids == null ? List.of() : evidence_trace_ids,
                    evidence_session_ids == null ? List.of() : evidence_session_ids,
                    count == null ? 0 : count);
        }

        private static @Nullable String firstPresent(@Nullable String... values) {
            for (String v : values) {
                if (v != null && !v.isBlank()) return v;
            }
            return null;
        }
    }

    /**
     * A report's causes as stored, in stored order: the cause filters address a cause by its index. A report
     * written before every kind shared this shape stored a metric run's causes as {@code hypotheses}, so those
     * are read when {@code causes} is empty.
     */
    public static List<Cause> causesOf(
            ObjectMapper mapper, String reportKind, @Nullable String causesJson, @Nullable String hypothesesJson) {
        List<StoredCause> stored = readStored(mapper, causesJson);
        if (stored.isEmpty()) stored = readStored(mapper, hypothesesJson);
        return stored.stream().map(s -> s.toCause(reportKind)).toList();
    }

    private static List<StoredCause> readStored(ObjectMapper mapper, @Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readerFor(new TypeReference<List<StoredCause>>() {})
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Where in the repo a cause comes from.
     *
     * @param kind {@code prompt} | {@code code} | {@code tool} | {@code model} | {@code unknown}
     */
    public record Attribution(
            String kind,
            @Nullable String path,
            @Nullable String commit,
            @Nullable String excerpt) {

        /** {@code kind} values. Anything else a model returns reads as {@link #UNKNOWN}. */
        public static final List<String> KINDS = List.of("prompt", "code", "tool", "model", "unknown");

        public static final String UNKNOWN = "unknown";
    }

    public record RcaReportView(
            String id,
            @JsonProperty("job_id") String jobId,
            @JsonProperty("subject_kind") String subjectKind,
            @JsonProperty("subject_id") String subjectId,
            @JsonProperty("subject_label") String subjectLabel,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            String metric,
            /** {@code metric_movement}, {@code frustration_causes} or {@code groundedness_causes}: which
             *  question the report answers. */
            @JsonProperty("report_kind") String reportKind,
            @JsonProperty("window_from") String windowFrom,
            @JsonProperty("window_split") String windowSplit,
            @JsonProperty("window_to") String windowTo,
            @JsonProperty("current_value") double currentValue,
            @JsonProperty("prior_value") double priorValue,
            double delta,
            String status,
            @Nullable String verdict,
            /** One plain sentence; see {@link RcaDtos#summaryOf}. */
            @Nullable String summary,
            @JsonProperty("ruled_out") List<RuledOutCheck> ruledOut,
            /** Proven causes first, then leads; in stored order, which the cause filters index into. */
            List<Cause> causes,
            @JsonProperty("detailed_report") @Nullable String detailedReport,
            String engine,
            /** Null on reports written before the column existed: unknown, not "no repository". */
            @JsonProperty("repo_available") @Nullable Boolean repoAvailable,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("completed_at") @Nullable String completedAt) {

        public static RcaReportView of(RcaReportRow r, ObjectMapper mapper) {
            List<Cause> causes = causesOf(mapper, r.reportKind(), r.causes(), r.hypotheses());
            return new RcaReportView(
                    r.id(),
                    r.jobId(),
                    r.subjectKind(),
                    r.subjectId(),
                    r.subjectLabel(),
                    r.callSiteId(),
                    r.metric(),
                    r.reportKind(),
                    r.windowFrom(),
                    r.windowSplit(),
                    r.windowTo(),
                    r.currentValue(),
                    r.priorValue(),
                    r.delta(),
                    r.status(),
                    r.verdict(),
                    summaryOf(r.summary(), causes),
                    readList(mapper, r.ruledOut(), new TypeReference<List<RuledOutCheck>>() {}),
                    causes,
                    r.detailedReport(),
                    r.engine(),
                    r.repoAvailable(),
                    r.createdAt(),
                    r.completedAt());
        }

        private static <T> List<T> readList(ObjectMapper mapper, @Nullable String json, TypeReference<List<T>> type) {
            if (json == null || json.isBlank()) return List.of();
            try {
                return mapper.readValue(json, type);
            } catch (Exception e) {
                return List.of();
            }
        }
    }
}
