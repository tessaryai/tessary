// SPDX-License-Identifier: Apache-2.0
/**
 * The sweep engine and the extension seam for window-tier classifiers:
 * {@link ai.tessary.classifier.worker.ClassifierSweep} is the port,
 * {@link ai.tessary.classifier.worker.ClassifierSweepRegistry} discovers adapters,
 * {@link ai.tessary.classifier.worker.ClassifierWorker} routes jobs. The catalog is never derived from the registry; an
 * unregistered kind is inert (one WARN). A sweep may refuse enabling a row, never disabling one. Contract:
 * {@code devdocs/reference/classifier-extension-interface.md}.
 */
package ai.tessary.classifier.worker;
