// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

/**
 * The shared lifecycle vocabulary for leased background-job queue rows
 * (observer drift jobs, synth runs, …): a row is {@link #PENDING} until a worker
 * leases it ({@link #CLAIMED}), then terminates as {@link #DONE} or {@link #FAILED} — or, for a queue
 * that opts into a dead-letter cooldown (embedding, signal), parks past its attempt cap as {@link #DEAD}
 * instead of {@link #FAILED}, so routine re-enqueue can't resurrect it into an unbounded retry loop.
 *
 * <p>These are the persisted string values, single-sourced so the leased-job
 * queues that share this exact state machine cannot drift apart. A row type with a
 * genuinely different vocabulary (e.g. {@code ExperimentRow}'s {@code running} state)
 * deliberately does NOT reference this holder — only types whose lifecycle is
 * literally identical do.
 */
public final class JobStatus {

    private JobStatus() {}

    public static final String PENDING = "pending";
    public static final String CLAIMED = "claimed";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String DEAD = "dead";
}
