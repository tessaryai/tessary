// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit acceptance for the deterministic NL→regex compiler: an NL phrase compiles to a
 * case-insensitive, whitespace-flexible, literal-only {@link Pattern} — no metacharacter in the phrase
 * is ever interpreted, and (with word boundaries) the match does not bleed across word edges.
 */
class DeterministicNlPhraseCompilerTest {

    private final NlPhraseCompiler compiler = new DeterministicNlPhraseCompiler();

    @Test
    void compile_matchesLiteralPhraseCaseInsensitively() {
        Pattern p = compiler.compile("api key", false);
        assertTrue(p.matcher("here is your API Key now").find(), "matches regardless of case");
        assertTrue(p.matcher("api key").find(), "matches the exact phrase");
    }

    @Test
    void compile_isWhitespaceFlexible() {
        Pattern p = compiler.compile("thank you", false);
        assertTrue(p.matcher("thank   you").find(), "collapses internal whitespace runs to \\s+");
        assertTrue(p.matcher("thank\nyou").find(), "matches across a newline");
    }

    @Test
    void compile_treatsRegexMetacharactersAsLiterals() {
        Pattern p = compiler.compile("a.b(c)", false);
        assertTrue(p.matcher("a.b(c)").find(), "the literal phrase matches itself");
        assertFalse(p.matcher("axbXc").find(), "'.' and '(' are literals, not regex metacharacters");
    }

    @Test
    void compile_wordBoundaryDoesNotMatchSubstrings() {
        Pattern bounded = compiler.compile("cat", true);
        assertFalse(bounded.matcher("category").find(), "word-bounded 'cat' does not match inside 'category'");
        assertTrue(bounded.matcher("the cat sat").find(), "but matches a standalone word");

        Pattern unbounded = compiler.compile("cat", false);
        assertTrue(unbounded.matcher("category").find(), "unbounded 'cat' matches the substring");
    }

    @Test
    void compile_doesNotCatastrophicallyBacktrack() {
        Pattern p = compiler.compile("password is", true);
        String hostile = "a".repeat(100_000);
        long start = System.nanoTime();
        assertFalse(p.matcher(hostile).find(), "no match on adversarial input");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1_000, "literal/word-boundary pattern is linear (ReDoS-safe); took " + elapsedMs + "ms");
    }
}
