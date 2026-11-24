// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The own null of each issue draws from Python's Mersenne Twister seeded with a CRC-32. A draw that differs from
 * Python's gives the Java judge a different null than the reference, and the frozen scenarios stop matching.
 */
class PyRandomTest {

    private static double draw(long seed, int index) {
        PyRandom r = new PyRandom(seed);
        double v = 0;
        for (int i = 0; i <= index; i++) {
            v = r.random();
        }
        return v;
    }

    @Test
    void random_matchesPythonFirstDraws() {
        assertAll(
                () -> assertEquals(0.8444218515250481, draw(0, 0), "M6 seed 0"),
                () -> assertEquals(0.13436424411240122, draw(1, 0), "M6 seed 1"),
                () -> assertEquals(0.7579544029403025, draw(0, 1)));
    }

    @Test
    void random_matchesPythonAfterTheStateIsRegenerated() {
        assertAll(
                () -> assertEquals(0.5198341022016146, draw(0, 623)),
                () -> assertEquals(0.5739818030823766, draw(0, 624), "draw 625 needs a second twist"));
    }

    @Test
    void random_seedsWithTheUnsignedCrc32Range() {
        assertEquals(0.6353574441341173, draw(4_294_967_295L, 0), "a CRC-32 above 2^31 is not negative");
    }

    @Test
    void random_seedsWithMoreThanOneWord() {
        assertEquals(0.11299430095636409, draw(4_294_967_296L, 0), "Python keys a seed of 2^32 with two words");
    }
}
