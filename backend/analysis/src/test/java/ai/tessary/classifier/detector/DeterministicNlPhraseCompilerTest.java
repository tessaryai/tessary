// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * An NL phrase compiles to a case-insensitive, whitespace-flexible, literal-only pattern: no metacharacter in the
 * phrase is ever interpreted, and with word boundaries the match does not bleed across word edges.
 */
class DeterministicNlPhraseCompilerTest {

    private final NlPhraseCompiler compiler = new DeterministicNlPhraseCompiler();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "thank you | false | 'thank   you' | true",
                "thank you | false | 'thank\nyou'  | true",
                "a.b(c)    | false | a.b(c)        | true",
                // '.' and '(' are literals, not regex metacharacters.
                "a.b(c)    | false | axbXc         | false",
                "cat       | true  | category      | false",
                "cat       | true  | the cat sat   | true",
                "cat       | false | category      | true"
            })
    void compilesALiteralWhitespaceFlexiblePattern(String phrase, boolean wordBounded, String text, boolean matches) {
        assertEquals(
                matches, compiler.compile(phrase, wordBounded).matcher(text).find());
    }
}
