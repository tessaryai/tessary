// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.finding.FindingEvidenceRepository.Ref;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The codec for a pinned reference window's evidence rows. The rows are history no query can re-derive, so
 * the bugs are a round trip that loses the span grain, and an unreadable blob that fails the sweep instead
 * of reading as no evidence.
 */
class MetricEvidenceRefsTest {

    @Test
    void aTraceRefAndASpanRefRoundTripAtTheirOwnGrain() {
        List<Ref> refs = List.of(Ref.trace("t-1"), Ref.span("t-2", "s-2"));

        assertEquals(refs, MetricEvidenceRefs.fromJson(MetricEvidenceRefs.toJson(refs)));
        assertNull(MetricEvidenceRefs.toJson(List.of()), "a window with no rows stores nothing, not []");
    }

    /** A row with no trace cannot become a finding_evidence row, and a blank span means trace grain. */
    @Test
    void aRowWithoutATraceIsDroppedAndABlankSpanReadsAsTraceGrain() {
        assertEquals(
                List.of(Ref.trace("t-1")),
                MetricEvidenceRefs.fromJson("[{\"s\":\"s-0\"},{\"t\":\"\"},{\"t\":\"t-1\",\"s\":\"\"}]"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "{not json", "{\"t\":\"t-1\"}"})
    void anAbsentOrUnreadableBlobReadsAsNoEvidence(@Nullable String blob) {
        assertEquals(List.of(), MetricEvidenceRefs.fromJson(blob));
    }
}
