// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

/** Shared vocabulary of the unified {@code job} table. {@link LeasedJobSql} supplies the claim and dead-letter SQL. */
public final class JobRow {
    private JobRow() {}

    public static final class Status {
        private Status() {}

        public static final String PENDING = "pending";
        public static final String CLAIMED = "claimed";
        public static final String DONE = "done";
    }

    public static final class Kind {
        private Kind() {}

        public static final String CLASSIFIER = "classifier";

        public static final String RCA = "rca";

        /**
         * The only kind with a NULL {@code project_id}: that column cascades on project delete, so a job naming its own
         * project would erase itself. The id lives in {@code payload.project_id} and {@code dedupe_key}.
         */
        public static final String PROJECT_DELETE = "project_delete";
    }
}
