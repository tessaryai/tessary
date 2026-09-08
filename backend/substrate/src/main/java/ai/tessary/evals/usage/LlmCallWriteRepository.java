// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.usage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The WRITE half of the llm_call ledger: append one recorded call.
 *
 * <p>Split from the reporting queries ({@code metering.LlmUsageQueryRepository}) because the two sit
 * at different layers. Every LLM caller records; only a billing surface aggregates. While they shared
 * a class, recording a call dragged the whole reporting stack under every caller of it — which is what
 * put `metering` underneath `judge`, `synth`, `compile`, `observer` and `rca` in the import graph.
 *
 * <p>Original doc:  * The single owner of {@code llm_call} SQL: the per-call append, and the org-scoped aggregations the
 * usage surface reads.
 *
 * <p><b>Why not {@code metric_rollup}.</b> That table meters CLOSED hour/day buckets of one
 * {@code llm_tokens} scalar — no lane, no model, no per-bucket token split — so it can say how many
 * tokens an org burned last hour and nothing about which part of the product burned them, at which
 * price, in which bucket. This ledger is written per call and read live, so the usage page is current
 * to the last call rather than lagging a bucket grain. The rollup stays the billing basis; this is the
 * breakdown.
 *
 * <p><b>Writes are fire-and-forget accounting, not the hot path's business.</b> {@link #insert} is
 * called after a completed LLM call, and {@link LlmUsageAccountant} swallows its failures — a ledger
 * outage must never fail a grading run.
 */
@Repository
public class LlmCallWriteRepository {

    private final JdbcClient jdbc;

    public LlmCallWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Append one call to the ledger. */
    public void insert(LlmCallRow row) {
        jdbc.sql("""
                        INSERT INTO llm_call (id, project_id, lane, model, service_tier, funding,
                                              input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                                              cost_usd, price_book_version, latency_ms, subject_kind, subject_id,
                                              created_at)
                        VALUES (:id, :projectId, :lane, :model, :tier, :funding,
                                :in, :out, :cacheRead, :cacheWrite,
                                :cost, :priceBookVersion, :latency, :subjectKind, :subjectId,
                                :createdAt::timestamptz)
                        """)
                .param("id", row.id())
                .param("projectId", row.projectId())
                .param("lane", row.lane())
                .param("model", row.model())
                .param("tier", row.serviceTier())
                .param("funding", row.funding())
                .param("in", row.inputTokens())
                .param("out", row.outputTokens())
                .param("cacheRead", row.cacheReadTokens())
                .param("cacheWrite", row.cacheWriteTokens())
                .param("cost", row.costUsd())
                .param("priceBookVersion", row.priceBookVersion())
                .param("latency", row.latencyMs())
                .param("subjectKind", row.subjectKind())
                .param("subjectId", row.subjectId())
                .param("createdAt", row.createdAt())
                .update();
    }
}
