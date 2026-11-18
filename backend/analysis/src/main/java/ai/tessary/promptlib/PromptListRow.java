// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import java.util.List;

/**
 * A prompt on a list page: how many versions it lists, their numbers ascending when the query asked for them (empty
 * otherwise), the newest listed version's config as stored, and the prompt's highest version number. Without a JSON
 * filter every version is listed.
 */
public record PromptListRow(
        PromptRow prompt, List<Integer> versions, int versionCount, String lastConfigJson, int highestVersion) {

    /** 0 only for a prompt with no version, which a committed create never leaves. */
    public int latestVersion() {
        return highestVersion;
    }
}
