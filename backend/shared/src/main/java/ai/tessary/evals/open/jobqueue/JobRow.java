// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.jobqueue;

import org.jspecify.annotations.Nullable;

/**
 * One row of the unified {@code job} table — the raw, kind-agnostic shape the shared queue store
 * ({@code JobRepository}) reads and writes. A concrete per-kind queue ({@code classifier}/{@code pull}/…)
 * implements {@link LeasedJobQueue} by mapping this row to/from its domain job
 * via {@link #payload} / {@link #progress}.
 *
 * <p>Lease + timestamp columns are ISO-8601 {@code TEXT} (lexicographically comparable) so
 * {@link LeasedJobSql}'s SKIP-LOCKED claim / dead-letter SQL applies verbatim.
 *
 * @param kind the queue discriminator
 *     ({@code observer|signal|synth|pull|usage_rollup|grader_run|dataset_run|rca}).
 * @param dedupeKey the per-kind natural key driving coalescing / revival (nullable).
 * @param cursorAt the high-water mark a sweep-style kind resumes from (nullable).
 * @param progress a kind-specific counter blob as JSON text (nullable).
 * @param payload the kind's per-job fields as JSON text (defaults to {@code {}}).
 */
public record JobRow(
        String id,
        @Nullable String projectId,
        String kind,
        String status,
        @Nullable String leaseOwner,
        @Nullable String leaseExpiresAt,
        int attempts,
        @Nullable String lastError,
        @Nullable String dedupeKey,
        @Nullable String cursorAt,
        @Nullable String cursorId,
        @Nullable String progress,
        String payload,
        String createdAt,
        String updatedAt) {

    /** The queue-status vocabulary the leased kinds share (pull / usage_rollup carry their own). */
    public static final class Status {
        private Status() {}

        public static final String PENDING = "pending";
        public static final String CLAIMED = "claimed";
        public static final String DONE = "done";
        public static final String FAILED = "failed";
    }

    /** The {@code job.kind} discriminator values. */
    public static final class Kind {
        private Kind() {}

        public static final String OBSERVER = "observer";
        /** The classifier sweep job. */
        public static final String CLASSIFIER = "classifier";

        public static final String SYNTH = "synth";
        public static final String PULL = "pull";
        public static final String USAGE_ROLLUP = "usage_rollup";
        public static final String GRADER_RUN = "grader_run";
        public static final String DATASET_RUN = "dataset_run";
        public static final String RCA = "rca";
        // Compile one stored SOP document (verbatim .tessary/sops/ intake) into its serving form.
        public static final String SOP_COMPILE = "sop_compile";

        /**
         * Purge one deleted project's data in bounded batches. The only kind whose rows carry a NULL
         * {@code project_id}: this column is {@code ON DELETE CASCADE} to {@code project}, so a delete
         * job that named its own project would erase itself the moment the cascade landed, leaving no
         * row to mark done. The project id lives in {@code payload.project_id} and {@code dedupe_key}.
         */
        public static final String PROJECT_DELETE = "project_delete";
    }
}
