// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.finding.FindingEvidenceRepository.SpanRef;
import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceSidesTest {

    private static final List<SpanRef> TOOL_ERROR_ROWS = List.of(
            SpanRefs.row(Role.WITNESS).span("t1", "sp1").build(),
            SpanRefs.row(Role.MEMBER).span("t1", "sp1").build(),
            SpanRefs.row(Role.MEMBER).span("t2", "sp2").build());

    /**
     * A rate classifier writes every call as {@code member} and the failures again as {@code witness}. The bugs are a
     * rate classifier with no comparison side, and healthy members counted as flagged.
     */
    @Test
    void unflaggedMembersAreTheComparisonForRateClassifiers() {
        EvidenceSides sides = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(TOOL_ERROR_ROWS));

        assertEquals(EvidenceSides.Side.COMPARISON, sides.sideOf(Role.MEMBER));
        assertEquals(Set.of("t1"), sides.flaggedTraces());
        assertEquals(Set.of("t2"), sides.comparisonTraces());
        assertEquals(1, sides.flaggedCount());
    }

    /**
     * A row that is flagged stays flagged, whatever other role names it. The bug is a flagged row read as healthy,
     * which makes its cause look explained by normal traffic.
     */
    @Test
    void flaggedMembersNeverJoinTheComparisonSide() {
        EvidenceSides rate = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(TOOL_ERROR_ROWS));
        assertEquals(Set.of("t2"), rate.comparisonTraces());
        assertEquals(
                List.of(TOOL_ERROR_ROWS.get(2)),
                rate.split(TOOL_ERROR_ROWS).comparison(),
                "the failing call's own member row is not a healthy call");

        List<SpanRef> drift = List.of(
                SpanRefs.row(Role.MEMBER).trace("t2").build(),
                SpanRefs.row(Role.BASELINE).trace("t2").build(),
                SpanRefs.row(Role.BASELINE).trace("t3").build());
        EvidenceSides shift = EvidenceSides.of(ClaimShape.DISTRIBUTION_SHIFT, SpanRefs.recorded(drift));
        assertEquals(EvidenceSides.Side.FLAGGED, shift.sideOf(Role.MEMBER));
        assertEquals(Set.of("t3"), shift.comparisonTraces());
    }

    /** The bug is a presence event compared against its own window. */
    @Test
    void aPresenceEventHasNoComparisonSide() {
        EvidenceSides sides = EvidenceSides.of(ClaimShape.PRESENCE_EVENT, SpanRefs.recorded(TOOL_ERROR_ROWS));

        assertEquals(Set.of(), sides.comparisonTraces());
        assertEquals(EvidenceSides.Side.NONE, sides.sideOf(Role.MEMBER));
        assertEquals(List.of(Role.WITNESS), sides.readOrder());
    }

    /**
     * Flagged roles are read before comparison roles, so a row ceiling cuts healthy rows first. The bug is the
     * repository's role-string order, which reads {@code baseline} and {@code member} before {@code witness}.
     */
    @Test
    void flaggedRolesAreReadFirst() {
        List<SpanRef> rows = List.of(
                SpanRefs.row(Role.BASELINE).trace("t0").build(),
                SpanRefs.row(Role.MEMBER).trace("t2").build(),
                SpanRefs.row(Role.WITNESS).trace("t1").build(),
                SpanRefs.row(Role.CHANGEPOINT).trace("t1").build());

        EvidenceSides sides = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(rows));

        assertEquals(List.of(Role.WITNESS, Role.MEMBER, Role.BASELINE), sides.readOrder());
    }
}
