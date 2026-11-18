// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.promptlib.PromptService.CreateVia;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A version on the detail page's list: no body and no config, which only the version read carries. */
public record PromptVersionSummaryRow(
        String id,
        int version,
        @Nullable String commitMessage,
        String createdBy,
        CreateVia createdVia,
        Instant createdAt) {}
