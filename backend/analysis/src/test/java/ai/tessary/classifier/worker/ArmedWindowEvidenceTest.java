// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.worker.ArmedWindowEvidence.ArmedWindowDetail;
import org.junit.jupiter.api.Test;

/**
 * {@link ArmedWindowEvidence#detail}: the summary R3 added for a classifier with no richer detail of
 * its own — frustration, groundedness, regex/threshold. Fixed against exactly the shape {@link
 * ClassifierArming#payload} writes.
 */
class ArmedWindowEvidenceTest {

    @Test
    void detail_readsTheBarClassifierArmingWrote() {
        String payload =
                "{\"cause_kind\":\"armed_window\",\"native_cause_key\":\"frustration\",\"basis\":\"event_count\","
                        + "\"observed\":7,\"threshold\":5,\"window_seconds\":86400,"
                        + "\"window_start\":\"2026-06-01T00:00:00Z\",\"window_end\":\"2026-06-02T00:00:00Z\"}";

        ArmedWindowDetail detail = ArmedWindowEvidence.detail(payload);

        assertNotNull(detail);
        assertEquals("event_count", detail.basis());
        assertEquals(7, detail.observed());
        assertEquals(5, detail.threshold());
        assertEquals(86_400, detail.windowSeconds());
        assertEquals("2026-06-01T00:00:00Z", detail.windowStart());
        assertEquals("2026-06-02T00:00:00Z", detail.windowEnd());
        assertNull(detail.confidence(), "no confidence band on a classifier that does not facet by band");
    }

    @Test
    void detail_carriesTheConfidenceBandWhenTheFacetedWriterSetOne() {
        String payload = "{\"cause_kind\":\"armed_window\",\"basis\":\"event_count\",\"observed\":2,"
                + "\"threshold\":1,\"window_seconds\":86400,\"confidence\":\"high\"}";

        ArmedWindowDetail detail = ArmedWindowEvidence.detail(payload);

        assertNotNull(detail);
        assertEquals("high", detail.confidence());
    }

    @Test
    void detail_isNullForAPayloadThatIsNotAnArmedWindow() {
        assertNull(ArmedWindowEvidence.detail("{\"cause_kind\":\"rate_shift\"}"));
        assertNull(ArmedWindowEvidence.detail(null));
        assertNull(ArmedWindowEvidence.detail(""));
        assertNull(ArmedWindowEvidence.detail("not json"));
    }
}
