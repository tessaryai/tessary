// SPDX-License-Identifier: Apache-2.0
package ai.tessary.search;

import static ai.tessary.storage.SpanPayloadRepository.PAYLOAD_TSVECTOR;

import ai.tessary.search.GlobalSearchDtos.HitType;
import ai.tessary.search.GlobalSearchDtos.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each leg is independently tenant-scoped and merged in {@link GlobalSearchService}, never UNION-ed. The span leg's
 * tsvector must stay {@code SpanPayloadRepository.PAYLOAD_TSVECTOR} byte for byte: Postgres matches an expression index
 * only against an identical expression, and any variation silently seq-scans every payload. The trigram filter uses
 * {@code name %> :q} because the operator form is what {@code gin_trgm_ops} backs; its threshold is set
 * transaction-locally rather than as a shared GUC.
 */
@Repository
public class GlobalSearchRepository {

    /**
     * Lower than pg_trgm's 0.6 so short prefixes and mild misspellings surface. Applied as a transaction-local GUC so
     * the {@code %>} operator engages the index.
     */
    static final double TRIGRAM_THRESHOLD = 0.4;

    /**
     * {@code ts_rank} re-parses each payload, and ranking every match of a broad word took 17.7 s on 31,000 spans. A
     * broad word therefore ranks the first 200 matches, not the best 200.
     */
    static final int PAYLOAD_CANDIDATE_LIMIT = 200;

    private final JdbcClient jdbc;

    public GlobalSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One transaction so the trigram threshold is lowered for the whole fan-out and restored at commit. */
    @Transactional(readOnly = true)
    public List<SearchHit> searchLexical(String projectId, String query, int limit) {
        // is_local => true scopes it to this transaction without mutating a shared GUC.
        jdbc.sql("SELECT set_config('pg_trgm.word_similarity_threshold', :thr, true)")
                .param("thr", Double.toString(TRIGRAM_THRESHOLD))
                .query()
                .singleColumn();
        List<SearchHit> hits = new ArrayList<>();
        hits.addAll(searchSpans(projectId, query, limit));
        return hits;
    }

    /**
     * Two statements, not one OR: across the join the planner resolves the two-index choice by scanning. A hit is the
     * trace, deduplicated to its best-scoring span.
     */
    List<SearchHit> searchSpans(String projectId, String query, int limit) {
        // The FTS leg runs first, so a payload-matched trace keeps its payload-derived snippet.
        Map<String, SearchHit> byTrace = new LinkedHashMap<>();
        for (SearchHit hit : searchSpansByPayloadText(projectId, query, limit)) {
            byTrace.merge(hit.id(), hit, (a, b) -> a.score() >= b.score() ? a : b);
        }
        for (SearchHit hit : searchSpansByName(projectId, query, limit)) {
            byTrace.merge(hit.id(), hit, (a, b) -> a.score() >= b.score() ? a : b);
        }
        List<SearchHit> hits = new ArrayList<>(byTrace.values());
        hits.sort(java.util.Comparator.comparingDouble(SearchHit::score).reversed());
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    /** The inner {@code LIMIT} bounds the cost; ranking in the same statement would rank every match first. */
    private List<SearchHit> searchSpansByPayloadText(String projectId, String query, int limit) {
        return jdbc.sql("SELECT s.trace_id, s.name, p.input,"
                        + " ts_rank(" + PAYLOAD_TSVECTOR + ", plainto_tsquery('simple', :q))"
                        + " + word_similarity(:q, coalesce(s.name, '')) * 0.3 AS score"
                        + " FROM (SELECT p.project_id, p.trace_id, p.span_id, p.input, p.output"
                        + " FROM span_payload p"
                        + " WHERE p.project_id = :pid"
                        + " AND " + PAYLOAD_TSVECTOR + " @@ plainto_tsquery('simple', :q)"
                        + " LIMIT " + PAYLOAD_CANDIDATE_LIMIT + ") p"
                        + " JOIN span s ON s.project_id = p.project_id AND s.trace_id = p.trace_id"
                        + " AND s.id = p.span_id"
                        + " ORDER BY score DESC, s.trace_id ASC"
                        + " LIMIT :lim")
                .param("pid", projectId)
                .param("q", query)
                .param("lim", limit)
                .query((rs, n) -> spanHit(
                        rs.getString("trace_id"), rs.getString("name"), rs.getString("input"), rs.getDouble("score")))
                .list();
    }

    /**
     * Matched bare so the predicate is exactly the indexed expression. The payload is not joined, which would pull the
     * biggest table into a leg that does not read it.
     */
    private List<SearchHit> searchSpansByName(String projectId, String query, int limit) {
        return jdbc.sql("""
                        SELECT trace_id, name, input_preview,
                               word_similarity(:q, coalesce(name, '')) * 0.3 AS score
                        FROM span
                        WHERE project_id = :pid AND name %> :q
                        ORDER BY score DESC, trace_id ASC
                        LIMIT :lim
                        """)
                .param("pid", projectId)
                .param("q", query)
                .param("lim", limit)
                .query((rs, n) -> spanHit(
                        rs.getString("trace_id"),
                        rs.getString("name"),
                        rs.getString("input_preview"),
                        rs.getDouble("score")))
                .list();
    }

    private static SearchHit spanHit(String traceId, @Nullable String name, @Nullable String body, double score) {
        String title = name != null && !name.isBlank() ? name : "Trace " + traceId;
        return SearchHit.of(HitType.TRACE, traceId, title, clip(body), score);
    }

    private static @Nullable String clip(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.strip();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 117) + "…";
    }
}
