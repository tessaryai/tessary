// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The {@code /v1/prompts} wire shapes, in the camelCase field names of a common prompt API shape, so a client written
 * against that shape reads them unchanged. Record names that would collide with {@link PromptDtos} in the shared springdoc schema carry a
 * {@code Public} prefix.
 */
public final class PromptPublicDtos {

    private PromptPublicDtos() {}

    /** Without {@code type}, a new prompt is text; a later version must match the stored type. */
    public record PublicCreatePromptRequest(
            @NotBlank String name,
            @Nullable PromptType type,
            @NotNull Object prompt,
            @Nullable Map<String, Object> config,
            @Nullable @Size(max = 50) List<@NotBlank String> labels,
            @Nullable @Size(max = 50) List<@NotBlank String> tags,
            @Nullable @Size(max = 500) String commitMessage) {}

    /** Add semantics: the listed labels move to the version, its other labels stay. */
    public record UpdateLabelsRequest(
            @NotBlank String name,
            @NotNull Integer version,
            @NotNull @Size(max = 50) List<@NotBlank String> newLabels) {}

    /** {@code labels} includes {@code latest} on the highest version; {@code isActive} is "carries production". */
    public record PublicPrompt(
            String id,
            String name,
            int version,
            PromptType type,
            Object prompt,
            Map<String, Object> config,
            List<String> labels,
            List<String> tags,
            @Nullable String commitMessage,
            String createdAt,
            String updatedAt,
            @JsonProperty("isActive") boolean isActive) {
        public static PublicPrompt from(PromptService.ResolvedVersion v) {
            PromptVersionRow row = v.version();
            return new PublicPrompt(
                    row.id(),
                    v.prompt().name(),
                    row.version(),
                    v.prompt().type(),
                    v.body().toWire(),
                    v.config(),
                    v.labels(),
                    v.prompt().tags(),
                    row.commitMessage(),
                    row.createdAt().toString(),
                    v.prompt().updatedAt().toString(),
                    v.labels().contains(PromptNames.PRODUCTION));
        }
    }

    /** {@code versions} ascending; {@code labels} distinct and sorted, with {@code latest} appended. */
    public record PromptMeta(
            String name,
            PromptType type,
            List<Integer> versions,
            List<String> labels,
            List<String> tags,
            String lastUpdatedAt,
            Map<String, Object> lastConfig) {}

    public record ListMeta(int page, int limit, long totalItems, long totalPages) {}

    public record PromptList(List<PromptMeta> data, ListMeta meta) {
        public static PromptList from(PromptService.PromptPage page) {
            List<PromptMeta> data = page.items().stream()
                    .map(i -> {
                        String id = i.prompt().id();
                        TreeSet<String> stored = new TreeSet<>();
                        for (PromptLabelRow l : page.labelsByPromptId().getOrDefault(id, List.of())) {
                            stored.add(l.label());
                        }
                        List<String> labels = new ArrayList<>(stored);
                        labels.add(PromptNames.LATEST);
                        return new PromptMeta(
                                i.prompt().name(),
                                i.prompt().type(),
                                i.versions(),
                                List.copyOf(labels),
                                i.prompt().tags(),
                                i.prompt().updatedAt().toString(),
                                page.lastConfigByPromptId().getOrDefault(id, Map.of()));
                    })
                    .toList();
            long totalPages = page.total() == 0 ? 0 : (page.total() + page.limit() - 1) / page.limit();
            return new PromptList(data, new ListMeta(page.page(), page.limit(), page.total(), totalPages));
        }
    }

    public record DeleteResponse(boolean deleted) {}
}
