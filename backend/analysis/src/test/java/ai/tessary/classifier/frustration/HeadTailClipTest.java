// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HeadTailClipTest {

    private static final String WORDS = "alpha beta gamma delta epsilon";

    @Test
    void clip_leavesTextWithinTheCapAlone() {
        assertEquals(WORDS, HeadTailClip.clip(WORDS, WORDS.length()));
    }

    @Test
    void clip_movesBothCutsOutwardToWhitespace() {
        assertEquals("alpha ... epsilon", HeadTailClip.clip(WORDS, 14), "neither half may split a word");
    }

    @Test
    void clip_keepsACutThatAlreadyFallsOnWhitespace() {
        assertEquals("alpha beta ... epsilon", HeadTailClip.clip(WORDS, 20));
    }

    @Test
    void clip_cutsASingleLongWordWhereItStands() {
        assertEquals("abc ... hij", HeadTailClip.clip("abcdefghij", 6));
    }

    @Test
    void clip_neverSplitsASurrogatePair() {
        String emoji = "😀";
        String clipped = HeadTailClip.clip("a" + emoji + emoji + emoji + "b", 4);
        assertEquals("a ... b", clipped, "a cut inside an emoji moves off it rather than halving it");
    }
}
