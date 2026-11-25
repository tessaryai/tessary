// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.model.Facts.AliasEdge;
import ai.tessary.endusers.model.AliasVia;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An alias merges two IDs into one unit. A link that is not one-to-one would merge two people into one, and a chain
 * that is not followed to its end would leave a unit split in two.
 */
class AliasesTest {

    @Test
    void alias_oneToOneOnly() {
        assertAll(
                () -> assertEquals(
                        Map.of(),
                        Aliases.representatives(List.of(session(1, 2, 10), session(1, 3, 11))),
                        "X to Y and X to Z give no link"),
                () -> assertEquals(
                        Map.of(),
                        Aliases.representatives(List.of(session(1, 3, 10), session(2, 3, 11))),
                        "X to Z and Y to Z give no link"),
                () -> assertEquals(
                        Map.of(1, 2), Aliases.representatives(List.of(session(1, 2, 10))), "a one-to-one link"));
    }

    @Test
    void alias_poolsSessionAndThreadLinksBeforeTheOneToOneFilter() {
        assertAll(
                () -> assertEquals(
                        Map.of(1, 2),
                        Aliases.representatives(List.of(
                                session(1, 2, 10), new AliasEdge(1, 2, AliasVia.THREAD, 10), session(1, 2, 12))),
                        "one pair seen in a session and a thread, and on two days, is one link"),
                () -> assertEquals(
                        Map.of(),
                        Aliases.representatives(List.of(session(1, 2, 10), new AliasEdge(1, 3, AliasVia.THREAD, 11))),
                        "a session successor and a thread successor are two successors"));
    }

    @Test
    void alias_chainEndsAtTheLastId() {
        assertAll(
                () -> assertEquals(
                        Map.of(1, 3, 2, 3), Aliases.representatives(List.of(session(1, 2, 10), session(2, 3, 11)))),
                () -> assertEquals(
                        Map.of(),
                        Aliases.representatives(List.of(session(1, 2, 10), session(2, 1, 11))),
                        "a loop has no last ID: each ID stays its own unit"));
    }

    private static AliasEdge session(int from, int to, int day) {
        return new AliasEdge(from, to, AliasVia.SESSION, day);
    }
}
