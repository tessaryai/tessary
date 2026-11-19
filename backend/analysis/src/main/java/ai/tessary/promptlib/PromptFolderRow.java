// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import java.time.Instant;

/** A folder one level below a path: its full path, the prompts anywhere under it, and the newest prompt's update. */
public record PromptFolderRow(String path, long promptCount, Instant updatedAt) {}
