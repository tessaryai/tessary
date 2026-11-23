// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import java.util.List;

/** A case's pull request as Tessary last read it. {@code causes} are the keys of the causes it addressed so far. */
public record FixPrRow(
        String id,
        String projectId,
        String caseId,
        String branch,
        int prNumber,
        String prUrl,
        String state,
        String headSha,
        List<String> causes,
        String openedAt,
        String updatedAt) {

    public FixPrRow {
        causes = List.copyOf(causes);
    }

    public static final class State {
        private State() {}

        public static final String OPEN = "open";
        public static final String MERGED = "merged";
        /** Closed without a merge: the reviewer said no to its causes. */
        public static final String CLOSED = "closed";
    }
}
