// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One fetch read in one statement: the prompt, the selected version ({@code null} when the selector matches none),
 * that version's stored labels sorted, and the highest version number the prompt has.
 */
public record PromptResolveRow(
        PromptRow prompt, @Nullable PromptVersionRow version, List<String> labels, int maxVersion) {}
