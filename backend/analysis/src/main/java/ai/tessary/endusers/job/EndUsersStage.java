// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

/**
 * One stage of an end-user job. A stage is safe to run again for the same project and day: the daily job stores the
 * name of the last stage done in {@code job.progress} and a retry starts at the stage after it.
 */
public interface EndUsersStage {

    /** The name {@code job.progress} stores; it must not change once released. */
    String name();

    /** Runs the stage for the job's closed UTC day and returns the rows it wrote or deleted. */
    long run(String projectId, int day);
}
