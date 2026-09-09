// SPDX-License-Identifier: Apache-2.0
/**
 * The sweep engine: job claim, lease and dead-letter budget, and the extension seam the fitting tier
 * of classifiers is dispatched through.
 *
 * <h2>The seam</h2>
 *
 * <p>{@link ai.tessary.classifier.worker.ClassifierSweep} is the port,
 * {@link ai.tessary.classifier.worker.ClassifierSweepRegistry} discovers its adapters, and
 * {@link ai.tessary.classifier.worker.ClassifierWorker} routes a claimed job to one of them.
 * Three rules hold it, and they are the same three
 * {@code ai.tessary.classifier.finding}'s package comment states for the read side:
 *
 * <ol>
 *   <li>Ports live here, in an open package; adapters are discovered as Spring beans and no open
 *       class names a concrete sweep. That is what lets a classifier ship from a jar this reactor
 *       does not build.
 *   <li>Ports key on the open {@code BuiltInDetector.Kind} constants, and the CATALOG stays constant.
 *       The registry is never consulted to decide what the catalog contains — see
 *       {@link ai.tessary.classifier.worker.ClassifierSweepRegistry} for the irreversible
 *       retirement that rule prevents.
 *   <li>An unregistered kind is inert: one WARN, the job completes, no other sweep runs in its place.
 * </ol>
 *
 * <p>The contract an out-of-tree author writes against is
 * {@code docs/reference/classifier-extension-interface.md}.
 */
package ai.tessary.classifier.worker;
