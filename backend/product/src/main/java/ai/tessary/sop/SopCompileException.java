// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sop;

/**
 * A compile that did not produce a deployable bundle, carrying one sentence an operator can act on
 * and what the queue should do about it.
 *
 * <p>The message is what lands in {@code job.last_error} and therefore what a human reads when a SOP
 * stops being compiled, so it names the cause rather than the layer: "this v3 SOP does not lint",
 * "no settled traffic in this project's fit window", "compile-service returned HTTP 400: unknown
 * checkpoint". Never a stack, never a class name.
 */
public class SopCompileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * What the queue does with a failed compile.
     *
     * <p>The distinction is load-bearing because {@code ux_job_sop_compile} makes a document
     * enqueueable ONCE for the life of the job: a job parked for a reason that was never the
     * document's fault leaves that SOP uncompilable until someone re-authors the file.
     */
    public enum Disposition {
        /**
         * Deterministic refusal — a v3 document, an invalid SOP, a bundle the loader rejects. The
         * same inputs fail the same way, so the job parks NOW; retrying is a guaranteed-identical
         * failure five times over.
         */
        PARK,
        /**
         * Transient — transport, timeout, a saturated or restarting service. Back to {@code pending}
         * and retried until the attempt cap, which is what bounds the loop.
         */
        RETRY,
        /**
         * Not a failure at all: the preconditions are not met YET, and no number of attempts changes
         * that — the canonical case being a SOP imported before the project has any traffic to fit a
         * reference period on. The job returns to {@code pending} WITHOUT consuming an attempt, so it
         * waits indefinitely for the world to change instead of dead-lettering within five heartbeats
         * of a connect that simply happened first.
         */
        DEFER
    }

    private final Disposition disposition;

    /** A deterministic refusal ({@link Disposition#PARK}). */
    public SopCompileException(String message) {
        this(message, Disposition.PARK);
    }

    public SopCompileException(String message, Throwable cause) {
        this(message, cause, Disposition.PARK);
    }

    public SopCompileException(String message, Disposition disposition) {
        super(message);
        this.disposition = disposition;
    }

    public SopCompileException(String message, Throwable cause, Disposition disposition) {
        super(message, cause);
        this.disposition = disposition;
    }

    public Disposition disposition() {
        return disposition;
    }
}
