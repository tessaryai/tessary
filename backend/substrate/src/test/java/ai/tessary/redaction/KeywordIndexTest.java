// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordIndexTest {

    private static final KeywordIndex INDEX = new KeywordIndex(List.of(
            List.of("secret"), // 0
            List.of("key"), // 1
            List.of("ghp_", "gho_"), // 2
            List.of())); // 3: no keywords, so a candidate for every text

    @Test
    void aKeywordThatEndsInsideAnotherIsStillReported() {
        assertEquals(set(0, 1, 3), INDEX.candidates("mysecretkey"), "key is a suffix run of secretkey");
    }

    @Test
    void matchesAsciiCaseInsensitively() {
        assertEquals(set(0, 2, 3), INDEX.candidates("SECRET is GHP_abc"));
    }

    @Test
    void aNonAsciiCharacterBreaksAKeywordRatherThanJoiningIt() {
        assertEquals(set(3), INDEX.candidates("secéret kéy"));
    }

    private static BitSet set(int... bits) {
        BitSet b = new BitSet();
        for (int bit : bits) b.set(bit);
        return b;
    }
}
