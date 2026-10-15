// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Row;
import ai.tessary.tenant.Ids;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * Raw inserts for {@link SampleProjectSeedListener}'s demo data, bypassing the business-logic repositories. Trace and
 * span volume is multi-row batched SQL: the live ingest writers carry replay semantics a one-shot seed does not need.
 */
@Repository
public class SampleDataRepository {

    /** Comfortably under Postgres's bind-parameter limit even for {@link SpanRow}'s ~40 columns. */
    private static final int BATCH_SIZE = 200;

    private final JdbcClient jdbc;

    public SampleDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code rca_report.job_id} is a real FK to {@code job(id)}. */
    public String insertDoneRcaJob(String id, String projectId, String now) {
        jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, created_at, updated_at)
                VALUES (:id, :pid, 'rca', 'done', :now, :now)
                """).param("id", id).param("pid", projectId).param("now", now).update();
        return id;
    }

    /** {@code finding_triage_paired_check} requires the triage columns to be all-null or all-set. */
    public void insertFinding(Finding f) {
        jdbc.sql("""
                INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                    subject_label, call_site_id, status, onset_at, last_seen_at, title, basis, severity,
                    sample_count, payload, created_at, updated_at, triage_verdict, triage_action,
                    triage_summary, triaged_at)
                VALUES (:id, :pid, :classifierKey, :causeKey, :subjectKind, :subjectId,
                    :subjectLabel, :csid, 'open', :onset, :lastSeenAt, :title, :basis, :severity,
                    :sampleCount, :payload::jsonb, :now, :now, :triageVerdict, :triageAction,
                    :triageSummary, :triagedAt)
                """)
                .param("id", f.id())
                .param("pid", f.projectId())
                .param("classifierKey", f.classifierKey())
                .param("causeKey", f.causeKey())
                .param("subjectKind", f.subjectKind())
                .param("subjectId", f.subjectId())
                .param("subjectLabel", f.subjectLabel())
                .param("csid", f.callSiteId())
                .param("onset", f.onset())
                .param("lastSeenAt", f.lastSeenAt())
                .param("title", f.title())
                .param("basis", f.basis())
                .param("severity", f.severity())
                .param("sampleCount", f.sampleCount())
                .param("payload", f.payload())
                .param("now", f.now())
                .param("triageVerdict", f.triageVerdict())
                .param("triageAction", f.triageAction())
                .param("triageSummary", f.triageSummary())
                .param("triagedAt", f.triagedAt())
                .update();
    }

    /**
     * Without these, RCA fails with {@code SUBJECT_NOT_FOUND} on a seeded finding. {@code evidence_counts} is bumped so
     * readers see the population as complete.
     */
    public void insertFindingEvidence(
            String projectId, String findingId, String role, List<String> traceIds, String now) {
        for (List<String> chunk : chunks(traceIds)) {
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) values.append(", ");
                values.append("(:id")
                        .append(i)
                        .append(", :pid, :fid, :trace")
                        .append(i)
                        .append(", :role, :rank")
                        .append(i)
                        .append(", :now)");
            }
            StatementSpec spec = jdbc.sql("INSERT INTO finding_evidence (id, project_id, finding_id, trace_id,"
                            + " role, rank, created_at) VALUES " + values
                            + " ON CONFLICT DO NOTHING")
                    .param("pid", projectId)
                    .param("fid", findingId)
                    .param("role", role)
                    .param("now", now);
            for (int i = 0; i < chunk.size(); i++) {
                spec = spec.param("id" + i, Ids.ulid())
                        .param("trace" + i, chunk.get(i))
                        .param("rank" + i, i);
            }
            spec.update();
        }
        jdbc.sql("UPDATE finding SET evidence_counts = COALESCE(evidence_counts, '{}'::jsonb)"
                        + " || jsonb_build_object(:role, :n) WHERE id = :fid AND project_id = :pid")
                .param("role", role)
                .param("n", traceIds.size())
                .param("fid", findingId)
                .param("pid", projectId)
                .update();
    }

    /** An open {@code eval_case} row plus {@code finding.case_id}, committed together in the caller's transaction. */
    /**
     * A direct write rather than {@code PipelineService}'s import path: announcing the change would rewind sweeps over
     * seeded data.
     */
    public void setCallSiteShape(String projectId, String callSiteId, String shape) {
        jdbc.sql("UPDATE call_site SET shape = :shape WHERE project_id = :pid AND id = :id")
                .param("shape", shape)
                .param("pid", projectId)
                .param("id", callSiteId)
                .update();
    }

    public void insertCase(SampleCase c) {
        jdbc.sql("""
                INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id,
                    subject_label, call_site_id, metric, state, title, basis, severity, onset_at,
                    current_value, baseline_value, delta, opened_at, last_seen_at, updated_at)
                VALUES (:id, :pid, :seq, :detector, :subjectKind, :subjectId, :label, :csid,
                    :metric, 'open', :title, :basis, :severity, :onset, :current, :baseline,
                    :delta, :onset, :now, :now)
                """)
                .param("id", c.id())
                .param("pid", c.projectId())
                .param("seq", c.seq())
                .param("detector", c.detector())
                .param("subjectKind", c.subjectKind())
                .param("subjectId", c.subjectId())
                .param("label", c.label())
                .param("csid", c.callSiteId())
                .param("metric", c.metric())
                .param("title", c.title())
                .param("basis", c.basis())
                .param("severity", c.severity())
                .param("onset", c.onset())
                .param("current", c.currentValue())
                .param("baseline", c.baselineValue())
                .param("delta", c.delta())
                .param("now", c.now())
                .update();
        jdbc.sql("UPDATE finding SET case_id = :cid, updated_at = :now WHERE project_id = :pid AND id = :fid")
                .param("cid", c.id())
                .param("now", c.now())
                .param("pid", c.projectId())
                .param("fid", c.findingId())
                .update();
    }

    public void insertCompletedRcaReport(SampleRcaReport r) {
        jdbc.sql("""
                INSERT INTO rca_report (id, project_id, job_id, subject_kind, subject_id, subject_label,
                    call_site_id, metric, window_from, window_split, window_to, current_value,
                    prior_value, delta, status, verdict, summary, ruled_out, causes,
                    detailed_report, engine, created_at, completed_at, finding_id)
                VALUES (:id, :pid, :jobId, :subjectKind, :subjectId, :label, :csid,
                    :metric, :windowFrom, :windowSplit, :windowTo, :current, :prior, :delta,
                    'done', :verdict, :summary, :ruledOut::jsonb, :causes::jsonb,
                    :detailedReport, :engine, :created, :completed, :findingId)
                """)
                .param("id", r.id())
                .param("pid", r.projectId())
                .param("jobId", r.jobId())
                .param("subjectKind", r.subjectKind())
                .param("subjectId", r.subjectId())
                .param("label", r.label())
                .param("csid", r.callSiteId())
                .param("metric", r.metric())
                .param("windowFrom", r.windowFrom())
                .param("windowSplit", r.windowSplit())
                .param("windowTo", r.windowTo())
                .param("current", r.currentValue())
                .param("prior", r.priorValue())
                .param("delta", r.delta())
                .param("verdict", r.verdict())
                .param("summary", r.summary())
                .param("ruledOut", r.ruledOut())
                .param("causes", r.causes())
                .param("detailedReport", r.detailedReport())
                .param("engine", r.engine())
                .param("created", r.createdAt())
                .param("completed", r.completedAt())
                .param("findingId", r.findingId())
                .update();
    }

    public void insertTraces(List<TraceV2Row> rows) {
        for (List<TraceV2Row> chunk : chunks(rows)) {
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) values.append(", ");
                values.append("(:pid")
                        .append(i)
                        .append(", :id")
                        .append(i)
                        .append(", :sid")
                        .append(i)
                        .append(", :ptid")
                        .append(i)
                        .append(", :thid")
                        .append(i)
                        .append(", :name")
                        .append(i)
                        .append(", :uid")
                        .append(i)
                        .append(", :pvid")
                        .append(i)
                        .append(", :status")
                        .append(i)
                        .append(", :startedAt")
                        .append(i)
                        .append("::timestamptz, :endedAt")
                        .append(i)
                        .append("::timestamptz, :spanCount")
                        .append(i)
                        .append(", :errorCount")
                        .append(i)
                        .append(", :inputTokens")
                        .append(i)
                        .append(", :outputTokens")
                        .append(i)
                        .append(", :cacheReadTokens")
                        .append(i)
                        .append(", :cacheWriteTokens")
                        .append(i)
                        .append(", :reasoningTokens")
                        .append(i)
                        .append(", :totalTokens")
                        .append(i)
                        .append(", :inputCost")
                        .append(i)
                        .append("::numeric, :outputCost")
                        .append(i)
                        .append("::numeric, :totalCost")
                        .append(i)
                        .append("::numeric, :unpricedSpans")
                        .append(i)
                        .append(", :inputPreview")
                        .append(i)
                        .append(", :outputPreview")
                        .append(i)
                        .append(", :csid")
                        .append(i)
                        .append(", :rollupDueAt")
                        .append(i)
                        .append("::timestamptz, :rolledUpAt")
                        .append(i)
                        .append("::timestamptz, :rolledUpThrough")
                        .append(i)
                        .append("::timestamptz, :isSettled")
                        .append(i)
                        .append(", :hasRootSpan")
                        .append(i)
                        .append(", :eventTs")
                        .append(i)
                        .append("::timestamptz, :isDeleted")
                        .append(i)
                        .append(")");
            }
            StatementSpec spec = jdbc.sql("""
                    INSERT INTO trace (project_id, id, session_id, parent_trace_id, thread_id, name, user_id,
                        project_version_id, status, started_at, ended_at, span_count, error_count,
                        input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens,
                        total_tokens, input_cost, output_cost, total_cost, unpriced_spans, input_preview,
                        output_preview, call_site_id, rollup_due_at, rolled_up_at, rolled_up_through,
                        is_settled, has_root_span, event_ts, is_deleted)
                    VALUES """ + values);
            for (int i = 0; i < chunk.size(); i++) {
                TraceV2Row t = chunk.get(i);
                spec = spec.param("pid" + i, t.projectId())
                        .param("id" + i, t.id())
                        .param("sid" + i, t.sessionId())
                        .param("ptid" + i, t.parentTraceId())
                        .param("thid" + i, t.threadId())
                        .param("name" + i, t.name())
                        .param("uid" + i, t.userId())
                        .param("pvid" + i, t.projectVersionId())
                        .param("status" + i, t.status())
                        .param("startedAt" + i, t.startedAt())
                        .param("endedAt" + i, t.endedAt())
                        .param("spanCount" + i, t.spanCount())
                        .param("errorCount" + i, t.errorCount())
                        .param("inputTokens" + i, t.inputTokens())
                        .param("outputTokens" + i, t.outputTokens())
                        .param("cacheReadTokens" + i, t.cacheReadTokens())
                        .param("cacheWriteTokens" + i, t.cacheWriteTokens())
                        .param("reasoningTokens" + i, t.reasoningTokens())
                        .param("totalTokens" + i, t.totalTokens())
                        .param("inputCost" + i, t.inputCost())
                        .param("outputCost" + i, t.outputCost())
                        .param("totalCost" + i, t.totalCost())
                        .param("unpricedSpans" + i, t.unpricedSpans())
                        .param("inputPreview" + i, t.inputPreview())
                        .param("outputPreview" + i, t.outputPreview())
                        .param("csid" + i, t.callSiteId())
                        .param("rollupDueAt" + i, t.rollupDueAt())
                        .param("rolledUpAt" + i, t.rolledUpAt())
                        .param("rolledUpThrough" + i, t.rolledUpThrough())
                        .param("isSettled" + i, t.isSettled())
                        .param("hasRootSpan" + i, t.hasRootSpan())
                        .param("eventTs" + i, t.eventTs())
                        .param("isDeleted" + i, t.isDeleted());
            }
            spec.update();
        }
    }

    /**
     * Every column {@code SpanRepository.upsertAll} writes, minus the GENERATED ones ({@code depth},
     * {@code total_tokens}, {@code total_cost}) an insert may not name.
     */
    public void insertSpans(List<SpanRow> rows) {
        for (List<SpanRow> chunk : chunks(rows)) {
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) values.append(", ");
                values.append("(:pid")
                        .append(i)
                        .append(", :traceId")
                        .append(i)
                        .append(", :id")
                        .append(i)
                        .append(", :parentSpanId")
                        .append(i)
                        .append(", :path")
                        .append(i)
                        .append("::ltree, :sessionId")
                        .append(i)
                        .append(", :userId")
                        .append(i)
                        .append(", :pvid")
                        .append(i)
                        .append(", :csid")
                        .append(i)
                        .append(", :traceName")
                        .append(i)
                        .append(", :kind")
                        .append(i)
                        .append(", :name")
                        .append(i)
                        .append(", :isLogicalRoot")
                        .append(i)
                        .append(", :status")
                        .append(i)
                        .append(", :level")
                        .append(i)
                        .append(", :errorType")
                        .append(i)
                        .append(", :errorMessage")
                        .append(i)
                        .append(", :startedAt")
                        .append(i)
                        .append("::timestamptz, :endedAt")
                        .append(i)
                        .append("::timestamptz, :latencyMs")
                        .append(i)
                        .append(", :ttftMs")
                        .append(i)
                        .append(", :providedModelName")
                        .append(i)
                        .append(", :modelId")
                        .append(i)
                        .append(", :inputTokens")
                        .append(i)
                        .append(", :outputTokens")
                        .append(i)
                        .append(", :cacheReadTokens")
                        .append(i)
                        .append(", :cacheWriteTokens")
                        .append(i)
                        .append(", :reasoningTokens")
                        .append(i)
                        .append(", :inputCost")
                        .append(i)
                        .append("::numeric, :outputCost")
                        .append(i)
                        .append("::numeric, :cacheReadCost")
                        .append(i)
                        .append("::numeric, :cacheWriteCost")
                        .append(i)
                        .append("::numeric, :costSource")
                        .append(i)
                        .append(", :priceBookVersion")
                        .append(i)
                        .append(", :inputPreview")
                        .append(i)
                        .append(", :outputPreview")
                        .append(i)
                        .append(", :correlationState")
                        .append(i)
                        .append(", :pathState")
                        .append(i)
                        .append(", :eventTs")
                        .append(i)
                        .append("::timestamptz, :isDeleted")
                        .append(i)
                        .append(")");
            }
            StatementSpec spec = jdbc.sql("""
                    INSERT INTO span (project_id, trace_id, id, parent_span_id, path, session_id, user_id,
                        project_version_id, call_site_id, trace_name, kind, name, is_logical_root, status,
                        level, error_type, error_message, started_at, ended_at, latency_ms, ttft_ms,
                        provided_model_name, model_id, input_tokens, output_tokens, cache_read_tokens,
                        cache_write_tokens, reasoning_tokens, input_cost, output_cost, cache_read_cost,
                        cache_write_cost, cost_source, price_book_version, input_preview, output_preview,
                        correlation_state, path_state, event_ts, is_deleted)
                    VALUES """ + values);
            for (int i = 0; i < chunk.size(); i++) {
                SpanRow s = chunk.get(i);
                spec = spec.param("pid" + i, s.projectId())
                        .param("traceId" + i, s.traceId())
                        .param("id" + i, s.id())
                        .param("parentSpanId" + i, s.parentSpanId())
                        .param("path" + i, s.path())
                        .param("sessionId" + i, s.sessionId())
                        .param("userId" + i, s.userId())
                        .param("pvid" + i, s.projectVersionId())
                        .param("csid" + i, s.callSiteId())
                        .param("traceName" + i, s.traceName())
                        .param("kind" + i, s.kind())
                        .param("name" + i, s.name())
                        .param("isLogicalRoot" + i, s.isLogicalRoot())
                        .param("status" + i, s.status())
                        .param("level" + i, s.level())
                        .param("errorType" + i, s.errorType())
                        .param("errorMessage" + i, s.errorMessage())
                        .param("startedAt" + i, s.startedAt())
                        .param("endedAt" + i, s.endedAt())
                        .param("latencyMs" + i, s.latencyMs())
                        .param("ttftMs" + i, s.ttftMs())
                        .param("providedModelName" + i, s.providedModelName())
                        .param("modelId" + i, s.modelId())
                        .param("inputTokens" + i, s.inputTokens())
                        .param("outputTokens" + i, s.outputTokens())
                        .param("cacheReadTokens" + i, s.cacheReadTokens())
                        .param("cacheWriteTokens" + i, s.cacheWriteTokens())
                        .param("reasoningTokens" + i, s.reasoningTokens())
                        .param("inputCost" + i, s.inputCost())
                        .param("outputCost" + i, s.outputCost())
                        .param("cacheReadCost" + i, s.cacheReadCost())
                        .param("cacheWriteCost" + i, s.cacheWriteCost())
                        .param("costSource" + i, s.costSource())
                        .param("priceBookVersion" + i, s.priceBookVersion())
                        .param("inputPreview" + i, s.inputPreview())
                        .param("outputPreview" + i, s.outputPreview())
                        .param("correlationState" + i, s.correlationState())
                        .param("pathState" + i, s.pathState())
                        .param("eventTs" + i, s.eventTs())
                        .param("isDeleted" + i, s.isDeleted());
            }
            spec.update();
        }
    }

    public void insertSpanPayloads(List<SpanPayloadRow> rows) {
        for (List<SpanPayloadRow> chunk : chunks(rows)) {
            StringBuilder values = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) values.append(", ");
                values.append("(:pid")
                        .append(i)
                        .append(", :traceId")
                        .append(i)
                        .append(", :spanId")
                        .append(i)
                        .append(", :input")
                        .append(i)
                        .append(", :output")
                        .append(i)
                        .append(", :attributes")
                        .append(i)
                        .append("::jsonb, :providedUsage")
                        .append(i)
                        .append("::jsonb, :eventTs")
                        .append(i)
                        .append("::timestamptz)");
            }
            StatementSpec spec = jdbc.sql("INSERT INTO span_payload (project_id, trace_id, span_id, input,"
                    + " output, attributes, provided_usage, event_ts) VALUES " + values);
            for (int i = 0; i < chunk.size(); i++) {
                SpanPayloadRow p = chunk.get(i);
                spec = spec.param("pid" + i, p.projectId())
                        .param("traceId" + i, p.traceId())
                        .param("spanId" + i, p.spanId())
                        .param("input" + i, p.input())
                        .param("output" + i, p.output())
                        .param("attributes" + i, p.attributes())
                        .param("providedUsage" + i, p.providedUsage())
                        .param("eventTs" + i, p.eventTs());
            }
            spec.update();
        }
    }

    private static <T> List<List<T>> chunks(List<T> rows) {
        List<List<T>> out = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            out.add(rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
        }
        return out;
    }

    public record Finding(
            String id,
            String projectId,
            String classifierKey,
            String causeKey,
            String subjectKind,
            String subjectId,
            String subjectLabel,
            String callSiteId,
            String onset,
            String lastSeenAt,
            String title,
            String basis,
            double severity,
            long sampleCount,
            @Nullable String payload,
            String now,
            @Nullable String triageVerdict,
            @Nullable String triageAction,
            @Nullable String triageSummary,
            @Nullable String triagedAt) {}

    public record SampleCase(
            String id,
            String projectId,
            long seq,
            String detector,
            String subjectKind,
            String subjectId,
            String label,
            String callSiteId,
            String metric,
            String title,
            String basis,
            double severity,
            String onset,
            double currentValue,
            double baselineValue,
            double delta,
            String now,
            String findingId) {}

    public record SampleRcaReport(
            String id,
            String projectId,
            String jobId,
            String subjectKind,
            String subjectId,
            String label,
            String callSiteId,
            String metric,
            String windowFrom,
            String windowSplit,
            String windowTo,
            double currentValue,
            double priorValue,
            double delta,
            String verdict,
            String summary,
            @Nullable String ruledOut,
            @Nullable String causes,
            @Nullable String detailedReport,
            String engine,
            String createdAt,
            @Nullable String completedAt,
            String findingId) {}
}
