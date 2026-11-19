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
 * The {@code /v1/prompts} wire shapes. Field names are snake_case on the wire, as on Tessary's other APIs. Record names that would collide with {@link PromptDtos} in the shared springdoc schema carry a
 * {@code Public} prefix.
 */
public final class PromptPublicDtos {

    private PromptPublicDtos() {}

    /** Without {@code type}, a new prompt is text; a later version must match the stored type. */
    public record PublicCreatePromptRequest(
            @NotBlank String name,
            @Nullable PromptType type,
            @NotNull Object body,
            @Nullable Map<String, Object> config,
            @Nullable @Size(max = 50) List<@NotBlank String> labels,
            @Nullable @Size(max = 50) List<@NotBlank String> tags,

            @Nullable @Size(max = 500) @JsonProperty("commit_message")
            String commitMessage) {}

    /**
     * {@code new_labels} move to the version and its other labels stay; then each of {@code remove_labels} that is on
     * this version comes off. A label in both lists is refused.
     */
    public record UpdateLabelsRequest(
            @NotBlank String name,
            @NotNull Integer version,

            @Nullable @Size(max = 50) @JsonProperty("new_labels")
            List<@NotBlank String> newLabels,

            @Nullable @Size(max = 50) @JsonProperty("remove_labels")
            List<@NotBlank String> removeLabels) {}

    /** One version, in the Prompts page's field names; {@code labels} includes {@code latest} on the highest version. */
    public record PublicPrompt(
            String id,
            String name,
            int version,
            PromptType type,
            Object body,
            Map<String, Object> config,
            List<String> labels,
            List<String> tags,
            @JsonProperty("commit_message") @Nullable String commitMessage,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {
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
                    v.prompt().updatedAt().toString());
        }
    }

    /**
     * {@code versions} ascending; {@code labels} distinct and sorted, with {@code latest} appended on the highest
     * version; {@code latest_config} is the newest version's config.
     */
    public record PromptMeta(
            String name,
            PromptType type,
            List<Integer> versions,
            List<String> labels,
            List<String> tags,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("latest_config") Map<String, Object> latestConfig) {}

    /** One page, in the shape of the Prompts page's list: {@code total} counts every matching prompt. */
    public record PromptList(List<PromptMeta> items, int page, int limit, long total) {
        public static PromptList from(PromptService.PromptPage page) {
            List<PromptMeta> items = page.items().stream()
                    .map(i -> {
                        String id = i.prompt().id();
                        TreeSet<String> stored = new TreeSet<>();
                        for (PromptLabelRow l : page.labelsByPromptId().getOrDefault(id, List.of())) {
                            stored.add(l.label());
                        }
                        List<String> labels = new ArrayList<>(stored);
                        if (i.versions().contains(i.highestVersion())) labels.add(PromptNames.LATEST);
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
            return new PromptList(items, page.page(), page.limit(), page.total());
        }
    }

    public record DeleteResponse(boolean deleted) {}
}
