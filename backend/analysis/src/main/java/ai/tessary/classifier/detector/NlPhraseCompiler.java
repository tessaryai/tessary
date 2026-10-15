// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import java.util.regex.Pattern;

/**
 * Compiles a literal NL phrase to a {@link Pattern} once, at definition time, so {@link RegexDetector} evaluation stays
 * pure matching with no model call. {@link DeterministicNlPhraseCompiler} is the default.
 */
public interface NlPhraseCompiler {

    /** Never called per observation. */
    Pattern compile(String nlPhrase, boolean wordBoundary);
}
