// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import java.time.Instant;
import java.util.List;

/**
 * One {@code prompt} row. {@code updatedAt} moves on every version, label and tag write. {@code lastVersion} is the
 * highest version number ever handed out; it only grows, so a deleted number is never reused.
 */
public record PromptRow(
        String id,
        String projectId,
        String name,
        PromptType type,
        List<String> tags,
        int lastVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
