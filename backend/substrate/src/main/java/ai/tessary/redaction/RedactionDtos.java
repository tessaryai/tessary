// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class RedactionDtos {

    private RedactionDtos() {}

    public record RuleView(
            String id,
            String name,
            String pattern,
            String replacement,
            boolean enabled,
            @JsonProperty("built_in") boolean builtIn,
            @JsonProperty("sort_order") int sortOrder,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {

        public static RuleView of(RedactionRuleRow r) {
            return new RuleView(
                    r.id(),
                    r.name(),
                    r.pattern(),
                    r.replacement(),
                    r.enabled(),
                    r.builtIn(),
                    r.sortOrder(),
                    r.createdAt(),
                    r.updatedAt());
        }
    }

    public record RuleListView(List<RuleView> rules) {}

    public record UpsertRuleRequest(
            @NotBlank String name,
            @NotBlank String pattern,
            @NotBlank String replacement,
            @Nullable Boolean enabled,
            @JsonProperty("sort_order") @Nullable Integer sortOrder) {

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public int sortOrderOrDefault() {
            return sortOrder == null ? 100 : sortOrder;
        }
    }

    /** The only mutation permitted on a built-in rule. */
    public record SetEnabledRequest(@NotNull Boolean enabled) {}

    /** A present {@code pattern} previews that single, possibly unsaved rule; blank previews the whole active set. */
    public record PreviewRequest(
            @Nullable String pattern,
            @Nullable String replacement,
            @JsonProperty("sample_text") @NotNull String sampleText) {}

    public record PreviewView(String original, String redacted, int matches) {
        public static PreviewView of(RedactionService.PreviewResult r) {
            return new PreviewView(r.original(), r.redacted(), r.matches());
        }
    }
}
