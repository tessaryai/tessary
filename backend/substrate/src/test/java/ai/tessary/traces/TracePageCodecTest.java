// SPDX-License-Identifier: Apache-2.0
package ai.tessary.traces;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.ingest.PreviewCursor;
import ai.tessary.storage.TraceV2Repository;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TracePageCodecTest {

    /**
     * The next-page cursor carries the value of the column the page was sorted on, so the keyset resumes
     * at the right row; a trace with no value for that column carries none, which is what sends the next
     * page into the null tail instead of treating the trace as zero.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "tokens, 120, 0.5, 30, 120",
                "TOKENS, null, 0.5, 30, null",
                "cost, 120, 0.50, 30, 0.50",
                "cost, 120, null, 30, null",
                "latency, 120, 0.5, 30, 30",
                "latency, 120, 0.5, null, null",
                "when, 120, 0.5, 30, null",
                "null, 120, 0.5, 30, null"
            })
    void theCursorResumesFromTheLastRowsValueForTheSortColumn(
            @Nullable String sort,
            @Nullable Long tokens,
            @Nullable BigDecimal cost,
            @Nullable Long latency,
            @Nullable String expectedSortValue) {
        var last = summary("trace-b", "2026-08-12T10:00:01Z", tokens, cost, latency);
        var page = TracePageCodec.trim(
                List.of(
                        summary("trace-a", "2026-08-12T10:00:02Z", 1L, BigDecimal.ONE, 1L),
                        last,
                        summary("trace-c", "2026-08-12T10:00:00Z", 1L, BigDecimal.ONE, 1L)),
                2,
                sort);

        assertEquals(
                List.of("trace-a", "trace-b"),
                page.rows().stream().map(TraceV2Repository.Summary::id).toList());
        assertEquals(
                new TracePageCodec.Key(expectedSortValue, "2026-08-12T10:00:01Z", "trace-b"),
                TracePageCodec.decode(page.nextCursor()),
                "seeded from the last row of this page, not from the over-fetched one");
    }

    @Test
    void aTokenMissingItsTimeOrIdSlotRestartsAtTheNewestPage() {
        assertEquals(
                TracePageCodec.Key.NONE,
                TracePageCodec.decode(PreviewCursor.encode(
                        "v2" + TracePageCodec.SEP + "5" + TracePageCodec.SEP + "2026-08-12T10:00:00Z"
                                + TracePageCodec.SEP,
                        0)));
        assertEquals(
                TracePageCodec.Key.NONE,
                TracePageCodec.decode(PreviewCursor.encode(
                        "v2" + TracePageCodec.SEP + "5" + TracePageCodec.SEP + TracePageCodec.SEP + "trace-a", 0)));
    }

    private static TraceV2Repository.Summary summary(
            String id, String startedAt, @Nullable Long tokens, @Nullable BigDecimal cost, @Nullable Long latency) {
        return new TraceV2Repository.Summary(
                id, null, startedAt, null, latency, null, null, null, null, null, null, null, null, null, null, null,
                tokens, null, null, cost, null, true, null, null);
    }
}
