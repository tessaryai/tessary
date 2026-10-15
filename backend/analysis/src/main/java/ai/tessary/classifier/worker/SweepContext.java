// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierRow;

/**
 * What a {@link ClassifierSweep} gets for one pass. Deliberately minimal (no repositories, catalog or clock): widening
 * later costs one field, narrowing after out-of-tree classifiers compile against it costs them a release. @param job
 * its cursor is the sweep's to advance through {@code ClassifierJobRepository}
 */
public record SweepContext(ClassifierJobRow job, ClassifierRow classifier) {}
