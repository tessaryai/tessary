// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.redaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wire DTOs for the PII redaction feature. Snake_case on the wire, camelCase in Java. */
public final class RedactionDtos {

    private RedactionDtos() {}

    /** A redaction rule as returned to the playground. */
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

    /** Create/update a custom rule. */
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

    /** Toggle a rule's enabled flag (the only mutation permitted on a built-in rule). */
    public record SetEnabledRequest(@NotNull Boolean enabled) {}

    /**
     * Playground preview request. When {@code pattern} is present, previews that single (possibly unsaved)
     * rule; when it is blank, previews the whole active rule set for the project.
     */
    public record PreviewRequest(
            @Nullable String pattern,
            @Nullable String replacement,
            @JsonProperty("sample_text") @NotNull String sampleText) {}

    /** Playground preview result: the original + redacted text and how many matches were redacted. */
    public record PreviewView(String original, String redacted, int matches) {
        public static PreviewView of(RedactionService.PreviewResult r) {
            return new PreviewView(r.original(), r.redacted(), r.matches());
        }
    }
}
