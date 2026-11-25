// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.RouteClasses;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Findings that fire together (level 2 guard): two shown issues whose hits share 50% or more of the smaller issue's hit
 * traces form one group. A trace is read as a ref on one user ID, day and route, so the traces two issues share on such
 * a cell are at most the smaller of their two ref counts. A cut that is not of the smaller set, a group across a hidden
 * issue, or a group with the key off would merge or split issues the data can or cannot tell apart.
 */
class CoFiringTest {

    private static final Guards OFF = new Guards(true, true, true, true, true, false, true);

    private static HitRow hit(int issue, Integer id, int day, int refs) {
        return new HitRow(issue, id, day, 1, Map.of(), refs);
    }

    private static Facts facts(List<HitRow> hits) {
        IdDays none = new IdDays(
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new long[0],
                new int[0],
                new int[0][0],
                new long[0][0]);
        return new Facts(
                200,
                0,
                null,
                List.of(),
                none,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new IssueInput("L-a", "C1", List.of("F1"), true, false),
                        new IssueInput("L-b", "C2", List.of("F2"), true, false),
                        new IssueInput("L-c", "C3", List.of("F3"), true, false),
                        new IssueInput("L-d", "C4", List.of("F4"), true, false)),
                hits,
                new RouteClasses(Map.of(), false),
                List.of(),
                List.of(),
                List.of());
    }

    /** Issue b has {@code total} refs, {@code shared} of them on a cell that issue a also hits with more refs. */
    private static Facts pair(int shared, int total) {
        return facts(List.of(
                hit(0, 1, 150, shared + 10),
                hit(0, 3, 151, 200),
                hit(1, 1, 150, shared),
                hit(1, 2, 150, total - shared)));
    }

    @Test
    void coFiring() {
        Set<String> shown = Set.of("L-a", "L-b", "L-c", "L-d");
        assertAll(
                () -> assertEquals(
                        Map.of("L-a", "L-a", "L-b", "L-a"),
                        CoFiring.groups(pair(5, 10), shown, Guards.allOn()),
                        "5 of the smaller set's 10 traces"),
                () -> assertEquals(Map.of(), CoFiring.groups(pair(49, 100), shown, Guards.allOn()), "49%"),
                () -> assertEquals(Map.of(), CoFiring.groups(pair(5, 10), shown, OFF), "key off"),
                () -> assertEquals(
                        Map.of(), CoFiring.groups(pair(5, 10), Set.of("L-a", "L-c"), Guards.allOn()), "b not shown"));
    }

    @Test
    void sharedTraces_areTheSmallerRefCountOfACell_andSkipRefsWithNoUserId() {
        Facts f = facts(List.of(
                hit(0, 1, 150, 2),
                hit(0, 4, 152, 8),
                hit(0, null, 150, 50),
                hit(1, 1, 150, 9),
                hit(1, 4, 153, 1),
                hit(1, null, 150, 50)));
        assertEquals(
                Map.of(),
                CoFiring.groups(f, Set.of("L-a", "L-b"), Guards.allOn()),
                "2 of 10: the ID 4 hits are on other days, and refs with no user ID are no hit set");
    }

    @Test
    void groupsJoinThroughASharedMember_andTakeTheirFirstKey() {
        Facts f = facts(
                List.of(hit(1, 1, 150, 4), hit(1, 2, 150, 4), hit(2, 1, 150, 4), hit(3, 2, 150, 4), hit(0, 9, 150, 4)));
        assertEquals(
                Map.of("L-b", "L-b", "L-c", "L-b", "L-d", "L-b"),
                CoFiring.groups(f, Set.of("L-a", "L-b", "L-c", "L-d"), Guards.allOn()));
    }
}
