// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.finding.FindingEvidenceRepository.SpanRef;
import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import ai.tessary.rca.EvidenceChecks.Metric;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EvidenceChecksTest {

    private static final String NOT_RECORDED_TODAY =
            "Not recorded on these rows: name, kind, status, level, callSiteId, models, latencyMs, totalTokens.";

    /**
     * A field no row carries says so once. The bug is a missing field rendered as {@code (none)} at 100% on both sides,
     * which reads as a fact about the rows.
     */
    @Test
    void aFieldMissingOnEveryRowIsNamedNotRecorded() {
        List<SpanRef> rows = List.of(
                SpanRefs.row(Role.WITNESS).trace("t1").errorType("timeout").build(),
                SpanRefs.row(Role.WITNESS).trace("t2").errorType("timeout").build(),
                SpanRefs.row(Role.MEMBER).trace("t3").errorType("refused").build(),
                SpanRefs.row(Role.MEMBER).trace("t4").errorType("refused").build());

        String text = render(ClaimShape.RATE_RISE, Metric.LATENCY_MS, rows);

        assertEquals("""
                ## Flagged against comparison

                ### errorType
                | value | flagged | comparison | difference |
                |---|---|---|---|
                | refused | 0 (0.0%) | 2 (100.0%) | -100.0 pp |
                | timeout | 2 (100.0%) | 0 (0.0%) | +100.0 pp |

                Not recorded on these rows: name, kind, status, level, callSiteId, models, latencyMs, totalTokens.""", section(text, "## Flagged against comparison"));
        assertFalse(text.contains("### level"), text);
    }

    /**
     * A value as common in normal traffic as in flagged rows is not a lead. The bug is such a feature presented as one.
     */
    @Test
    void aValueAsCommonInComparisonAsFlaggedShowsNoDifference() {
        List<SpanRef> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(SpanRefs.row(Role.WITNESS)
                    .trace("f" + i)
                    .status(i < 2 ? "ok" : "error")
                    .errorType("timeout")
                    .build());
        }
        for (int i = 0; i < 6; i++) {
            rows.add(SpanRefs.row(Role.MEMBER)
                    .trace("c" + i)
                    .status(i < 3 ? "ok" : "error")
                    .build());
        }

        String text = render(ClaimShape.RATE_RISE, Metric.LATENCY_MS, rows);

        assertEquals("""
                ## Flagged against comparison

                ### errorType
                | value | flagged | comparison | difference |
                |---|---|---|---|
                | (none) | 0 (0.0%) | 6 (100.0%) | -100.0 pp |
                | timeout | 4 (100.0%) | 0 (0.0%) | +100.0 pp |

                Not recorded on these rows: name, kind, level, callSiteId, models, latencyMs, totalTokens.
                No difference between the sides: status.""", section(text, "## Flagged against comparison"));
    }

    /**
     * The detector recorded where the shift starts; that wins over any split computed here. The bug is the computed
     * split overriding what the detector recorded.
     */
    @Test
    void aChangepointRowBeatsTheComputedSplit() {
        List<SpanRef> rows = new ArrayList<>();
        for (int hour = 0; hour < 2; hour++) {
            for (int i = 0; i < 2; i++) {
                rows.add(SpanRefs.row(Role.BASELINE)
                        .trace("b" + hour + i)
                        .startedAt(hour(hour))
                        .latencyMs(100)
                        .build());
            }
        }
        for (int hour = 2; hour < 6; hour++) {
            for (int i = 0; i < 2; i++) {
                rows.add(SpanRefs.row(Role.MEMBER)
                        .trace("m" + hour + i)
                        .startedAt(hour(hour))
                        .latencyMs(1_000)
                        .build());
            }
        }
        rows.add(SpanRefs.row(Role.CHANGEPOINT)
                .trace("cp")
                .startedAt(hour(4))
                .latencyMs(1_000)
                .build());

        String text = render(ClaimShape.DISTRIBUTION_SHIFT, Metric.LATENCY_MS, rows);

        assertEquals(
                "Change point: 2026-10-01T04:00Z, from the detector's changepoint rows.", lineStarting(text, "Change"));
    }

    /**
     * A step in the flagged share splits where the step is. The bugs are a split at the window open, or on the wrong
     * side of the step.
     */
    @Test
    void aStepSeriesSplitsAtTheStep() {
        List<SpanRef> rows = new ArrayList<>();
        for (int hour = 0; hour < 8; hour++) {
            int flagged = hour < 4 ? 1 : 5;
            for (int i = 0; i < 10; i++) {
                rows.add(SpanRefs.row(i < flagged ? Role.WITNESS : Role.MEMBER)
                        .trace("t" + hour + "-" + i)
                        .startedAt(hour(hour))
                        .build());
            }
        }

        String text = render(ClaimShape.RATE_RISE, Metric.LATENCY_MS, rows);

        assertEquals(
                "Change point: 2026-10-01T04:00Z, computed as the best single split of the series.\n"
                        + "Before: share 10.0% over 40 rows. After: share 50.0% over 40 rows.",
                lineStarting(text, "Change") + "\n" + lineStarting(text, "Before"));
    }

    /**
     * Within each call site the latency is unchanged; only the mix of call sites moved. The bugs are a mix change
     * reported as two causes, or as a per-group slowdown.
     */
    @Test
    void aShiftFromMixAloneHasNoWithinGroupChange() {
        List<SpanRef> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            boolean a = i < 2;
            rows.add(SpanRefs.row(Role.BASELINE)
                    .trace("b" + i)
                    .startedAt(hour(0))
                    .callSiteId(a ? "A" : "B")
                    .latencyMs(a ? 1_000 : 100)
                    .build());
        }
        for (int i = 0; i < 10; i++) {
            boolean a = i < 8;
            rows.add(SpanRefs.row(Role.MEMBER)
                    .trace("m" + i)
                    .startedAt(hour(1))
                    .callSiteId(a ? "A" : "B")
                    .latencyMs(a ? 1_000 : 100)
                    .build());
        }

        String text = render(ClaimShape.DISTRIBUTION_SHIFT, Metric.LATENCY_MS, rows);

        assertEquals(
                "Before: mean 280 ms over 10 rows. After: mean 820 ms over 10 rows.", lineStarting(text, "Before"));
        assertEquals("""
                ## Mix within groups

                ### callSiteId
                | value | flagged mean | comparison mean | ratio |
                |---|---|---|---|
                | A | 1,000 | 1,000 | 1.00 |
                | B | 100 | 100 | 1.00 |""", section(text, "## Mix within groups"));
    }

    /**
     * Malformed output writes only its failures. The bug is an empty comparison rendered as zero-share tables, which
     * reads as every value differing.
     */
    @Test
    void noComparisonSideIsSaid() {
        List<SpanRef> rows = List.of(
                SpanRefs.row(Role.WITNESS).span("t1", "s1").errorType("schema").build(),
                SpanRefs.row(Role.WITNESS).span("t2", "s2").errorType("schema").build(),
                SpanRefs.row(Role.WITNESS).span("t3", "s3").errorType("schema").build());

        String text = render(ClaimShape.RATE_RISE, Metric.LATENCY_MS, rows);

        assertEquals(
                "Flagged rows: `witness` (3). Comparison rows: none. No comparison side: the evidence holds no row this"
                        + " shape compares against.",
                lineStarting(text, "Flagged rows"));
        assertEquals("""
                ## Flagged rows alone

                ### errorType
                | value | flagged |
                |---|---|
                | schema | 3 (100.0%) |

                """ + NOT_RECORDED_TODAY, section(text, "## Flagged rows alone"));
        assertEquals(
                "No comparison side.",
                section(text, "## Mix within groups").lines().toList().get(2));
    }

    /**
     * A finding with more rows than the reader takes says how many it read and that the flagged side is whole. The bug
     * is a silently clipped file.
     */
    @Test
    void theCeilingIsStatedWhenHit() {
        List<SpanRef> recorded = new ArrayList<>();
        for (int i = 0; i < 170; i++) {
            recorded.add(SpanRefs.row(Role.WITNESS).trace("w" + i).build());
        }
        for (int i = 0; i < 30_830; i++) {
            recorded.add(SpanRefs.row(Role.MEMBER).trace("m" + i).build());
        }
        List<SpanRef> read = recorded.subList(0, 20_000);
        EvidenceSides sides = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(recorded));

        String text = EvidenceChecks.render(ClaimShape.RATE_RISE, Metric.LATENCY_MS, read, sides, 31_000);

        assertEquals(
                "Row ceiling: 20,000 of 31,000 recorded rows read; flagged roles were read first and are whole."
                        + " The tables below are over the rows read.",
                lineStarting(text, "Row ceiling"));
    }

    /**
     * Long values push the file past its character ceiling; it is cut on a whole line with the real counts stated, and
     * the series stays whole. The bug is a file that overran the dossier, or one cut with no note.
     */
    @Test
    void theCharacterCeilingIsStatedWhenHit() {
        List<SpanRef> rows = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String tail = Integer.toString(i) + "x".repeat(1_500);
            rows.add(SpanRefs.row(i < 8 ? Role.WITNESS : Role.MEMBER)
                    .trace("t" + i)
                    .startedAt(hour(i % 4))
                    .name("n" + tail)
                    .kind("k" + tail)
                    .status("s" + tail)
                    .level("l" + tail)
                    .build());
        }

        String text = render(ClaimShape.RATE_RISE, Metric.LATENCY_MS, rows);

        assertTrue(text.length() <= EvidenceChecks.CHAR_CEILING, Integer.toString(text.length()));
        Matcher note = Pattern.compile(
                        "\n\n\\[checks truncated: ([\\d,]+) of ([\\d,]+) characters shown\\. The series and change point"
                                + " above the cut are complete\\.]$")
                .matcher(text);
        assertTrue(note.find(), text.substring(text.length() - 200));
        assertEquals(String.format(Locale.ROOT, "%,d", note.start()), note.group(1));
        assertTrue(Integer.parseInt(note.group(2).replace(",", "")) > EvidenceChecks.CHAR_CEILING, note.group(2));
        int changePoint = text.indexOf("Change point:");
        assertTrue(changePoint > 0 && text.indexOf("## Flagged against comparison") > changePoint, "series cut");
    }

    /**
     * The cut lands on the last whole line that leaves room for the note. The bug is a cut mid-line, or a note that
     * pushes the file back over its ceiling.
     */
    @Test
    void aLongTextIsCutAtTheLastWholeLineWithTheCutStated() {
        String line = "x".repeat(99) + "\n";

        // The note at its widest is 113 characters, so the cut is the last newline at or before 39,887: index 39,799.
        assertEquals(
                line.repeat(398).substring(0, 39_799)
                        + "\n\n[checks truncated: 39,799 of 100,000 characters shown. The series and change point"
                        + " above the cut are complete.]",
                EvidenceChecks.truncate(line.repeat(1_000)));
    }

    /**
     * A presence event counts its flagged rows per hour, with the empty hour shown. The bug is a presence event
     * rendered with a share column over an empty comparison.
     */
    @Test
    void aPresenceEventSeriesCountsFlaggedPerBucket() {
        List<SpanRef> rows = List.of(
                SpanRefs.row(Role.MEMBER).trace("t1").startedAt(hour(0)).build(),
                SpanRefs.row(Role.MEMBER)
                        .trace("t2")
                        .startedAt("2026-10-01T00:30:00Z")
                        .build(),
                SpanRefs.row(Role.MEMBER).trace("t3").startedAt(hour(2)).build());

        String text = render(ClaimShape.PRESENCE_EVENT, Metric.LATENCY_MS, rows);

        assertEquals("""
                ## Series

                Hourly buckets, 2026-10-01T00:00Z to 2026-10-01T02:00Z (3 buckets).

                | bucket | flagged |
                |---|---|
                | 2026-10-01T00:00Z | 2 |
                | 2026-10-01T01:00Z | 0 |
                | 2026-10-01T02:00Z | 1 |

                Change point: 2026-10-01T01:00Z, computed as the best single split of the series.
                Before: 2 flagged over 1 bucket. After: 1 flagged over 2 buckets.""", section(text, "## Series"));
        assertEquals(
                "Flagged rows: `exemplar` (0), `member` (3), `changepoint` (0). Comparison rows: none. No comparison"
                        + " side: a presence event has nothing it is compared against.",
                lineStarting(text, "Flagged rows"));
    }

    private static String render(ClaimShape shape, Metric metric, List<SpanRef> rows) {
        EvidenceSides sides = EvidenceSides.of(shape, SpanRefs.recorded(rows));
        return EvidenceChecks.render(shape, metric, rows, sides, rows.size());
    }

    private static String hour(int hour) {
        return String.format(Locale.ROOT, "2026-10-01T%02d:00:00Z", hour);
    }

    /** From the heading to the next {@code ## } heading, without the blank line between them. */
    private static String section(String text, String heading) {
        int from = text.indexOf("\n" + heading + "\n");
        assertTrue(from >= 0, "no " + heading + " in " + text);
        int to = text.indexOf("\n## ", from + heading.length() + 1);
        return (to < 0 ? text.substring(from + 1) : text.substring(from + 1, to)).strip();
    }

    private static String lineStarting(String text, String prefix) {
        List<String> lines = text.lines().filter(l -> l.startsWith(prefix)).toList();
        assertEquals(1, lines.size(), "lines starting " + prefix + " in " + text);
        return lines.get(0);
    }
}
