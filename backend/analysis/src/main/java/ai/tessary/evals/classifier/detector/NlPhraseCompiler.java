// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import java.util.regex.Pattern;

/**
 * The natural-language-phrase → {@link Pattern} compilation seam: translates a literal NL
 * phrase into a regex once, at signal-definition time, off the evaluation hot path. {@link RegexDetector}
 * pre-compiles its phrases through this seam so per-trace evaluation is pure {@link Pattern#matcher}
 * matching with <b>no model call</b> — consistent with the "structural/heuristic only" boundary.
 *
 * <p>The default {@link DeterministicNlPhraseCompiler} treats the phrase as a literal (escaped,
 * whitespace-flexible, optionally word-bounded, case-insensitive). An LLM-assisted NL-description→regex
 * compiler can be dropped in behind this same interface later <em>without touching the
 * evaluation path</em> — compilation stays one-time, evaluation stays model-free.
 */
public interface NlPhraseCompiler {

    /**
     * Compile one NL phrase into a {@link Pattern}. Called at definition time (detector construction or
     * a config override), never per observation.
     *
     * @param nlPhrase the literal phrase to match.
     * @param wordBoundary whether to anchor the match on word boundaries ({@code \b}).
     */
    Pattern compile(String nlPhrase, boolean wordBoundary);
}
