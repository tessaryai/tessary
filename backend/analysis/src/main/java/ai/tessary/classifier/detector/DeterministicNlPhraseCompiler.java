// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import java.util.regex.Pattern;

/**
 * Compiles a literal natural-language phrase into a case-insensitive, whitespace-flexible
 * {@link Pattern}, once, at signal-definition time, so {@link RegexDetector}'s per-trace evaluation is
 * pure {@link Pattern#matcher} matching with <b>no model call</b>. The phrase is treated as <b>literal text</b>,
 * never as a regex — every run of whitespace in the phrase becomes {@code \s+} (so "thank you" matches
 * "thank   you" and "thank\nyou"), and every other character is {@link Pattern#quote escaped}, so no
 * metacharacter in the phrase is ever interpreted.
 *
 * <p><b>ReDoS:</b> because the literal segments are wrapped in {@code \Q…\E} and the only quantifier
 * emitted is the linear {@code \s+}, the compiled pattern cannot backtrack catastrophically — it is safe
 * to run over arbitrary observation text. (If a free-form-regex {@code config_json} path is ever added,
 * it must NOT bypass this compiler: route it through one that bounds the pattern, rather than calling
 * {@link Pattern#compile} on untrusted input directly.)
 */
public final class DeterministicNlPhraseCompiler {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Compile one NL phrase into a {@link Pattern}. Called at definition time (detector construction or
     * a config override), never per observation.
     *
     * @param nlPhrase the literal phrase to match.
     * @param wordBoundary whether to anchor the match on word boundaries ({@code \b}).
     */
    public Pattern compile(String nlPhrase, boolean wordBoundary) {
        String collapsed = WHITESPACE.matcher(nlPhrase.strip()).replaceAll(" ");
        String[] tokens = collapsed.split(" ");
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) regex.append("\\s+");
            regex.append(Pattern.quote(tokens[i]));
        }
        String body = wordBoundary ? "\\b" + regex + "\\b" : regex.toString();
        return Pattern.compile(body, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
