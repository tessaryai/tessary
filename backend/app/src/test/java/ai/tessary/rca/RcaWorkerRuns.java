// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

/** Runs one claimed RCA job on the caller's thread, for a Spring test outside this package. */
public final class RcaWorkerRuns {

    private RcaWorkerRuns() {}

    public static void run(RcaWorker worker, RcaJobRow job) {
        worker.run(job);
    }
}
