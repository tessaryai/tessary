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
 * Global search: one tenant-scoped lexical query per entity, merged and re-ranked for the palette. Tenant scoping lives
 * in the legs, each filtering on the already-authorized {@code projectId}. Reciprocal-rank fusion over one list is just
 * a stable dedup-and-resort.
 */
@Service
public class GlobalSearchService {

    static final int PER_ENTITY_LIMIT = 10;

    static final int RESULT_CAP = 20;

    /**
     * The standard 60 keeps each list's top handful dominant while letting agreement across lists outrank a single-list
     * top hit.
     */
    static final int RRF_K = 60;

    private final GlobalSearchRepository repo;

    public GlobalSearchService(GlobalSearchRepository repo) {
        this.repo = repo;
    }

    /** A blank query returns nothing without scanning. */
    public List<SearchHit> search(String projectId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.strip();

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
