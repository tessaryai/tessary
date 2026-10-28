// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.finding.FindingEvidenceRepository.Ref;
import ai.tessary.classifier.metric.MetricEvidenceRefs.Timed;
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
            control = control.fold(GRID, day, window(100, 1000), workload(), tokens(), List.of());
        }
        return control;
    }

    @Test
    @DisplayName("windows landing on the same day merge exactly, so a day is not a sample of itself")
    void sameDayWindowsMergeExactly() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(100, 1000), workload(), tokens(), List.of())
                .fold(GRID, "2026-08-10", window(400, 1000), workload(), tokens(), List.of());

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
                .fold(
                        GRID,
                        "2026-07-27",
                        window(1000, 1000),
                        workload(),
                        tokens(),
                        List.of()) // 14 days before EVENT_DAY
                .fold(
                        GRID,
                        "2026-08-10",
                        window(1000, 1000),
                        workload(),
                        tokens(),
                        List.of()); // the judged window's day

        MetricControl.Resolved resolved = control.resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(resolved);
        // Kish's effective sample size for weights 1 and 0.25 over 1000 each ≈ 1470.
        assertEquals(1470.0, resolved.measure().count(), 5.0, "an old day contributes, but not as a fresh one");

        // Past retention it contributes nothing and leaves the ring.
        MetricControl aged = MetricControl.empty()
                .fold(GRID, "2026-07-01", window(1000, 1000), workload(), tokens(), List.of())
                .fold(GRID, "2026-08-10", window(1000, 1000), workload(), tokens(), List.of());
        assertEquals(1, daysOf(aged).size(), "a day past retention is dropped on the next fold");
    }

    @Test
    @DisplayName("a day exactly RETAIN_DAYS old is the inclusive bound; one day older is not")
    void retentionBoundIsInclusive() {
        // One slot, so only resolve's boundary is on trial.
        MetricControl control =
                MetricControl.empty().fold(GRID, "2026-07-20", window(1000, 1000), workload(), tokens(), List.of());

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
    @DisplayName("a confirmed regression's days are left out, so it never becomes the bar it is judged against")
    void confirmedDaysAreExcluded() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-08", window(500, 1000), workload(), tokens(), List.of())
                .fold(GRID, "2026-08-09", window(500, 4000), workload(), tokens(), List.of()) // the regression
                .fold(GRID, "2026-08-10", window(500, 4000), workload(), tokens(), List.of()); // still regressed

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
    @DisplayName("the ring round-trips, and an unreadable one comes back empty rather than throwing")
    void serializationRoundTripsAndToleratesGarbage() {
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(250, 1500), workload(), tokens(), List.of())
                .fold(GRID, "2026-08-09", window(250, 1500), workload(), tokens(), List.of());

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
        MetricControl.Resolved refolded = control.fold(
                        GRID, "2026-08-09", window(50, 1000), workload(), tokens(), List.of())
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
                .fold(GRID, "2026-08-10", window(10, pastTheTop), workload(), tokens(), List.of())
                .resolve(GRID, EVENT_DAY, Set.of());

        assertNotNull(resolved);
        assertEquals(GRID.logHi(), resolved.measure().quantile(0.5));
    }

    /** {@code n} trace rows named {@code prefix-i}, a second apart from {@code day}'s midnight, all at 1s. */
    private static List<Timed> rowsOf(String prefix, String day, int n) {
        List<Timed> out = new ArrayList<>(n);
        Instant start = Instant.parse(day + "T00:00:00Z");
        for (int i = 0; i < n; i++) {
            out.add(new Timed(Ref.trace(prefix + "-" + i), start.plusSeconds(i).toString(), Math.log(1000)));
        }
        return out;
    }

    private static List<String> traceIds(List<Timed> rows) {
        return rows.stream().map(t -> t.ref().traceId()).toList();
    }

    private static List<Timed> dayRows(MetricControl control) {
        MetricControl.Day day = control.newest();
        assertNotNull(day);
        return MetricControl.rows(List.of(day));
    }

    /**
     * A day keeps 100 rows, shared in proportion to the samples each window put into the day's sketch. A 500-row window
     * stores every fifth row (a-0, a-5, ... a-495). A 100-row window then earns round(100 x 100 / 600) = 17 slots, and
     * the first window keeps 83 of its 100 stored rows, stored[floor(i x 100 / 83)], ahead of the second window's
     * b-floor(i x 100 / 17). The bug is a concatenate-then-sample that gives the last window half the day.
     */
    @Test
    void aDayKeepsEveryWindowsShareOfItsRows() {
        List<Timed> first = rowsOf("a", "2026-08-10", 500);
        List<Timed> second = rowsOf("b", "2026-08-10", 100);
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(500, 1000), workload(), tokens(), first)
                .fold(GRID, "2026-08-10", window(100, 1000), workload(), tokens(), second);

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 83; i++) expected.add("a-" + 5 * (i * 100 / 83));
        for (int i = 0; i < 17; i++) expected.add("b-" + i * 100 / 17);
        List<String> kept = traceIds(dayRows(control));
        assertEquals(100, kept.size(), "a day slot is bounded");
        assertEquals("a-490", kept.get(82), "the first window's share spans its whole period");
        assertEquals("b-94", kept.get(99));
        assertEquals(expected, kept);
    }

    /** The bug is rows from a sketch no longer in the reference offered as its baseline. */
    @Test
    void aDeadGridDayDropsItsRowsWithItsSketch() {
        Grid other = new Grid(1.0, 2.0, 4);
        MetricHistogram otherWindow = new MetricHistogram(other);
        for (int i = 0; i < 30; i++) otherWindow.add(Math.log(3));
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-10", window(50, 1000), workload(), tokens(), rowsOf("a", "2026-08-10", 50))
                .fold(
                        other,
                        "2026-08-10",
                        otherWindow,
                        new MetricWorkload(MetricWorkload.grid(other.bins())),
                        new MetricTokens(MetricTokens.grid(other.bins())),
                        rowsOf("b", "2026-08-10", 30));

        assertEquals(traceIds(rowsOf("b", "2026-08-10", 30)), traceIds(dayRows(control)));
    }

    /** The bug is baseline rows drawn from a confirmed regression's day or from a day after the judged window. */
    @Test
    void resolveListsTheRowsOfTheDaysItUsedOnly() {
        List<Timed> a = rowsOf("a", "2026-08-08", 20);
        MetricControl control = MetricControl.empty()
                .fold(GRID, "2026-08-08", window(20, 1000), workload(), tokens(), a)
                .fold(GRID, "2026-08-09", window(20, 4000), workload(), tokens(), rowsOf("b", "2026-08-09", 20))
                .fold(GRID, "2026-08-11", window(20, 1000), workload(), tokens(), rowsOf("c", "2026-08-11", 20));

        MetricControl.Resolved resolved = control.resolve(GRID, "2026-08-10", Set.of("2026-08-09"));
        assertNotNull(resolved);
        assertEquals(
                List.of("2026-08-08"),
                resolved.days().stream().map(MetricControl.Day::day).toList());
        assertEquals(a, MetricControl.rows(resolved.days()));
    }

    /** The bug is a ring written before days kept rows failing the sweep, or reading as a lost write. */
    @Test
    void anOlderRingWithoutRowsReadsAsNoRows() {
        String json = "{\"kind\":\"control\",\"days\":[{\"d\":\"2026-08-09\",\"m\":"
                + window(100, 1000).toJson() + "}]}";

        MetricControl.Resolved resolved = MetricControl.fromJson(json).resolve(GRID, EVENT_DAY, Set.of());
        assertNotNull(resolved);
        assertEquals(1, resolved.daysUsed());
        assertEquals(List.of(), MetricControl.rows(resolved.days()));
    }

    /** The bug is a ring reload that loses a day's rows, their span ids or their event times. */
    @Test
    void rowsRoundTripThroughTheRing() {
        List<Timed> rows = List.of(
                new Timed(Ref.span("t-1", "s-1"), "2026-08-10T00:00:01Z", Math.log(1500)),
                new Timed(Ref.span("t-2", "s-2"), "2026-08-10T00:00:02Z", Math.log(2500)),
                new Timed(Ref.trace("t-3"), "2026-08-10T00:00:03Z", Math.log(3500)));
        MetricControl control =
                MetricControl.empty().fold(GRID, "2026-08-10", window(3, 1500), workload(), tokens(), rows);

        assertEquals(rows, dayRows(MetricControl.fromJson(control.toJson())));
    }
}
