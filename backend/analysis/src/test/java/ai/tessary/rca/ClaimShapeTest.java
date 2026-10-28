// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingRow;
import org.junit.jupiter.api.Test;

class ClaimShapeTest {

    /**
     * A custom classifier stamps a kind of its own or none. The bug is such a finding getting no checks, or a shift's
     * checks.
     */
    @Test
    void anUnknownCauseKindIsARateRise() {
        FindingClaim claim = new FindingClaim(
                "f1",
                "proj-1",
                "custom-x",
                "cls-1:cs-1",
                FindingRow.SubjectKind.CLASSIFIER,
                "cls-1",
                null,
                null,
                "2026-10-01T00:00:00Z",
                "2026-10-02T00:00:00Z",
                null,
                null,
                null,
                1,
                "{\"version_id\":\"v1\"}",
                null,
                "2026-10-01T00:00:00Z");

        assertEquals(ClaimShape.RATE_RISE, ClaimShape.of("custom-x"));
        assertEquals(ClaimShape.RATE_RISE, ClaimShape.of(claim.causeKind()));
    }

    /**
     * A drift is a shift and an armed window is a presence event. The bug is a drift compared by flagged share, or a
     * secret leak compared against rows it never had.
     */
    @Test
    void theTwoNamedKindsHaveTheirOwnShapes() {
        assertEquals(ClaimShape.DISTRIBUTION_SHIFT, ClaimShape.of(FindingRow.Cause.DISTRIBUTION_SHIFT));
        assertEquals(ClaimShape.PRESENCE_EVENT, ClaimShape.of(FindingRow.Cause.ARMED_WINDOW));
    }
}
