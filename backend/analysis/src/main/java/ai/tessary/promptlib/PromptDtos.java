// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.promptlib.PromptService.CreateVia;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The UI wire shapes, snake_case. {@code body} is an {@code Object} because it is a string for a text prompt and an
 * array for a chat prompt; a config is a {@code Map}, never a Jackson 2 {@code JsonNode}, since the HTTP converter is
 * Jackson 3.
 */
public final class PromptDtos {

    private PromptDtos() {}

    public record LabelRef(String label, int version) {
        static List<LabelRef> of(List<PromptLabelRow> rows) {
            return rows.stream().map(l -> new LabelRef(l.label(), l.version())).toList();
        }
    }

    public record PromptSummary(
            String id,
            String name,
            PromptType type,
            List<String> tags,
            @JsonProperty("latest_version") int latestVersion,
            @JsonProperty("version_count") int versionCount,
            List<LabelRef> labels,
            @JsonProperty("updated_at") String updatedAt) {}

    public record PromptPage(List<PromptSummary> items, int page, int limit, long total) {
        public static PromptPage from(PromptService.PromptPage page) {
            List<PromptSummary> items = page.items().stream()
                    .map(i -> new PromptSummary(
                            i.prompt().id(),
                            i.prompt().name(),
                            i.prompt().type(),
                            i.prompt().tags(),
                            i.latestVersion(),
                            i.versionCount(),
                            LabelRef.of(page.labelsByPromptId()
                                    .getOrDefault(i.prompt().id(), List.of())),
                            i.prompt().updatedAt().toString()))
                    .toList();
            return new PromptPage(items, page.page(), page.limit(), page.total());
        }
    }

    /** {@code labels} includes {@code latest} on the highest version. */
    public record VersionSummary(
            String id,
            int version,
            List<String> labels,
            @JsonProperty("commit_message") @Nullable String commitMessage,
            @JsonProperty("created_by") String createdBy,
            @JsonProperty("created_by_label") String createdByLabel,
            @JsonProperty("created_via") CreateVia createdVia,
            @JsonProperty("created_at") String createdAt) {}

    /** {@code labels} holds stored labels only; {@code latest} shows as {@code latest_version}. */
    public record PromptDetail(
            String id,
            String name,
            PromptType type,
            List<String> tags,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("latest_version") int latestVersion,
            List<LabelRef> labels,
            List<VersionSummary> versions) {
        public static PromptDetail from(PromptService.PromptDetail d, PromptService.Authors authors) {
            int latest = d.versions().isEmpty() ? 0 : d.versions().getFirst().version();
            List<VersionSummary> versions = d.versions().stream()
                    .map(v -> {
                        List<String> labels = new ArrayList<>();
                        for (PromptLabelRow l : d.labels()) {
                            if (l.versionId().equals(v.id())) labels.add(l.label());
                        }
                        if (v.version() == latest) labels.add(PromptNames.LATEST);
                        return new VersionSummary(
                                v.id(),
                                v.version(),
                                List.copyOf(labels),
                                v.commitMessage(),
                                v.createdBy(),
                                authors.of(v.createdBy()),
                                v.createdVia(),
                                v.createdAt().toString());
                    })
                    .toList();
            PromptRow p = d.prompt();
            return new PromptDetail(
                    p.id(),
                    p.name(),
                    p.type(),
                    p.tags(),
                    p.createdAt().toString(),
                    p.updatedAt().toString(),
                    latest,
                    LabelRef.of(d.labels()),
                    versions);
        }
    }

    public record PromptVersionView(
            String id,
            @JsonProperty("prompt_id") String promptId,
            String name,
            PromptType type,
            int version,
            Object body,
            Map<String, Object> config,
            List<String> labels,
            @JsonProperty("commit_message") @Nullable String commitMessage,
            @JsonProperty("created_by") String createdBy,
            @JsonProperty("created_by_label") String createdByLabel,
            @JsonProperty("created_via") CreateVia createdVia,
            @JsonProperty("created_at") String createdAt) {
        public static PromptVersionView from(PromptService.ResolvedVersion v, PromptService.Authors authors) {
            PromptVersionRow row = v.version();
            return new PromptVersionView(
                    row.id(),
                    row.promptId(),
                    v.prompt().name(),
                    v.prompt().type(),
                    row.version(),
                    v.body().toWire(),
                    v.config(),
                    v.labels(),
                    row.commitMessage(),
                    row.createdBy(),
                    authors.of(row.createdBy()),
                    row.createdVia(),
                    row.createdAt().toString());
        }
    }

    public record DeleteResponse(boolean deleted) {}

    /** A distinct name: every {@code DeleteResponse(boolean)} record shares one springdoc schema. */
    public record FolderDeleteResponse(
            @JsonProperty("deleted_count") int deletedCount) {}

    public record CreatePromptRequest(
            @NotBlank String name,
            @NotNull PromptType type,
            @NotNull Object body,
            @Nullable Map<String, Object> config,
            @Nullable @Size(max = 50) List<@NotBlank String> labels,
            @Nullable @Size(max = 50) List<@NotBlank String> tags,

            @JsonProperty("commit_message") @Nullable @Size(max = 500)
            String commitMessage) {}

    public record CreateVersionRequest(
            @NotNull Object body,
            @Nullable Map<String, Object> config,
            @Nullable @Size(max = 50) List<@NotBlank String> labels,

            @JsonProperty("commit_message") @Nullable @Size(max = 500)
            String commitMessage) {}

    /** Replace semantics: the version ends with exactly these labels. */
    public record SetLabelsRequest(@NotNull @Size(max = 50) List<@NotBlank String> labels) {}

    public record UpdateTagsRequest(@NotNull @Size(max = 50) List<@NotBlank String> tags) {}
}
