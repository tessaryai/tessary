// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import java.util.regex.Pattern;

/**
 * The default {@link NlPhraseCompiler}: the phrase is literal text, never a regex. Whitespace runs become {@code \s+}
 * and everything else is quoted, so the pattern cannot backtrack catastrophically. A future free-form regex path must
 * not bypass this with {@link Pattern#compile} on untrusted input.
 */
public final class DeterministicNlPhraseCompiler implements NlPhraseCompiler {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
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
