// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

/**
 * The shared vocabulary of the unified {@code job} table: its {@code kind} discriminator and the
 * queue statuses the leased kinds share. Each per-kind repository maps its own rows; {@link
 * LeasedJobSql} supplies the SKIP-LOCKED claim / dead-letter SQL they run.
 */
public final class JobRow {
    private JobRow() {}

    /** The queue-status vocabulary the leased kinds share. */
    public static final class Status {
        private Status() {}

        public static final String PENDING = "pending";
        public static final String CLAIMED = "claimed";
        public static final String DONE = "done";
    }

    /** The {@code job.kind} discriminator values. */
    public static final class Kind {
        private Kind() {}

        /** The classifier sweep job. */
        public static final String CLASSIFIER = "classifier";

        public static final String RCA = "rca";

        /**
         * Purge one deleted project's data in bounded batches. The only kind whose rows carry a NULL
         * {@code project_id}: this column is {@code ON DELETE CASCADE} to {@code project}, so a delete
         * job that named its own project would erase itself the moment the cascade landed, leaving no
         * row to mark done. The project id lives in {@code payload.project_id} and {@code dedupe_key}.
         */
        public static final String PROJECT_DELETE = "project_delete";
    }
}
