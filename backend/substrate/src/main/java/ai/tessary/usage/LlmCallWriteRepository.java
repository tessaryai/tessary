// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The write half of the {@code llm_call} ledger, split from {@code metering.LlmUsageQueryRepository} so recording a
 * call does not drag the reporting stack under every caller. Per call and read live, unlike {@code metric_rollup}'s
 * closed buckets, which remain the billing basis. {@link LlmUsageAccountant} swallows failures here.
 */
@Repository
public class LlmCallWriteRepository {

    private final JdbcClient jdbc;

    public LlmCallWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LlmCallRow row) {
        jdbc.sql("""
                        INSERT INTO llm_call (id, project_id, lane, model, funding,
                                              input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                                              cost_usd, price_book_version, latency_ms, subject_kind, subject_id,
                                              created_at)
                        VALUES (:id, :projectId, :lane, :model, :funding,
                                :in, :out, :cacheRead, :cacheWrite,
                                :cost, :priceBookVersion, :latency, :subjectKind, :subjectId,
                                :createdAt::timestamptz)
                        """)
                .param("id", row.id())
                .param("projectId", row.projectId())
                .param("lane", row.lane())
                .param("model", row.model())
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
