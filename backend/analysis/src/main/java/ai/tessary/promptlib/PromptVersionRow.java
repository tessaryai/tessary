// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.promptlib.PromptService.CreateVia;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One immutable {@code prompt_version} row; {@code bodyJson} and {@code configJson} are the jsonb text as stored. */
public record PromptVersionRow(
        String id,
        String projectId,
        String promptId,
        int version,
        String bodyJson,
        String configJson,
        @Nullable String commitMessage,
        String createdBy,
        CreateVia createdVia,
        Instant createdAt) {}
