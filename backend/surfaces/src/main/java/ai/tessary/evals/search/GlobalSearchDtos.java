// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the UI-facing global search palette — the ⌘K command palette's
 * server-backed content search. Distinct from the token-scoped {@code /v1/query} search API: this is a
 * plain, org-membership-scoped full-text read over the project's content entities (graders, datasets,
 * traces), feeding the palette as an async command source.
 *
 * <p>Snake_case on the wire, camelCase in Java — the platform DTO convention.
 */
public final class GlobalSearchDtos {

    private GlobalSearchDtos() {}

    /**
     * The kind of entity a {@link SearchHit} points at. The {@code wire} value is what the frontend
     * maps to a detail route (see {@code shell/commands.tsx}); keeping it an explicit enum means adding
     * a searchable entity is a one-line change on both sides rather than a stringly-typed contract.
     */
    public enum HitType {
        // GRADER("grader") and DATASET("dataset") were here until Track A removed both stores. The enum
        // is kept at one constant rather than folded away: it is the wire contract the palette maps to a
        // detail route, and a second searchable entity is a one-line change on both sides.
        TRACE("trace");

        private final String wire;

        HitType(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * One ranked content match. {@code type} discriminates the entity (and the frontend route);
     * {@code id} is its primary key within the project; {@code title} is the display label; {@code snippet}
     * is an optional short body excerpt; {@code score} is the Postgres {@code ts_rank} relevance the
     * results were sorted by (best-first), exposed so the merge order is auditable.
     */
    public record SearchHit(
            @JsonProperty("type") String type,
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("snippet") @Nullable String snippet,
            @JsonProperty("score") double score) {

        public static SearchHit of(HitType type, String id, String title, @Nullable String snippet, double score) {
            return new SearchHit(type.wire(), id, title, snippet, score);
        }
    }

    /** Global-search result: the ranked hits across all entity types, best-first by {@code score}. */
    public record GlobalSearchView(@JsonProperty("hits") List<SearchHit> hits) {

        public static GlobalSearchView of(List<SearchHit> hits) {
            return new GlobalSearchView(hits);
        }
    }
}
