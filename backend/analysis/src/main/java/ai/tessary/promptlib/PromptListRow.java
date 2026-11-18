// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import java.util.List;

/**
 * A prompt on a list page: its listed version numbers ascending, the newest listed version's config as stored, and the
 * prompt's highest version number. Without a JSON filter every version is listed.
 */
public record PromptListRow(PromptRow prompt, List<Integer> versions, String lastConfigJson, int highestVersion) {

    /** 0 only for a prompt with no version, which a committed create never leaves. */
    public int latestVersion() {
        return highestVersion;
    }

    public int versionCount() {
        return versions.size();
    }
}
