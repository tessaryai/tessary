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
     * One entry under "What else was checked".
     *
     * <p>A report written now stores each candidate cause the analysis ruled out as one plain sentence in
     * {@code question}, with assessment {@code ruled_out} and no {@code detail} or {@code measurement}. A report
     * written before that stored one entry per measured checklist item, with every assessment below and a
     * {@code detail}; those rows are read unchanged.
     *
     * @param check a stable id: {@code ruled_out_<n>} on a current report, the checklist id on an older one
     * @param passed true only when the assessment is {@code ruled_out}
     * @param detail the analysis's reasoning on an older report; null on a current one
     * @param assessment {@code ruled_out}, or on an older report also {@code contributing}, {@code explains}
     *     or {@code unknown}
     * @param measurement the measured numbers on an older report; null on a current one
     * @param question the ruled-out candidate as a plain sentence; on an older report the check as a question,
     *     null when it was skipped or written before the field existed
     */
    public record RuledOutCheck(
            String check,
            boolean passed,
            @Nullable String detail,
            String assessment,
            @Nullable String measurement,
            @Nullable String question) {

        /** The one {@code assessment} value written now. */
        public static final class Assessment {
            private Assessment() {}

            public static final String RULED_OUT = "ruled_out";
        }

        /** The {@code index}-th (1-based) candidate the analysis ruled out, as the sentence it wrote. */
        public static RuledOutCheck ruledOut(int index, String sentence) {
            return new RuledOutCheck("ruled_out_" + index, true, null, Assessment.RULED_OUT, null, sentence);
        }
    }

    /**
     * One cause a report found, the same shape for every report kind. The prose fields are plain language for
     * a reader with no context; the receipts are the id lists.
     *
     * <p>A cause is current-format when it carries {@code change}. Older causes have no {@code change} or
     * {@code type}, may be {@code low}, and name their kind on {@code attribution.kind}.
     *
     * @param confidence {@code high} or {@code medium}; {@code low} only on older reports
     * @param change {@code change} (something changed over time) or {@code standing} (something the agent always
     *     does); null on older reports
     * @param type {@code code}, {@code prompt}, {@code tool}, {@code model}, {@code traffic}, {@code upstream},
     *     {@code data} or {@code other}; null on older reports
     * @param whatChanged what the cause does; null when the analysis wrote nothing
     * @param howItCausedThis null on every report written before the field existed
     * @param nextStep what to fix
     * @param attribution null when the analysis attributed nothing
     * @param evidenceTraceIds trace ids from the finding's own evidence
     * @param evidenceSessionIds session ids from the finding's own evidence
     * @param affectedCount how many flagged rows the cause explains
     */
    public record Cause(
            String title,
            String confidence,
            @Nullable String change,
            @Nullable String type,
            @JsonProperty("what_changed") @Nullable String whatChanged,
            @JsonProperty("how_it_caused_this") @Nullable String howItCausedThis,
            @JsonProperty("next_step") @Nullable String nextStep,
            @Nullable Attribution attribution,
            @JsonProperty("evidence_trace_ids") List<String> evidenceTraceIds,
            @JsonProperty("evidence_session_ids") List<String> evidenceSessionIds,
            @JsonProperty("affected_count") int affectedCount) {

        public static final String HIGH = "high";
        public static final String MEDIUM = "medium";
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
            @Nullable String change,
            @Nullable String type,
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
                    change,
                    type,
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
     * @param kind {@code prompt}, {@code code}, {@code tool}, {@code model} or {@code unknown} on older reports;
     *     null on current ones, whose cause carries {@code type} instead
     */
    public record Attribution(
            @Nullable String kind,
            @Nullable String path,
            @Nullable String commit,
            @Nullable String excerpt) {}

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
            /** High first, then medium (and low on older reports); in stored order, which the cause filters index
             *  into. */
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
