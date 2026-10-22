// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import java.time.Instant;

/** One deployment label and the version it points at; {@code version} is joined from {@code prompt_version}. */
public record PromptLabelRow(
        String projectId,
        String promptId,
        String label,
        String versionId,
        int version,
        String updatedBy,
        Instant updatedAt) {}
