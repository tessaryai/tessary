// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HeadTailClipTest {

    /** Neither half may split a word: cuts move outward to whitespace, unless one word is all there is. */
    @ParameterizedTest
    @CsvSource({
        "alpha beta gamma delta epsilon, 30, alpha beta gamma delta epsilon",
        "alpha beta gamma delta epsilon, 14, alpha ... epsilon",
        "alpha beta gamma delta epsilon, 20, alpha beta ... epsilon",
        "abcdefghij, 6, abc ... hij"
    })
    void clipKeepsAHeadAndATailOnWordBoundaries(String text, int cap, String clipped) {
        assertEquals(clipped, HeadTailClip.clip(text, cap));
    }

    @Test
    void clip_neverSplitsASurrogatePair() {
        String emoji = "😀";
        String clipped = HeadTailClip.clip("a" + emoji + emoji + emoji + "b", 4);
        assertEquals("a ... b", clipped, "a cut inside an emoji moves off it rather than halving it");
    }
}
