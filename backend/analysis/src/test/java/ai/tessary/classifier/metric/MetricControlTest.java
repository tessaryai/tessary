// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.metric.MetricHistogram.Grid;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rolling control: what it remembers, how fast it forgets, and what it refuses to absorb.
 *
 * <p>Every assertion here is about a property the single previously-closed window did NOT have, because
 * those are the properties the replacement exists for.
 */
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

    private static MetricControl ringOf(String... days) {
        MetricControl control = MetricControl.empty();
        for (String day : days) {
            control = control.fold(GRID, day, window(100, 1000), null, null);
        }
        return control;
    }

    @Test
    @DisplayName("windows landing on the same day merge exactly, so a day is not a sample of itself")
    void sameDayWindowsMergeExactly() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(100, 1000), null, null)
                .fold(GRID, "2026-08-10", window(400, 1000), null, null);

        assertEquals(1, control.days().size(), "one day is one slot however many windows closed in it");
        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(resolved);
        assertEquals(500, resolved.measure().count(), "the day carries every sample that closed in it");
    }

    @Test
    @DisplayName("a fortnight-old day counts a quarter of a fresh one, and a three-week-old day nothing")
    void weightHalvesEverySevenDays() {
        // Same population on both days, so any difference in the resolved count is the weighting alone.
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-07-27", window(1000, 1000), null, null) // 14 days before EVENT_DAY
                .fold(GRID, "2026-08-10", window(1000, 1000), null, null); // the judged window's own day

        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(resolved);
        // Kish's effective sample size for weights 1 and 0.25 over 1000 samples each:
        // (1000·1 + 1000·0.25)² / (1000·1² + 1000·0.25²) = 1562500/1062.5 ≈ 1470.
        assertEquals(1470.0, resolved.measure().count(), 5.0, "an old day contributes, but not as a fresh one");

        // Aged past retention it contributes nothing at all — and the ring stops carrying it.
        MetricControl aged = MetricControl.empty()
                .fold(GRID, "2026-07-01", window(1000, 1000), null, null)
                .fold(GRID, "2026-08-10", window(1000, 1000), null, null);
        assertEquals(1, aged.days().size(), "a day past retention is dropped on the next fold");
    }

    @Test
    @DisplayName("a day exactly RETAIN_DAYS old is the inclusive bound; one day older is not")
    void retentionBoundIsInclusive() {
        // One slot, isolated from fold's own retention pruning, so only resolve's boundary is on trial.
        MetricControl control = MetricControl.empty().fold(GRID, "2026-07-20", window(1000, 1000), null, null);

        // 2026-08-10 is exactly 21 days after 2026-07-20.
        MetricControl.Resolved atBound = control.resolve(GRID, "2026-08-10", Set.of());
        assertNotNull(atBound, "a day exactly three half-lives old still counts");
        assertEquals(1, atBound.daysUsed());

        MetricControl.Resolved pastBound = control.resolve(GRID, "2026-08-11", Set.of());
        assertNull(pastBound, "one day older than the bound drops out of the read entirely");
    }

    @Test
    @DisplayName("resolve skips days after the judged window's event day")
    void resolveSkipsDaysAfterTheJudgedEventDay() {
        // The ring holds a day AFTER the window under judgment — the late half of an out-of-order
        // backfill. Judging an earlier window against it must not pull in traffic that, on the event
        // clock, had not happened yet.
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
        // A ring that has already seen a recent day...
        MetricControl control = MetricControl.empty().fold(GRID, "2026-08-10", window(100, 1000), null, null);

        // ...then an import lands a day nine weeks earlier. Anchoring retention on the day just folded,
        // rather than on the newest day the ring holds, would read 2026-08-10 as impossibly far in the
        // future relative to 2026-06-15 and drop it — the eviction this anchor exists to prevent.
        MetricControl afterImport = control.fold(GRID, "2026-06-15", window(100, 1000), null, null);

        assertTrue(
                afterImport.days().stream().anyMatch(d -> "2026-08-10".equals(d.day())),
                "the recent day survives an older import instead of being evicted by it");
        // And retention still means something: the ancient import is itself more than 21 days before the
        // newest day the ring holds, so it does not linger either.
        assertFalse(
                afterImport.days().stream().anyMatch(d -> "2026-06-15".equals(d.day())),
                "the import is past retention relative to the newest day, so it is not kept either");
    }

    @Test
    @DisplayName("a window's event day is the day of its closing sample, so one spanning midnight lands on one day")
    void eventDayIsTheClosingSamplesDay() {
        // The window opened late on one UTC day and its last sample — the one that closed it — landed
        // just after midnight. MetricDriftSweep computes the event day from that closing sample alone
        // (MetricControl.dayOf), so both the fold and the read below use the day AFTER midnight.
        String closingSampleDay = MetricControl.dayOf(Instant.parse("2026-08-11T00:00:05Z"));
        assertEquals("2026-08-11", closingSampleDay);

        MetricControl control = MetricControl.empty().fold(GRID, closingSampleDay, window(100, 1000), null, null);
        assertEquals(1, control.days().size());
        MetricControl.Day newest = control.newest();
        assertNotNull(newest);
        assertEquals("2026-08-11", newest.day(), "the whole window is one slot, on the closing day");
    }

    @Test
    @DisplayName("a confirmed regression's days are left out, so it never becomes the bar it is judged against")
    void confirmedDaysAreExcluded() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-08", window(500, 1000), null, null)
                .fold(GRID, "2026-08-09", window(500, 4000), null, null) // the regression
                .fold(GRID, "2026-08-10", window(500, 4000), null, null); // still regressed

        MetricControl.Resolved all = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(all);
        MetricControl.Resolved clean = control.resolve(GRID, EVENT_DAY, Set.of("2026-08-09", "2026-08-10"));
        assertNotNull(clean);

        assertEquals(1, clean.daysUsed(), "only the day before the regression is left");
        // The whole point: with the regression folded in, the control has already moved most of the way
        // to the new level and the next window barely looks shifted. Excluded, it stays where it was.
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
        assertEquals(2, control.days().size(), "the ring records what closed, not what was later ruled on");

        // A verdict landing now takes effect on the next read, with no rewrite of anything stored. That is
        // the property that makes a triage arriving hours after the window closed able to act.
        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of("2026-08-10"));
        assertNotNull(resolved);
        assertEquals(1, resolved.daysUsed());
        assertEquals(2, MetricControl.fromJson(control.toJson()).days().size(), "and the ring still holds both");
    }

    @Test
    @DisplayName("everything excluded resolves to nothing rather than to an empty reference")
    void excludingEveryDayIsSilenceNotAnEmptyBar() {
        MetricControl control = ringOf("2026-08-10");
        assertNull(
                control.resolve(GRID, EVENT_DAY, Set.of("2026-08-10")),
                "a null reference makes the detector abstain; a zero-count one would compare against nothing");
    }

    @Test
    @DisplayName("the ring round-trips, and an unreadable one comes back empty rather than throwing")
    void serializationRoundTripsAndToleratesGarbage() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(250, 1500), null, null)
                .fold(GRID, "2026-08-09", window(250, 1500), null, null);

        MetricControl back = MetricControl.fromJson(control.toJson());
        assertEquals(2, back.days().size());
        MetricControl.Resolved before = control.resolve(GRID, EVENT_DAY, Set.of());
        MetricControl.Resolved after = back.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(
                before.measure().count(), after.measure().count(), "a rehydrated ring resolves to the same reference");

        // A bucket that cannot read its own ring waits for a fresh one; it does not take the sweep down.
        assertTrue(MetricControl.fromJson("{not json").isEmpty());
        assertTrue(MetricControl.fromJson(null).isEmpty());
    }

    @Test
    @DisplayName("the newest day is what absorb pins, and it is a whole day rather than one window")
    void newestIsTheDayAbsorbPins() {
        assertNull(MetricControl.empty().newest(), "nothing to pin before anything has closed");

        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-09", window(100, 1000), null, null)
                .fold(GRID, "2026-08-10", window(100, 4000), null, null)
                .fold(GRID, "2026-08-10", window(300, 4000), null, null);

        MetricControl.Day newest = control.newest();
        assertNotNull(newest);
        assertEquals("2026-08-10", newest.day());
        assertEquals(400, MetricSketch.fromJson(newest.sketchJson()).count(), "every window that closed that day");
    }

    @Test
    @DisplayName("a day on a retired grid is skipped, not merged, and the rest of the ring still answers")
    void aDeadGridDayIsSkipped() {
        MetricControl control = MetricControl.empty().fold(GRID, "2026-08-10", window(200, 1000), null, null);

        // hist_bins edited under a live project: the same measure, a layout the stored day cannot join.
        Grid moved = new Grid(GRID.lo(), GRID.ratio(), GRID.bins() - 1);
        assertNull(
                control.resolve(moved, EVENT_DAY, Set.of()), "a ring of dead days answers nothing rather than wrongly");
    }
}
