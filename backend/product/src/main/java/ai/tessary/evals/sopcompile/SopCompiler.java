// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.sopcompile;

import ai.tessary.evals.sop.SopCompileException;

/**
 * Turn one stored SOP document into this project's live conformance deployment.
 *
 * <p><b>Why this interface exists at all.</b> {@code SopCompileWorker} drains the {@code sop_compile}
 * queue; everything a compile actually touches — the substrate read that renders turns into the
 * engine's shape, the artifact store, the bundle loader — is the conformance stack. When both lived in
 * {@code backend/}, those were {@code product} (L3) and {@code analysis} (L6), and L3 cannot import L6:
 * Maven enforces downward-only dependencies, and it is right to. So this is the repo's standing answer
 * to that shape, the one {@code ingest/CallSiteRegistry} already uses ({@code docs/modules.md}, "Where a
 * lower layer must trigger a higher one"): an interface OWNED BY A MODULE BOTH ENDS CAN SEE. The worker
 * keeps the queue semantics — lease, attempts, dead-letter, capability gate — and knows nothing about
 * how a bundle is produced; the implementation keeps the conformance stack and knows nothing about
 * jobs.
 *
 * <p>Deliberately NOT a general "compile something" listener. One caller, one verb, one narrow
 * argument list, so an implementor never has to stub a half it does not care about.
 *
 * <p><b>Why it is in {@code sopcompile/} and not beside its caller in {@code sop/}.</b> #842 took the
 * queue half of {@code product/sop/} — intake, the document store, the compile worker — to
 * {@code tessary-paid/sop}, and #841 took the only implementation to {@code tessary-paid/conformance}
 * before it. Both ends of this seam are now paid, and they are in two DIFFERENT overlay modules that do
 * not depend on each other; an open module cannot name either. This package is the neutral ground the
 * port sits on so the direction stays open ← paid, and so the two paid modules need no edge between
 * them. It is a package with one file in it on purpose. {@code ai.tessary.evals.compile} was the other
 * candidate and was rejected: that package already exists in {@code backend/evaluation}, and reusing
 * the name would split one Java package across two Maven modules.
 *
 * <p><b>{@link SopCompileException} stays OPEN, and #842 made that call.</b> The alternative was to take
 * it to {@code tessary-paid/sop} with the worker, which fails twice over: this file names it in the
 * {@code @throws} contract below, so an open file would reference a paid package — the enforcer at
 * {@code validate}, and {@code check-open-boundary.sh} rule 1 on the text — and
 * {@code tessary-paid/conformance}, which throws it from two classes, would need a new paid → paid pom
 * edge to a module it otherwise has nothing to do with. So it sits here beside the port, in a shrunk
 * open {@code ai.tessary.evals.sop} package: one class of paid vocabulary deliberately left in the open
 * tree, because it is the failure type of THIS contract and both implementors reach it paid → open,
 * which is the legal direction.
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
     * live bundle, it must return without re-fitting. Fails LOUDLY — an unconfigured service, an
     * empty fit window, a v3 document, a bundle that will not load — because every one of those is
     * a state where the sweep would otherwise stay a silent no-op forever, and the dead-letter is
     * what makes it visible.
     *
     * @throws SopCompileException with a message an operator can act on, for every failure
     */
    Outcome compile(String projectId, String callSiteId, String sopDocumentId, String sopYaml);
}
