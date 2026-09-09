// SPDX-License-Identifier: Apache-2.0
package ai.tessary.search;

import ai.tessary.search.GlobalSearchDtos.SearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The global-search read service: fan out one tenant-scoped query per content entity
 * ({@link GlobalSearchRepository}) — graders, datasets, traces (substrate observations) — then merge and
 * re-rank the small per-leg result sets into one best-first list for the ⌘K palette. A fixed fan-out (not
 * per-row) — latency is bounded by {@link #PER_ENTITY_LIMIT} per leg and {@link #RESULT_CAP} overall.
 *
 * <p>Tenant scoping is the load-bearing property and lives entirely in the read legs: every lexical leg
 * filters {@code WHERE project_id = :pid} against the same
 * {@code projectId} the controller resolved via {@link ai.tessary.auth.TenantPathResolver#requireProject}.
 * This service never sees an org/project slug — only the already-authorized id.
 *
 * <p><b>Lexical-only, deduplicated and re-ranked.</b> The lexical legs (graders, datasets, traces) each
 * rank by Postgres {@code ts_rank} (+ a small trigram term) and arrive best-first; concatenated they form
 * one ranked list that may name the same (type, id) more than once across legs. The merge still runs
 * through <em>reciprocal-rank fusion</em> (RRF) — each hit contributes {@code 1/(k + rank)}, summed across
 * occurrences — which for a single input list is exactly a stable dedup-and-resort by rank, not a scoring
 * change. This platform previously also blended in a semantic (vector) leg here by the same RRF merge;
 * that leg was removed with the rest of the embedding substrate (#1116), and lexical was always what the
 * merge degenerated to whenever the semantic leg had nothing to contribute, so this is that same output,
 * now the only path rather than the default one.
 */
@Service
public class GlobalSearchService {

    /** Per-entity ranked-scan cap — ample for the short name/identifier entities, bounds the entry scan. */
    static final int PER_ENTITY_LIMIT = 10;

    /** Overall cap on the merged list returned to the palette (keeps the payload small). */
    static final int RESULT_CAP = 20;

    /**
     * Reciprocal-rank-fusion damping constant: a hit at rank {@code r} (0-based) in a list contributes
     * {@code 1/(RRF_K + r)}. The standard 60 keeps the top handful of each list dominant while still letting
     * a strong agreement across lists outrank a single-list top hit.
     */
    static final int RRF_K = 60;

    private final GlobalSearchRepository repo;

    public GlobalSearchService(GlobalSearchRepository repo) {
        this.repo = repo;
    }

    /**
     * Ranked content matches for {@code query} within {@code projectId}, best-first across all entity types,
     * deduplicated and re-ranked by reciprocal-rank fusion over the lexical legs. A blank query
     * returns no hits (the palette only calls this on typed input); the fan-out is skipped so an empty
     * search never scans.
     */
    public List<SearchHit> search(String projectId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.strip();

        // Lexical legs already arrive best-first per leg; concatenated they form one lexical ranked list.
        List<SearchHit> lexical = new ArrayList<>(repo.searchLexical(projectId, q, PER_ENTITY_LIMIT));
        lexical.sort(Comparator.comparingDouble(SearchHit::score).reversed().thenComparing(SearchHit::title));

        List<SearchHit> fused = fuse(List.of(lexical));
        return fused.size() > RESULT_CAP ? List.copyOf(fused.subList(0, RESULT_CAP)) : List.copyOf(fused);
    }

    /**
     * Reciprocal-rank fusion of several already-ranked hit lists into one best-first list, de-duplicated by
     * (type, id). Each hit's fused score is {@code Σ 1/(RRF_K + rank)} over the lists it appears in; the
     * representative {@link SearchHit} carried into the result is the first occurrence (richest snippet from
     * whichever leg ranked it first), but its exposed {@code score} is replaced with the fused score so the
     * merge order stays auditable. Falls through to the single list's order when only one is non-empty.
     */
    private static List<SearchHit> fuse(List<List<SearchHit>> lists) {
        Map<String, SearchHit> repByKey = new LinkedHashMap<>();
        Map<String, Double> fusedScore = new LinkedHashMap<>();
        for (List<SearchHit> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                SearchHit hit = list.get(rank);
                String key = hit.type() + " " + hit.id();
                repByKey.putIfAbsent(key, hit);
                fusedScore.merge(key, 1.0 / (RRF_K + rank), Double::sum);
            }
        }
        List<SearchHit> merged = new ArrayList<>(repByKey.size());
        for (Map.Entry<String, SearchHit> e : repByKey.entrySet()) {
            SearchHit h = e.getValue();
            double score = fusedScore.getOrDefault(e.getKey(), h.score());
            merged.add(new SearchHit(h.type(), h.id(), h.title(), h.snippet(), score));
        }
        merged.sort(Comparator.comparingDouble(SearchHit::score).reversed().thenComparing(SearchHit::title));
        return merged;
    }
}
