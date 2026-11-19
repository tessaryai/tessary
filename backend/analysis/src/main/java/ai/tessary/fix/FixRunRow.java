// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One fix step after one RCA report. {@code causes} are the keys of the causes it was given; {@code note} is the agent's
 * own sentence, what its change does or why it made none.
 */
public record FixRunRow(
        String id,
        String projectId,
        @Nullable String fixPrId,
        String caseId,
        String rcaReportId,
        String status,
        @Nullable String outcome,
        @Nullable String reason,
        @Nullable String note,
        List<String> causes,
        List<FixCheck> checks,
        int patchFiles,
        String createdAt,
        String updatedAt) {

    public FixRunRow {
        causes = List.copyOf(causes);
        checks = List.copyOf(checks);
    }

    public static final class Status {
        private Status() {}

        public static final String QUEUED = "queued";
        public static final String RUNNING = "running";
        public static final String DONE = "done";
        public static final String FAILED = "failed";
    }

    public static final class Outcome {
        private Outcome() {}

        public static final String PR_OPENED = "pr_opened";
        public static final String COMMIT_ADDED = "commit_added";
        /** The open pull request already covers every fixable cause of the report. */
        public static final String NO_CHANGE = "no_change";
        /** The agent ran and made no change Tessary could deliver; {@code reason} says why. */
        public static final String NO_FIX = "no_fix";
        /** A gate was shut, so no agent ran; {@code reason} names the gate. */
        public static final String SKIPPED = "skipped";
    }

    /** Stable codes the case page maps to its own words. */
    public static final class Reason {
        private Reason() {}

        public static final String NO_REPO = "no_repo";
        public static final String READ_ONLY = "read_only";
        public static final String NO_FRONTIER_MODEL = "no_frontier_model";
        public static final String NO_FIXABLE_CAUSE = "no_fixable_cause";
        /** Every fixable cause was in a pull request a reviewer closed without merging. */
        public static final String DECLINED = "declined";
        /** The agent said it could not write a safe fix. */
        public static final String AGENT_DECLINED = "agent_declined";
        /** A check the repository names failed on the change. */
        public static final String CHECK_FAILED = "check_failed";
        /** The agent answered fixed but changed no file Tessary may write. */
        public static final String EMPTY_PATCH = "empty_patch";

        public static final String PATCH_OVER_LIMIT = "patch_over_limit";
        /** The patch named a path Tessary never writes, such as one under {@code .git}. */
        public static final String PATCH_REFUSED = "patch_refused";
        /** The worker stopped mid-run, as on a restart. */
        public static final String INTERRUPTED = "interrupted";
    }

    /**
     * One check the agent ran, as it reported it.
     *
     * @param result {@code passed}, {@code failed} or {@code not_run}
     */
    public record FixCheck(
            String command, String result, @Nullable String note) {
        public static final String PASSED = "passed";
        public static final String FAILED = "failed";
        public static final String NOT_RUN = "not_run";
    }
}
