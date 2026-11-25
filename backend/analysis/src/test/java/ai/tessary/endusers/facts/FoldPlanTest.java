// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Which days a fold run replaces. A day whose traces retention has started to delete must keep the rows folded while it
 * was whole, or history shrinks; a day the plan skips keeps stale rows.
 */
class FoldPlanTest {

    private static final int TODAY = 20619;

    @Test
    void oldestReplaceableDay_isTwoDaysInsideTheTtlCut() {
        assertAll(
                () -> assertEquals(TODAY - 88, FoldPlan.oldestReplaceableDay(TODAY, 90)),
                () -> assertNull(FoldPlan.oldestReplaceableDay(TODAY, 0), "a TTL of 0 has no edge"));
    }

    @Test
    void days_steadyState_areTheLast7ClosedDaysAndChangedLiveDays() {
        Integer oldest = FoldPlan.oldestReplaceableDay(TODAY, 90);

        assertEquals(
                List.of(TODAY - 30, TODAY - 7, TODAY - 6, TODAY - 5, TODAY - 4, TODAY - 3, TODAY - 2, TODAY - 1),
                FoldPlan.days(TODAY, oldest, null, Set.of(TODAY - 95, TODAY - 89, TODAY - 30, TODAY - 3, TODAY)),
                "a changed day at or past the edge, or today, is not folded");
    }

    @Test
    void days_catchUp_foldsEveryDayAfterTheLastFold() {
        assertEquals(
                IntStream.range(TODAY - 20, TODAY).boxed().toList(),
                FoldPlan.days(TODAY, TODAY - 88, TODAY - 20, Set.of(TODAY - 15)));
    }

    @Test
    void days_backfill_runsFromTheFirstTraceDayOldestFirst() {
        assertAll(
                () -> assertEquals(
                        IntStream.range(TODAY - 10, TODAY).boxed().toList(),
                        FoldPlan.days(TODAY, TODAY - 88, TODAY - 10, Set.of())),
                () -> assertEquals(
                        IntStream.range(TODAY - 88, TODAY).boxed().toList(),
                        FoldPlan.days(TODAY, TODAY - 88, TODAY - 90, Set.of()),
                        "a first trace on a partly deleted day starts the backfill at the oldest whole day"),
                () -> assertEquals(
                        IntStream.range(TODAY - 400, TODAY).boxed().toList(),
                        FoldPlan.days(TODAY, null, TODAY - 400, Set.of())));
    }

    @Test
    void days_shortTtl_clipsTheLast7Days() {
        assertEquals(
                List.of(TODAY - 5, TODAY - 4, TODAY - 3, TODAY - 2, TODAY - 1),
                FoldPlan.days(TODAY, FoldPlan.oldestReplaceableDay(TODAY, 7), null, Set.of()));
    }

    @Test
    void gapDays_areTheDaysLostBetweenTheLastFoldAndTheEdge() {
        assertAll(
                () -> assertEquals(
                        IntStream.range(TODAY - 199, TODAY - 88).boxed().toList(),
                        FoldPlan.gapDays(TODAY - 200, TODAY - 88)),
                () -> assertEquals(List.of(), FoldPlan.gapDays(TODAY - 89, TODAY - 88)),
                () -> assertEquals(List.of(), FoldPlan.gapDays(TODAY - 3, TODAY - 88)),
                () -> assertEquals(List.of(), FoldPlan.gapDays(null, TODAY - 88), "a first run has no gap"),
                () -> assertEquals(List.of(), FoldPlan.gapDays(TODAY - 200, null), "nothing is lost without a TTL"));
    }
}
