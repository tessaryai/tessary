// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.catalog.BuiltInDetector;
import java.util.Set;

/**
 * The fitting tier's extension seam: one classifier that judges a UNIT LARGER THAN AN OBSERVATION —
 * a whole trace, or a window of them — dispatched from the job queue rather than from the detector
 * map.
 *
 * <p>{@link BuiltInDetector} is the other half of the pair and covers the observation/turn grain:
 * pure, batched, no state. Everything on this seam is the opposite — it reads a cursor, holds fitted
 * per-project state, and writes findings — which is why it is dispatched by {@link ClassifierWorker}
 * through a claimed job rather than evaluated per row. Before this interface existed the worker held
 * each sweep as a constructor field and dispatched with an {@code if/else} chain on the detector
 * kind, so the open engine had a compile-time dependency on every classifier the platform shipped,
 * paid ones included.
 *
 * <h2>The contract</h2>
 *
 * <p>The same four rules {@code classifier/finding/package-info.java} states for the read-side ports,
 * because this is the same seam seen from the write side:
 *
 * <ol>
 *   <li><b>The port lives open and implementations are discovered.</b> Adapters are Spring
 *       {@code @Component}s collected by {@link ClassifierSweepRegistry}; no open class names a
 *       concrete sweep, and an implementation may ship from any jar on the runtime classpath —
 *       including one this repo does not build. See
 *       {@code docs/reference/classifier-extension-interface.md}.
 *   <li><b>Sweeps key on the open {@link BuiltInDetector.Kind} constants,</b> which stay constant.
 *       {@code BuiltInClassifierCatalog.builtIns()} is NEVER filtered by what is registered here:
 *       {@code ClassifierService.retireDroppedBuiltIns} keys on catalog membership and retirement is
 *       IRREVERSIBLE, so a classifier dropping out of the catalog because its jar is absent would
 *       permanently disable every customer row that classifier ever wrote.
 *   <li><b>An unregistered kind is INERT, not an error.</b> The worker logs one WARN and completes
 *       the job — it does not throw, does not dead-letter, and above all does not fall through to
 *       another sweep. The {@code else} branch that used to route every unrecognised WINDOW kind
 *       into metric drift is what this rule exists to forbid: {@code tool_error} hit it once, kept a
 *       second copy of every duration and cost baseline, and emitted duplicate findings under its
 *       own classifier id with nothing failing loudly.
 *   <li><b>The context is narrow on purpose.</b> {@link SweepContext} carries the claimed job row and
 *       the classifier row and nothing else — no repositories, no catalog, no worker. Widening it
 *       later is cheap; narrowing it once two out-of-tree classifiers depend on it is not.
 * </ol>
 */
public interface ClassifierSweep {

    /**
     * The {@link BuiltInDetector.Kind} values this sweep claims. A kind claimed by two beans fails the
     * context at startup rather than picking one; an empty set registers nothing and can never be
     * dispatched — see {@link ClassifierSweepRegistry}.
     *
     * <p>A {@code Set} rather than a single kind because one sweep legitimately serves a family:
     * {@code MetricDriftSweep} runs both {@code duration_drift} and {@code cost_drift} from one bean
     * over one baseline store, and splitting that into two beans to satisfy a narrower port would buy
     * nothing.
     */
    Set<String> kinds();

    /**
     * Run one pass for a claimed job. The worker owns the lease, the attempt budget and the
     * dead-letter transition; an implementation that throws is retried under that budget, and one
     * that returns is treated as a clean pass.
     */
    SweepOutcome sweep(SweepContext ctx);
}
