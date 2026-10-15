// SPDX-License-Identifier: Apache-2.0
package ai.tessary.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the ⌘K palette's content search: an org-membership-scoped read, distinct from the token-scoped
 * {@code /v1/query} search.
 */
public final class GlobalSearchDtos {

    private GlobalSearchDtos() {}

    /** The {@code wire} value maps to a frontend detail route ({@code shell/commands.tsx}). */
    public enum HitType {
        // One constant left, kept as an enum because it is the palette's wire contract.
        TRACE("trace");

        private final String wire;

        HitType(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** {@code score} is the {@code ts_rank} the results were sorted by, exposed so the merge order is auditable. */
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

    public record GlobalSearchView(@JsonProperty("hits") List<SearchHit> hits) {

        public static GlobalSearchView of(List<SearchHit> hits) {
            return new GlobalSearchView(hits);
        }
    }
}
