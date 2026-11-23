// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.SessionEvent.PatchFile;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a fix goes once the agent wrote it. A pull request on the project's repository is the only destination today;
 * the seam is here so another (an issue tracker) can take the same fix run.
 */
public interface FixDestination {

    /** Null when this destination can take a fix for the project; else the {@link FixRunRow.Reason} it cannot. */
    @Nullable
    String blocked(String projectId);

    /** Reads where the case's earlier fixes stand and decides where this run starts, or why it stops. */
    Start start(String projectId, String caseId, List<FixableCause> causes);

    /** Writes the change. Throws when the destination refuses it. */
    Delivery deliver(Start.Go go, Change change);

    sealed interface Start {

        /** No agent runs: the run is done with this outcome. @param fixPrId the pull request the run is about */
        record Stop(
                String outcome,
                @Nullable String reason,
                @Nullable String fixPrId) implements Start {}

        /**
         * @param repo the clone the agent starts from @param branch the branch the clone starts at @param causes the causes this run fixes @param openPr the
         * case's open pull request this run adds a commit to; null opens a new one
         */
        record Go(
                String projectId,
                String caseId,
                AgentSessionClient.Clone repo,
                String branch,
                List<FixableCause> causes,
                @Nullable FixPrRow openPr)
                implements Start {}
    }

    /**
     * @param baseSha the commit the agent's clone started from, which the change is built on @param causes the keys
     * the change fixes
     */
    record Change(
            List<PatchFile> files,
            String baseSha,
            List<String> causes,
            String title,
            String body,
            String commitMessage) {}

    sealed interface Delivery {

        /** @param outcome {@code pr_opened} or {@code commit_added} */
        record Delivered(String outcome, String fixPrId, int prNumber, String url) implements Delivery {}

        /** The branch moved under the run onto commits that touch the change's files: rerun the agent on it. */
        record Moved() implements Delivery {}
    }
}
