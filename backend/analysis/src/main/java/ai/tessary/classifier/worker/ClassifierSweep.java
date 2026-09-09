// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.catalog.BuiltInDetector;
import java.util.Set;

/**
 * The fitting tier's extension seam: one classifier that judges a unit larger than an observation,
 * a whole trace or a window of them, dispatched from the job queue rather than from the detector
 * map.
 *
 * <p>{@link BuiltInDetector} is the other half of the pair and covers the observation/turn grain:
 * pure, batched, no state. Everything on this seam is the opposite: it reads a cursor, holds fitted
 * per-project state, and writes findings, which is why {@link ClassifierWorker} dispatches it
 * through a claimed job rather than evaluating it per row.
 *
 * <h2>The contract</h2>
 *
 * <p>The same four rules {@code classifier/finding/package-info.java} states for the read-side
 * ports, because this is the same seam seen from the write side:
 *
 * <ol>
 *   <li>The port lives open and implementations are discovered. Adapters are Spring {@code
 *       @Component}s collected by {@link ClassifierSweepRegistry}; no open class names a concrete
 *       sweep, and an implementation may ship from any jar on the runtime classpath. See {@code
 *       devdocs/reference/classifier-extension-interface.md}.
 *   <li>Sweeps key on the open {@link BuiltInDetector.Kind} constants, which stay constant. {@code
 *       BuiltInClassifierCatalog.builtIns()} is never filtered by what is registered here: {@code
 *       ClassifierService.retireDroppedBuiltIns} keys on catalog membership and retirement is
 *       irreversible, so a classifier dropping out of the catalog because its jar is absent would
 *       permanently disable every customer row that classifier ever wrote.
 *   <li>An unregistered kind is inert, not an error. The worker logs one warning and completes the
 *       job: it does not throw, does not dead-letter, and above all does not fall through to
 *       another sweep, since an unrecognized kind silently routed to the wrong sweep would keep a
 *       second copy of that sweep's state and emit duplicate findings with nothing failing loudly.
 *   <li>The context is narrow on purpose. {@link SweepContext} carries the claimed job row and the
 *       classifier row and nothing else, no repositories, no catalog, no worker. Widening it later
 *       is cheap; narrowing it once out-of-tree classifiers depend on it is not.
 * </ol>
 */
public interface ClassifierSweep {

    /**
     * The {@link BuiltInDetector.Kind} values this sweep claims. A kind claimed by two beans fails
     * the context at startup rather than picking one; an empty set registers nothing and can never
     * be dispatched; see {@link ClassifierSweepRegistry}.
     *
     * <p>A {@code Set} rather than a single kind because one sweep legitimately serves a family:
     * {@code MetricDriftSweep} runs both {@code duration_drift} and {@code cost_drift} from one bean
     * over one baseline store.
     */
    Set<String> kinds();

    /**
     * Run one pass for a claimed job. The worker owns the lease, the attempt budget and the
     * dead-letter transition; an implementation that throws is retried under that budget, and one
     * that returns is treated as a clean pass.
     */
    SweepOutcome sweep(SweepContext ctx);
}
