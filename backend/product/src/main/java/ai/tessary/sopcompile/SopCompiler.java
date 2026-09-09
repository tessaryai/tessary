// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sopcompile;

import ai.tessary.sop.SopCompileException;

/**
 * Turn one stored SOP document into this project's live conformance deployment.
 *
 * <p>A worker drains the {@code sop_compile} queue and keeps the queue semantics (lease, attempts,
 * dead-letter, capability gate) without knowing how a bundle is produced; an implementation of this
 * interface builds the bundle without knowing about jobs. A build may ship no implementation at all,
 * in which case compiles simply never run.
 *
 * <p>Deliberately not a general "compile something" listener: one caller, one verb, one narrow
 * argument list, so an implementor never has to stub a half it does not care about.
 */
public interface SopCompiler {

    /** What a compile produced, for the worker's structured log. */
    record Outcome(String engineVersion, String encoderCheckpoint, int ruleCount, int referenceTurns) {

        /** The already-fitted case: this document is what the project's live bundle came from. */
        public static Outcome alreadyFitted(String engineVersion) {
            return new Outcome(engineVersion, "", 0, 0);
        }
    }

    /**
     * Fit and deploy the bundle for {@code sopYaml}, replacing whatever the project had.
     *
     * <p>Idempotent by contract: called again for a document that already produced the project's
     * live bundle, it must return without re-fitting. Fails loudly (an unconfigured service, an
     * empty fit window, a v3 document, a bundle that will not load) because every one of those is
     * a state where the sweep would otherwise stay a silent no-op forever, and the dead-letter is
     * what makes it visible.
     *
     * @throws SopCompileException with a message an operator can act on, for every failure
     */
    Outcome compile(String projectId, String callSiteId, String sopDocumentId, String sopYaml);
}
