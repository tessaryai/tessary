// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
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
     * checks became subjective. {@code assessment} and {@code measurement} are null on those older
     * reports (whose checks were threshold gates), so readers must fall back to {@code passed}.
     *
     * @param check the check id — {@link RcaChecklist.Measurement#check}
     * @param passed legacy view of the assessment: true only when it is {@code ruled_out}
     * @param detail the analysis's reasoning for its assessment
     * @param assessment {@code ruled_out} | {@code contributing} | {@code explains} | {@code unknown}
     * @param measurement the numbers that were measured, which the assessment is a judgment of
     */
    public record RuledOutCheck(
            String check,
            boolean passed,
            String detail,
            @Nullable String assessment,
            @Nullable String measurement) {

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
                String check, @Nullable String assessment, String detail, String measured) {
            String normalized = Assessment.normalize(assessment);
            return new RuledOutCheck(check, Assessment.RULED_OUT.equals(normalized), detail, normalized, measured);
        }

        /** An item the analysis skipped — the measurement stands on its own, unjudged. */
        public static RuledOutCheck unassessed(String check, String measured) {
            return new RuledOutCheck(
                    check, false, "The analysis did not assess this check.", Assessment.UNKNOWN, measured);
        }
    }

    /** One synthesized root-cause hypothesis, with its receipts. */
    public record Hypothesis(
            String title,
            String confidence,
            String rationale,
            @JsonProperty("evidence_trace_ids") List<String> evidenceTraceIds) {}

    public record RcaReportView(
            String id,
            @JsonProperty("job_id") String jobId,
            @JsonProperty("subject_kind") String subjectKind,
            @JsonProperty("subject_id") String subjectId,
            @JsonProperty("subject_label") String subjectLabel,
            @JsonProperty("call_site_id") @Nullable String callSiteId,
            String metric,
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
            List<Hypothesis> hypotheses,
            @JsonProperty("detailed_report") @Nullable String detailedReport,
            String engine,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("completed_at") @Nullable String completedAt) {

        public static RcaReportView of(RcaReportRow r, ObjectMapper mapper) {
            return new RcaReportView(
                    r.id(),
                    r.jobId(),
                    r.subjectKind(),
                    r.subjectId(),
                    r.subjectLabel(),
                    r.callSiteId(),
                    r.metric(),
                    r.windowFrom(),
                    r.windowSplit(),
                    r.windowTo(),
                    r.currentValue(),
                    r.priorValue(),
                    r.delta(),
                    r.status(),
                    r.verdict(),
                    r.summary(),
                    readList(mapper, r.ruledOut(), new TypeReference<List<RuledOutCheck>>() {}),
                    readList(mapper, r.hypotheses(), new TypeReference<List<Hypothesis>>() {}),
                    r.detailedReport(),
                    r.engine(),
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
