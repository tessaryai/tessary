// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class RcaDtos {

    private RcaDtos() {}

    /**
     * A current report stores each ruled-out candidate as one sentence with no {@code detail} or {@code measurement};
     * older checklist rows are read unchanged.
     */
    public record RuledOutCheck(
            String check,
            boolean passed,
            @Nullable String detail,
            String assessment,
            @Nullable String measurement,
            @Nullable String question) {

        public static final class Assessment {
            private Assessment() {}

            public static final String RULED_OUT = "ruled_out";
        }

        public static RuledOutCheck ruledOut(int index, String sentence) {
            return new RuledOutCheck("ruled_out_" + index, true, null, Assessment.RULED_OUT, null, sentence);
        }
    }

    /**
     * Current-format when it carries {@code change}; older causes lack {@code change} and {@code type} and may be
     * {@code low}. @param howItCausedThis null on reports written before the field existed
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

    /** A blank summary, or one that is the agent's raw reply, falls back to the first cause's title. */
    public static @Nullable String summaryOf(@Nullable String summary, List<Cause> causes) {
        String s = summary == null ? "" : summary.strip();
        if (!s.isEmpty() && !s.startsWith("{") && !s.startsWith("```")) return summary;
        return causes.isEmpty() ? null : causes.get(0).title();
    }

    /** Bound leniently so older stored shapes map instead of reading empty. */
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

    /** Ordered as stored, since cause filters index into it. Older metric reports stored {@code hypotheses}. */
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

    /** @param kind on older reports only; current causes carry {@code type} */
    public record Attribution(
            @Nullable String kind,
            @Nullable String path,
            @Nullable String commit,
            @Nullable String excerpt) {}

    /**
     * Where an analysis stands: {@code queued} until a worker holds it, {@code running}, {@code stopped} when it failed,
     * {@code done}. A queued or running job shows no ended run, since that run belongs to an attempt before this one.
     *
     * @param queuedAt when the job last changed state, which for a queued job is when it was queued @param doing the
     * step the run is on, only while it runs @param stopCode the code the run stopped on
     */
    public record RcaRunView(
            String state,
            @JsonProperty("queued_at") String queuedAt,
            @JsonProperty("run_id") @Nullable String runId,
            @JsonProperty("started_at") @Nullable String startedAt,
            @JsonProperty("ended_at") @Nullable String endedAt,
            @JsonProperty("step_count") int stepCount,
            @Nullable String doing,
            @JsonProperty("stop_code") @Nullable String stopCode) {

        public static final String QUEUED = "queued";
        public static final String RUNNING = "running";
        public static final String STOPPED = "stopped";
        public static final String DONE = "done";

        public static RcaRunView of(RcaReportRepository.RunState s) {
            String state =
                    switch (s.jobStatus()) {
                        case "pending" -> QUEUED;
                        case "claimed" -> s.leased() ? RUNNING : QUEUED;
                        case "done" -> DONE;
                        default -> STOPPED;
                    };
            boolean live = QUEUED.equals(state) || RUNNING.equals(state);
            if (s.runId() == null || (live && s.endedAt() != null)) {
                return new RcaRunView(state, s.jobUpdatedAt(), null, null, null, 0, null, null);
            }
            return new RcaRunView(
                    state,
                    s.jobUpdatedAt(),
                    s.runId(),
                    s.startedAt(),
                    s.endedAt(),
                    s.stepCount(),
                    RUNNING.equals(state) ? s.doing() : null,
                    s.stopCode());
        }

        /** When the state began, for a list mark: queued since queued, running since started, stopped since ended. */
        public static String since(RcaRunView run) {
            String at =
                    switch (run.state()) {
                        case RUNNING -> run.startedAt();
                        case STOPPED -> run.endedAt();
                        default -> null;
                    };
            return at == null ? run.queuedAt() : at;
        }
    }

    public record RcaReportView(
            String id,
            @JsonProperty("job_id") String jobId,
            @JsonProperty("subject_kind") String subjectKind,
            @JsonProperty("subject_id") String subjectId,
            @JsonProperty("subject_label") String subjectLabel,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            String metric,
            @JsonProperty("report_kind") String reportKind,
            @JsonProperty("window_from") String windowFrom,
            @JsonProperty("window_split") String windowSplit,
            @JsonProperty("window_to") String windowTo,
            @JsonProperty("current_value") double currentValue,
            @JsonProperty("prior_value") double priorValue,
            double delta,
            String status,
            @Nullable String verdict,
            @Nullable String summary,
            @JsonProperty("ruled_out") List<RuledOutCheck> ruledOut,
            /** In stored order, which the cause filters index into. */
            List<Cause> causes,
            @JsonProperty("detailed_report") @Nullable String detailedReport,
            String engine,
            /** Null on reports written before the column existed: unknown, not "no repository". */
            @JsonProperty("repo_available") @Nullable Boolean repoAvailable,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("completed_at") @Nullable String completedAt,
            /** Where the analysis stands; null only on a view built without its job. */
            @Nullable RcaRunView run) {

        public static RcaReportView of(RcaReportRow r, ObjectMapper mapper) {
            return of(r, mapper, null);
        }

        public static RcaReportView of(RcaReportRow r, ObjectMapper mapper, @Nullable RcaRunView run) {
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
                    r.completedAt(),
                    run);
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
