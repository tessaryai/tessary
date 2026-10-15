// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

/**
 * Lifecycle vocabulary for leased job rows: {@link #PENDING}, {@link #CLAIMED}, then {@link #DONE} or {@link #FAILED}.
 * Queues with a dead-letter cooldown park past their attempt cap as {@link #DEAD} so routine re-enqueue cannot
 * resurrect them into an unbounded retry loop. Only row types with this exact lifecycle reference it.
 */
public final class JobStatus {

    private JobStatus() {}

    public static final String PENDING = "pending";
    public static final String CLAIMED = "claimed";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String DEAD = "dead";
}
