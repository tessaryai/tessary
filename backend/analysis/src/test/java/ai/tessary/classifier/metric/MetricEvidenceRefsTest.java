// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.finding.FindingEvidenceRepository.Ref;
import ai.tessary.classifier.metric.MetricEvidenceRefs.Timed;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The codec for a window's evidence rows. The rows are history no query can re-derive, so the bugs are a round trip
 * that loses the span grain, the event time or the value the change point is located from, and an unreadable blob that
 * fails the sweep instead of reading as no evidence.
 */
class MetricEvidenceRefsTest {

    @Test
    void aTraceRefAndASpanRefRoundTripAtTheirOwnGrain() {
        List<Timed> refs = List.of(
                new Timed(Ref.trace("t-1"), "2026-06-01T00:00:00Z", 7.6009024595420822),
                new Timed(Ref.span("t-2", "s-2"), "2026-06-01T00:00:01.250Z", 6.907755278982137));

        assertEquals(refs, MetricEvidenceRefs.fromJson(MetricEvidenceRefs.toJson(refs)));
        assertNull(MetricEvidenceRefs.toJson(List.of()), "a window with no rows stores nothing, not []");
    }

    /** A row with no trace cannot become a finding_evidence row, and a blank span means trace grain. */
    @Test
    void aRowWithoutATraceIsDroppedAndABlankSpanReadsAsTraceGrain() {
        assertEquals(
                List.of(Timed.of(Ref.trace("t-1"))),
                MetricEvidenceRefs.fromJson("[{\"s\":\"s-0\"},{\"t\":\"\"},{\"t\":\"t-1\",\"s\":\"\"}]"));
    }

    /**
     * A blob written before rows carried a time and a value, or one with a time and no value, reads with the missing
     * half null, so the sweep neither throws nor invents a value for the change point.
     */
    @Test
    void readsBlobsWithoutValues() {
        List<Timed> read = MetricEvidenceRefs.fromJson(
                "[{\"t\":\"t-1\"},{\"t\":\"t-2\",\"s\":\"s-2\",\"a\":\"2026-06-01T00:00:10Z\"}]");

        assertEquals(
                List.of(
                        new Timed(Ref.trace("t-1"), null, null),
                        new Timed(Ref.span("t-2", "s-2"), "2026-06-01T00:00:10Z", null)),
                read);
        assertFalse(read.get(0).valued());
        assertFalse(read.get(1).valued());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "{not json", "{\"t\":\"t-1\"}"})
    void anAbsentOrUnreadableBlobReadsAsNoEvidence(@Nullable String blob) {
        assertEquals(List.of(), MetricEvidenceRefs.fromJson(blob));
    }
}
