// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import java.util.List;

/** A prompt on a list page: its version numbers ascending and the newest version's config as stored. */
public record PromptListRow(PromptRow prompt, List<Integer> versions, String lastConfigJson) {

    /** 0 only for a prompt with no version, which a committed create never leaves. */
    public int latestVersion() {
        return versions.isEmpty() ? 0 : versions.getLast();
    }

    public int versionCount() {
        return versions.size();
    }
}
