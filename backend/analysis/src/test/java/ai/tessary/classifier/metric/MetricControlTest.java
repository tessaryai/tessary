// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.metric.MetricHistogram.Grid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rolling control: what it remembers, how fast it forgets, and what it refuses to absorb. */
class MetricControlTest {

    private static final Grid GRID = Grid.duration();
    private static final String EVENT_DAY = MetricControl.dayOf(Instant.parse("2026-08-10T12:00:00Z"));

    /** A window of {@code n} samples all at {@code value}, in the measure's own units. */
    private static MetricSketch window(int n, double value) {
        MetricHistogram h = new MetricHistogram(GRID);
        for (int i = 0; i < n; i++) {
            h.add(Math.log(value));
        }
        return h;
    }

    private static MetricWorkload workload() {
        return new MetricWorkload(MetricWorkload.grid(GRID.bins()));
    }

    private static MetricTokens tokens() {
        return new MetricTokens(MetricTokens.grid(GRID.bins()));
    }

    /** The event days the ring holds, oldest first, read off its persisted form. */
    private static List<String> daysOf(MetricControl control) {
        List<String> out = new ArrayList<>();
        try {
            for (JsonNode day : MetricHistogram.JSON.readTree(control.toJson()).path("days")) {
                out.add(day.path("d").asText());
            }
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
        return out;
    }

    private static MetricControl ringOf(String... days) {
        MetricControl control = MetricControl.empty();
        for (String day : days) {
            control = control.fold(GRID, day, window(100, 1000), workload(), tokens());
        }
        return control;
    }

    @Test
    @DisplayName("windows landing on the same day merge exactly, so a day is not a sample of itself")
    void sameDayWindowsMergeExactly() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(100, 1000), workload(), tokens())
                .fold(GRID, "2026-08-10", window(400, 1000), workload(), tokens());

        assertEquals(1, daysOf(control).size(), "one day is one slot however many windows closed in it");
        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(resolved);
        assertEquals(500, resolved.measure().count(), "the day carries every sample that closed in it");
    }

    @Test
    @DisplayName("a fortnight-old day counts a quarter of a fresh one, and a three-week-old day nothing")
    void weightHalvesEverySevenDays() {
        // Same population both days, so any difference is the weighting.
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-07-27", window(1000, 1000), workload(), tokens()) // 14 days before EVENT_DAY
                .fold(GRID, "2026-08-10", window(1000, 1000), workload(), tokens()); // the judged window's day

        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(resolved);
        // Kish's effective sample size for weights 1 and 0.25 over 1000 each ≈ 1470.
        assertEquals(1470.0, resolved.measure().count(), 5.0, "an old day contributes, but not as a fresh one");

        // Past retention it contributes nothing and leaves the ring.
        MetricControl aged = MetricControl.empty()
                .fold(GRID, "2026-07-01", window(1000, 1000), workload(), tokens())
                .fold(GRID, "2026-08-10", window(1000, 1000), workload(), tokens());
        assertEquals(1, daysOf(aged).size(), "a day past retention is dropped on the next fold");
    }

    @Test
    @DisplayName("a day exactly RETAIN_DAYS old is the inclusive bound; one day older is not")
    void retentionBoundIsInclusive() {
        // One slot, so only resolve's boundary is on trial.
        MetricControl control =
                MetricControl.empty().fold(GRID, "2026-07-20", window(1000, 1000), workload(), tokens());

        MetricControl.Resolved atBound = control.resolve(GRID, "2026-08-10", Set.of());
        assertNotNull(atBound, "a day exactly three half-lives old still counts");
        assertEquals(1, atBound.daysUsed());

        MetricControl.Resolved pastBound = control.resolve(GRID, "2026-08-11", Set.of());
        assertNull(pastBound, "one day older than the bound drops out of the read entirely");
    }

    @Test
    @DisplayName("resolve skips days after the judged window's event day")
    void resolveSkipsDaysAfterTheJudgedEventDay() {
        // A day after the judged window (out-of-order backfill) must not count: on the event clock it had not
        // happened.
        MetricControl control = ringOf("2026-08-05", "2026-08-12");

        MetricControl.Resolved resolved = control.resolve(GRID, "2026-08-10", Set.of());
        assertNotNull(resolved);
        assertEquals(1, resolved.daysUsed(), "the future day is skipped");
        assertEquals("2026-08-05", resolved.oldestDay());
    }

    @Test
    @DisplayName(
            "retention anchors on the newest day the ring holds, so an out-of-order import cannot evict recent days")
    void retentionAnchorsOnTheNewestDayHeld() {
        MetricControl control = MetricControl.empty().fold(GRID, "2026-08-10", window(100, 1000), workload(), tokens());

        // An import nine weeks earlier. Anchoring retention on the day just folded would drop 2026-08-10 as far
        // future.
        MetricControl afterImport = control.fold(GRID, "2026-06-15", window(100, 1000), workload(), tokens());

        assertTrue(
                daysOf(afterImport).contains("2026-08-10"),
                "the recent day survives an older import instead of being evicted by it");
        // The ancient import is itself past retention from the newest day.
        assertFalse(
                daysOf(afterImport).contains("2026-06-15"),
                "the import is past retention relative to the newest day, so it is not kept either");
    }

    @Test
    @DisplayName("a confirmed regression's days are left out, so it never becomes the bar it is judged against")
    void confirmedDaysAreExcluded() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-08", window(500, 1000), workload(), tokens())
                .fold(GRID, "2026-08-09", window(500, 4000), workload(), tokens()) // the regression
                .fold(GRID, "2026-08-10", window(500, 4000), workload(), tokens()); // still regressed

        MetricControl.Resolved all = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(all);
        MetricControl.Resolved clean = control.resolve(GRID, EVENT_DAY, Set.of("2026-08-09", "2026-08-10"));
        assertNotNull(clean);

        assertEquals(1, clean.daysUsed(), "only the day before the regression is left");
        // With the regression folded in, the control moves toward it and the next window barely looks shifted.
        assertTrue(
                clean.measure().meanLog() < all.measure().meanLog() - 0.5,
                "excluding the regression keeps the reference at the pre-regression level: clean="
                        + clean.measure().meanLog() + " all=" + all.measure().meanLog());
        assertEquals(Math.log(1000), clean.measure().meanLog(), 0.05);
    }

    @Test
    @DisplayName("exclusion is retroactive: the ring keeps the day, the read leaves it out")
    void theRingIsExactAndExclusionIsLate() {
        MetricControl control = ringOf("2026-08-09", "2026-08-10");
        assertEquals(2, daysOf(control).size(), "the ring records what closed, not what was later ruled on");

        // A verdict applies on the next read with no rewrite, so a late triage can still act.
        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of("2026-08-10"));
        assertNotNull(resolved);
        assertEquals(1, resolved.daysUsed());
        assertEquals(2, daysOf(MetricControl.fromJson(control.toJson())).size(), "and the ring still holds both");
    }

    @Test
    @DisplayName("the ring round-trips, and an unreadable one comes back empty rather than throwing")
    void serializationRoundTripsAndToleratesGarbage() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(250, 1500), workload(), tokens())
                .fold(GRID, "2026-08-09", window(250, 1500), workload(), tokens());

        MetricControl back = MetricControl.fromJson(control.toJson());
        assertEquals(2, daysOf(back).size());
        MetricControl.Resolved before = control.resolve(GRID, EVENT_DAY, Set.of());
        MetricControl.Resolved after = back.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(
                before.measure().count(), after.measure().count(), "a rehydrated ring resolves to the same reference");

        // An unreadable ring waits for a fresh one; it does not take the sweep down.
        assertNull(MetricControl.fromJson("{not json").newest());
        assertNull(MetricControl.fromJson(null).newest());
    }

    @Test
    @DisplayName("a day on a retired grid is skipped, not merged, and the rest of the ring still answers")
    void aDeadGridDayIsSkipped() {
        MetricControl control = MetricControl.empty().fold(GRID, "2026-08-10", window(200, 1000), workload(), tokens());

        // hist_bins edited under a live project: a layout the stored day cannot join.
        Grid moved = new Grid(GRID.lo(), GRID.ratio(), GRID.bins() - 1);
        assertNull(
                control.resolve(moved, EVENT_DAY, Set.of()), "a ring of dead days answers nothing rather than wrongly");
    }

    /** An unparseable day slot is dropped from the read rather than failing the bucket or the sweep. */
    @Test
    @DisplayName("an unreadable day slot or context blob is left out of the reference, not thrown")
    void anUnreadableSlotIsLeftOutOfTheReference() {
        String unknownKind = "{\"kind\":\"tdigest\"}";
        String json = "{\"kind\":\"control\",\"days\":["
                + "{\"d\":\"2026-08-09\",\"m\":" + unknownKind + "},"
                + "{\"d\":\"2026-08-10\",\"m\":" + window(100, 1000).toJson()
                + ",\"w\":{\"" + MetricWorkload.INPUT_TOKENS + "\":" + unknownKind + "}"
                + ",\"t\":{\"" + MetricTokens.INPUT + "\":" + unknownKind + "}}]}";
        MetricControl control = MetricControl.fromJson(json);

        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(resolved);
        assertEquals(1, resolved.daysUsed(), "only the readable day is in the reference");
        assertEquals(100, resolved.measure().count());
        assertNull(resolved.workload(), "an unreadable workload is absent, not a zeroed distribution");
        assertNull(resolved.tokens());

        // A fresh window replaces the unreadable day instead of throwing on the merge.
        MetricControl.Resolved refolded = control.fold(GRID, "2026-08-09", window(50, 1000), workload(), tokens())
                .resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(refolded);
        assertEquals(2, refolded.daysUsed());
    }

    /** Mass past the grid's top edge is reported at the edge: the median of a reference that is all overflow. */
    @Test
    @DisplayName("a reference whose mass is all past the grid reads its quantiles at the top edge")
    void overflowMassReadsAtTheTopEdge() {
        double pastTheTop = Math.exp(GRID.logHi()) * 2;
        MetricControl.Resolved resolved = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(10, pastTheTop), workload(), tokens())
                .resolve(GRID, EVENT_DAY, Set.of());

        assertNotNull(resolved);
        assertEquals(GRID.logHi(), resolved.measure().quantile(0.5));
    }
}
