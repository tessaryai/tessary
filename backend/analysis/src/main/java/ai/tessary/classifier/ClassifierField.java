// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.detector.RegexDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;

/**
 * Which side a text detector reads. {@link RegexDetector} matches the input or output text alone, so "OUTPUT contains
 * X" means the output only.
 */
public enum ClassifierField {
    INPUT,
    OUTPUT,
    BOTH
}
