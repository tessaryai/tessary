// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.detector.RegexDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;

/**
 * Which side of an observation a text detector reads: the user-side {@code input}, the agent-side
 * {@code output}, or both. {@link RegexDetector} matches over {@link SubstrateObservation#inputText()}
 * / {@link SubstrateObservation#outputText()} directly, so "OUTPUT contains X" means the output alone.
 */
public enum ClassifierField {
    INPUT,
    OUTPUT,
    BOTH
}
