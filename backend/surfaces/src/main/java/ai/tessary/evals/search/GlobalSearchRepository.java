// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.search;

import ai.tessary.evals.search.GlobalSearchDtos.HitType;
import ai.tessary.evals.search.GlobalSearchDtos.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-text search over a project's content entities for the global palette. One
 * {@link JdbcClient} query per entity type — graders, datasets, and traces (the substrate
 * {@code span} and its {@code span_payload}) —
 * each an <strong>independently tenant-scoped</strong> {@code WHERE project_id = :pid} read; the
 * per-entity results are merged and re-ranked in {@link GlobalSearchService}, never UNION-ed in SQL
 * (so each leg stays a self-contained, auditable tenant boundary and can be capped on its own).
 *
 * <p>Ranking uses Postgres-native FTS: an expression {@code to_tsvector(…)} matched against
 * {@code plainto_tsquery(…)} and scored by {@code ts_rank}. Every nullable text column is
 * wrapped in {@code coalesce(col, '')} so a null field never collapses the whole document's tsvector to
 * null (which would silently drop the row from search). The matching {@code to_tsvector} GIN indexes
 * keep these predicates index-assisted as content grows — most importantly on
 * {@code span_payload}, which holds the bytes.
 *
 * <p><b>The span leg's tsvector expression is copied character for character from migration 0077, and
 * must stay that way.</b> Postgres matches an expression index only against a syntactically identical
 * expression. The index is
 * {@code to_tsvector('simple', left(coalesce(input,''),100000) || ' ' || left(coalesce(output,''),100000))};
 * writing {@code 'english'} instead of {@code 'simple'}, or {@code left(…, 100_000)} spelled any other
 * way, or dropping the cap, all still return correct rows — by sequentially scanning every payload in the
 * project. That is not a slow query, it is the query this whole migration exists to delete, reintroduced
 * silently. Two consequences ride along and are deliberate: the cap means search sees the first ~100KB of
 * each of input and output, and the {@code 'simple'} configuration means no English stemming on payload
 * text (a search for {@code refunds} does not match {@code refunded}). Both are the index's terms, not
 * this class's choices, and the trigram leg on {@code span.name} covers most of what stemming would have.
 *
 * <p>On top of exact FTS, each leg adds a <strong>trigram fallback</strong> so the palette
 * tolerates typos and short prefixes as you type. The trigram leg uses pg_trgm's <strong>word-similarity
 * operator</strong> {@code name %> :q} as the row filter — the index-eligible commutator of
 * {@code :q <% name} (so the indexed column {@code name} is the left operand), semantically equal to the
 * {@code word_similarity(:q, name) >= threshold} predicate expressed as a bare function call. The
 * operator form is what the {@code gin_trgm_ops} GIN indexes actually back, so the planner
 * can drive the trigram leg with a Bitmap Index Scan on {@code ix_*_name_trgm} and {@code BitmapOr} it with
 * the FTS leg rather than falling back to a per-entity sequential scan. The column is matched bare (not
 * {@code coalesce}-wrapped) so the predicate matches the functional index's indexed expression exactly — a
 * null {@code name} simply never matches, which is correct. {@code word_similarity(:q, name)} is retained
 * purely as the <em>ranking</em> term in the score, not as the filter.
 *
 * <p>The {@code %>} operator's selectivity is governed by the {@code pg_trgm.word_similarity_threshold}
 * session GUC (default 0.6). To keep the deliberately-lower {@value #TRIGRAM_THRESHOLD} threshold (so
 * genuine short prefixes / mild misspellings still surface) self-contained — identical in dev, prod and
 * test, without a wide-blast-radius {@code connectionInitSql} GUC in the shared {@code DataSourceConfig} —
 * the fan-out runs inside one transaction that issues {@code set_config(…, is_local => true)} once up
 * front, so the lowered threshold is scoped to exactly this query and reset at commit.
 *
 * <p>Relevance is the composite {@code ts_rank(...) + word_similarity(:q, name) * }{@value #TRIGRAM_WEIGHT}:
 * an exact FTS hit always outranks a pure-trigram one, since the trigram term is capped at
 * {@value #TRIGRAM_WEIGHT} while a genuine full-text match contributes a positive {@code ts_rank} on top of
 * its own (typically high) word similarity.
 *
 * <p>This is a pure READ surface — it never writes to any store.
 */
@Repository
public class GlobalSearchRepository {

    /**
     * Weight applied to the {@code word_similarity(:q, name)} term (range 0..1) when blending it into the
     * composite relevance score. Kept &lt; the floor of a real {@code ts_rank} match so an exact full-text hit
     * always sorts above a pure-trigram (typo/prefix) hit, while still ordering trigram-only hits among
     * themselves by how close the name is.
     */
    static final double TRIGRAM_WEIGHT = 0.3;

    /**
     * Minimum {@code word_similarity(:q, name)} for a row to qualify as a trigram (typo/prefix) match.
     * Lower than pg_trgm's default {@code word_similarity_threshold} (0.6) so genuine short prefixes and mild
     * misspellings still surface (e.g. {@code refn}/{@code refnud} → "Refund tone grader"), while unrelated
     * terms (different words) stay below it and are excluded. Applied as the {@code %>} operator's transaction-
     * local GUC (see class doc), not as a bound predicate, so the operator engages the {@code gin_trgm_ops}
     * index.
     */
    static final double TRIGRAM_THRESHOLD = 0.4;

    private final JdbcClient jdbc;

    public GlobalSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Fan out the per-entity lexical (FTS) + trigram legs for {@code query} within {@code projectId}, each
     * capped at {@code limit}, and return the concatenated hits (still per-leg, un-merged — the merge and
     * overall re-rank are {@link GlobalSearchService}'s job).
     *
     * <p>Wrapped in a single transaction so the {@code %>} word-similarity threshold can be lowered
     * transaction-locally for the whole fan-out (see class doc): every leg sees {@value #TRIGRAM_THRESHOLD}
     * and the session default is restored at commit. Read-only — no row is ever written.
     */
    @Transactional(readOnly = true)
    public List<SearchHit> searchLexical(String projectId, String query, int limit) {
        // Lower pg_trgm's word-similarity threshold for THIS transaction only (is_local => true), so the
        // index-backed `<%` operator matches at the lowered tolerance without mutating any shared/session GUC.
        jdbc.sql("SELECT set_config('pg_trgm.word_similarity_threshold', :thr, true)")
                .param("thr", Double.toString(TRIGRAM_THRESHOLD))
                .query()
                .singleColumn();
        // Two legs left with Track A: graders and datasets were both searched here, and both tables
        // are gone. The span leg is the whole lexical fan-out now — which is also why the transaction
        // and the lowered trigram threshold above still earn their keep for exactly one caller.
        List<SearchHit> hits = new ArrayList<>();
        hits.addAll(searchSpans(projectId, query, limit));
        return hits;
    }

    /**
     * The exact indexed expression from migration 0077's {@code ix_span_payload_fts}. Any divergence —
     * a different text-search config, a differently-spelled cap, an added column — silently drops the
     * index and sequentially scans every payload in the project. Kept as one constant so the two places
     * that need it (the filter and the rank) cannot drift from each other either.
     */
    private static final String PAYLOAD_TSVECTOR = "to_tsvector('simple', "
            + "left(coalesce(p.input, ''), 100000) || ' ' || left(coalesce(p.output, ''), 100000))";

    /**
     * Trace matches over the substrate: the payload full-text leg plus the span-name trigram leg, scoped
     * to {@code projectId}. Both {@code span} and {@code span_payload} carry their own {@code project_id},
     * so the tenant boundary is a direct filter on each — no join upward.
     *
     * <p><b>Two statements, not one OR.</b> The full-text predicate is served by
     * {@code ix_span_payload_fts} on {@code span_payload} and the trigram predicate by
     * {@code ix_span_name_trgm} on {@code span}. OR-ing them across the join gives the planner a choice
     * between two indexes on two different tables and it resolves that by scanning; issued separately,
     * each leg is driven by its own index and the results merge here.
     *
     * <p><b>The hit is the TRACE.</b> A palette hit routes to {@code traces/<id>}, and in v2 that path
     * takes the producer trace id — so a span match surfaces the trace it belongs to, deduplicated to the
     * best-scoring span. (v1 returned the observation id into the same route, which was already the wrong
     * key for it.) The title is the matched span's name, and the snippet a clipped excerpt of its input.
     */
    List<SearchHit> searchSpans(String projectId, String query, int limit) {
        // Best-scoring span per trace, in first-seen order: the FTS leg runs first, so a trace matched on
        // payload text keeps its payload-derived snippet even when its name also matches on trigrams.
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

    /** The full-text leg: {@code span_payload} matched through {@code ix_span_payload_fts}. */
    private List<SearchHit> searchSpansByPayloadText(String projectId, String query, int limit) {
        return jdbc.sql("SELECT s.trace_id, s.name, p.input,"
                        + " ts_rank(" + PAYLOAD_TSVECTOR + ", plainto_tsquery('simple', :q))"
                        + " + word_similarity(:q, coalesce(s.name, '')) * 0.3 AS score"
                        + " FROM span_payload p"
                        + " JOIN span s ON s.project_id = p.project_id AND s.trace_id = p.trace_id"
                        + " AND s.id = p.span_id"
                        + " WHERE p.project_id = :pid"
                        + " AND " + PAYLOAD_TSVECTOR + " @@ plainto_tsquery('simple', :q)"
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
     * The trigram leg: {@code span.name} matched through {@code ix_span_name_trgm}. The column is matched
     * bare, not {@code coalesce}-wrapped, so the predicate is exactly the indexed expression; a null name
     * simply never matches, which is correct. The payload is not joined — a name match needs no snippet
     * from it, and joining would pull the biggest table in the schema into a leg that does not read it.
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

    /** One span match rendered as the navigable TRACE hit the palette routes on. */
    private static SearchHit spanHit(String traceId, @Nullable String name, @Nullable String body, double score) {
        String title = name != null && !name.isBlank() ? name : "Trace " + traceId;
        return SearchHit.of(HitType.TRACE, traceId, title, clip(body), score);
    }

    /** Clip a body excerpt to a palette-friendly length, or {@code null} when there is nothing to show. */
    private static @Nullable String clip(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.strip();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 117) + "…";
    }
}
