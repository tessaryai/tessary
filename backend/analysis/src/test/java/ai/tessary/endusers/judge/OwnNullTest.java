// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.Cells.Pools;
import ai.tessary.endusers.judge.OwnNull.Draw;
import ai.tessary.endusers.judge.OwnNull.Frame;
import ai.tessary.endusers.judge.OwnNull.Result;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The issue's own null of spec 8.5. A seed that is not {@code crc32("<issue>|<draw>")}, a draw count that ignores the
 * size of the estimate, two false hits for one ID, or a false arm that takes real exposed trials would give a null that
 * does not match the reference, and an issue whose loss is only its detector's habit would pass.
 */
class OwnNullTest {

    private static final Cell CELL = new Cell(4, 2, 1, 1, false, null);
    private static final String ISSUE = "C-118";
    private static final int H = 14;

    @Test
    void ownNull() {
        assertAll(
                () -> assertEquals(138418942L, OwnNull.seed(ISSUE, 0), "crc32 of C-118|0"),
                () -> assertEquals(2860321985L, OwnNull.seed(ISSUE, 59), "crc32 of C-118|59, above 2^31"),
                () -> assertEquals(60, OwnNull.drawCount(1500)),
                () -> assertEquals(30, OwnNull.drawCount(1501), "30 draws above 1,500 estimated IDs"));
    }

    @Test
    void ownNull_matchesTheReferenceDraws() {
        List<Trial> trials = sample();
        Pools pools = new Pools(trials);
        Result sixty = OwnNull.run(ISSUE, 2, Map.of(20, 2), trials, pools, H);
        Result thirty = OwnNull.run(ISSUE, 1501, Map.of(20, 2), trials, pools, H);
        assertAll(
                () -> assertEquals(60, sixty.draws()),
                () -> assertEquals(-0.010490592740592768, sixty.b0(), 1e-12),
                () -> assertEquals(0.4007521928637158, sixty.s0(), 1e-12),
                () -> assertEquals(0.6807692307692308, sixty.nullMax(), 1e-12),
                () -> assertEquals(30, thirty.draws()),
                () -> assertEquals(-0.11098156010656014, thirty.b0(), 1e-12),
                () -> assertEquals(0.3613804108970963, thirty.s0(), 1e-12),
                () -> assertEquals(0.65, thirty.nullMax(), 1e-12));
    }

    @Test
    void ownNull_oneFalseHitPerId() {
        List<Trial> trials = new ArrayList<>();
        for (int u = 1; u <= 2; u++) for (int d = 140; d <= 142; d++) trials.add(control(u, d, 1, 0));
        Map<Integer, Integer> nreal = Map.of(20, 3);
        Frame frame = OwnNull.frame(trials, nreal.keySet());
        Pools pools = new Pools(trials);
        for (int draw = 0; draw < 60; draw++) {
            Draw result = OwnNull.draw(new PyRandom(OwnNull.seed(ISSUE, draw)), nreal, frame, pools, H);
            List<Trial> arm = result.falseArm();
            assertAll(
                    () -> assertEquals(arm.size(), new HashSet<>(arm).size(), "no trial twice"),
                    () -> assertEquals(Set.of(1, 2), ids(arm), "3 false hits wanted, 2 IDs, one each"),
                    () -> assertNotNull(result.rate()));
        }
    }

    @Test
    void ownNull_realExposedTrialsAreInNeitherArm() {
        List<Trial> trials = new ArrayList<>(sample());
        trials.add(control(7, 140, 1, 0));
        trials.add(control(7, 141, 1, 0));
        for (int d = 142; d <= 146; d++)
            trials.add(new Trial(7, d, 1, d == 146 ? 0.8 : 0, d == 146 ? 1 : 0, CELL, Arm.EXPOSED));
        trials.add(new Trial(8, 145, 1, 0, 0, CELL, Arm.NEITHER));
        Map<Integer, Integer> nreal = Map.of(20, 3);
        Frame frame = OwnNull.frame(trials, nreal.keySet());
        Pools pools = new Pools(trials);
        boolean hitIdDrawn = false;
        for (int draw = 0; draw < 60; draw++) {
            List<Trial> arm = OwnNull.draw(new PyRandom(OwnNull.seed(ISSUE, draw)), nreal, frame, pools, H)
                    .falseArm();
            assertTrue(arm.stream().allMatch(t -> t.arm() == Arm.UNEXPOSED));
            hitIdDrawn |= ids(arm).contains(7);
        }
        Cells.Match match = pools.match(20, CELL);
        assertNotNull(match);
        assertAll(
                () -> assertTrue(match.pool().rows().stream().allMatch(t -> t.arm() == Arm.UNEXPOSED)),
                () -> assertFalse(ids(frame.candidates().get(20)).contains(8)));
        assertTrue(hitIdDrawn, "the days before a real hit can take a false hit");
    }

    @Test
    void falseHitsWithNoPool_giveNoDraw() {
        List<Trial> trials = List.of(control(1, 140, 1, 0), control(2, 141, 1, 0), control(3, 142, 1, 0));
        Pools pools = new Pools(trials);
        Draw draw = OwnNull.draw(
                new PyRandom(OwnNull.seed(ISSUE, 0)), Map.of(20, 1), OwnNull.frame(trials, Set.of(20)), pools, H);
        assertAll(
                () -> assertNull(draw.rate()),
                () -> assertTrue(draw.falseArm().isEmpty()),
                () -> assertEquals(new Result(0, 0, 0, 0), OwnNull.run(ISSUE, 1, Map.of(20, 1), trials, pools, H)),
                () -> assertEquals(
                        new Result(0, 0, 0, 0),
                        OwnNull.run(ISSUE, 1, Map.of(25, 1), trials, pools, H),
                        "no candidates"));
    }

    @Test
    void aPoolOfOnlyFalseHits_addsNothing() {
        List<Trial> trials = new ArrayList<>();
        for (int u = 1; u <= 5; u++) trials.add(control(u, 140, 1, u == 1 ? 0.5 : 0));
        Result result = OwnNull.run(ISSUE, 5, Map.of(20, 5), trials, new Pools(trials), H);
        assertEquals(new Result(0, 0, 0, 60), result);
    }

    private static Set<Integer> ids(List<Trial> trials) {
        Set<Integer> out = new HashSet<>();
        trials.forEach(t -> out.add(t.id()));
        return out;
    }

    /** The input of the reference replica: one cell, week 20, ordered by key index and day. */
    private static List<Trial> sample() {
        return List.of(
                control(1, 140, 1, 0),
                control(1, 141, 1, 0),
                control(1, 142, 1, 0.6),
                control(2, 140, 2, 0),
                control(2, 141, 2, 0),
                control(3, 140, 1, 0),
                control(3, 141, 1, 0),
                control(3, 142, 1, 0),
                control(4, 141, 3, 0.9),
                control(5, 142, 1, 0),
                control(5, 143, 1, 0),
                control(6, 140, 1, 0),
                control(6, 143, 2, 0.3));
    }

    private static Trial control(int id, int day, double weight, double yLo) {
        return new Trial(id, day, weight, yLo, yLo > 0 ? 1 : 0, CELL, Arm.UNEXPOSED);
    }
}
