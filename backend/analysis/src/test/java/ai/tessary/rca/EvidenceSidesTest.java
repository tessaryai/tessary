// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        // Frustration's turn rows, each naming its session: A calm at 09:40, A flagged at 10:20, D calm at 10:50.
        List<SpanRef> turns = List.of(
                SpanRefs.row(Role.MEMBER).trace("a-0940").session("A").build(),
                SpanRefs.row(Role.WITNESS).trace("a-1020").session("A").build(),
                SpanRefs.row(Role.MEMBER).trace("a-1020").session("A").build(),
                SpanRefs.row(Role.MEMBER).trace("d-1050").session("D").build());
        EvidenceSides frustration = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(turns));
        assertEquals(EvidenceSides.TRACES, frustration.grain(), "a turn naming its session is a trace row");
        assertEquals(Set.of("a-1020"), frustration.flaggedTraces());
        assertEquals(
                Set.of("a-0940", "d-1050"),
                frustration.comparisonTraces(),
                "a calm hour of a session flagged in another hour is a comparison turn");
        assertEquals(Set.of("D"), frustration.comparisonSessions());
        assertEquals(1, frustration.flaggedCount());
    }

    /**
     * A row that is flagged stays flagged, whatever other role names it. The bug is a flagged row read as healthy,
     * which makes its cause look explained by normal traffic. A shift's member row that is also a baseline row is the
     * one exception: it is a healthy row just before the change point, written to both roles.
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
        assertEquals(Set.of("t2", "t3"), shift.comparisonTraces());
        assertEquals(Set.of(), shift.flaggedTraces());
        assertEquals(List.of(drift.get(0), drift.get(2)), shift.split(drift).comparison());
    }

    /**
     * A pinned drift whose reference a person moved: the pinned rows aged out, so its only baseline rows are the ten
     * window rows just before the change point, which are members too. The bugs are those rows read as flagged and
     * citable, and the finding read as having no comparison side.
     */
    @Test
    void aMovedPinComparesAgainstTheRowsBeforeTheStep() {
        List<SpanRef> rows = List.of(
                SpanRefs.row(Role.MEMBER).trace("b1").build(),
                SpanRefs.row(Role.MEMBER).trace("b2").build(),
                SpanRefs.row(Role.MEMBER).trace("a1").build(),
                SpanRefs.row(Role.MEMBER).trace("a2").build(),
                SpanRefs.row(Role.BASELINE).trace("b1").build(),
                SpanRefs.row(Role.BASELINE).trace("b2").build(),
                SpanRefs.row(Role.CHANGEPOINT).trace("a1").build());

        EvidenceSides sides = EvidenceSides.of(ClaimShape.DISTRIBUTION_SHIFT, SpanRefs.recorded(rows));

        assertEquals(Set.of("a1", "a2"), sides.flaggedTraces());
        assertEquals(Set.of("b1", "b2"), sides.comparisonTraces());
        assertTrue(sides.baselinePresent());
        assertEquals(2, sides.flaggedCount());
        EvidenceSides.Split split = sides.split(rows);
        assertEquals(List.of(rows.get(2), rows.get(3)), split.flagged());
        assertEquals(List.of(rows.get(0), rows.get(1)), split.comparison());
    }

    /**
     * A classifier that writes session rows flags a conversation with a session row and a trace row for its flagged
     * turn, and its members are sessions, as frustration findings closed before turn rows still hold; groundedness
     * flags an answer with a trace row and a span row, and its members are traces. The bug is
     * both rows counted on the flagged side, so it holds two rows per unit against one on the comparison side.
     */
    @Test
    void theFlaggedSideHoldsOneRowPerFlaggedUnit() {
        List<SpanRef> frustration = List.of(
                SpanRefs.row(Role.WITNESS).session("s1").build(),
                SpanRefs.row(Role.WITNESS).trace("t1").session("s1").build(),
                SpanRefs.row(Role.MEMBER).session("s1").build(),
                SpanRefs.row(Role.MEMBER).session("s2").build());
        EvidenceSides.Split sessions = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(frustration))
                .split(frustration);
        assertEquals(List.of(frustration.get(0)), sessions.flagged());
        assertEquals(List.of(frustration.get(3)), sessions.comparison());

        List<SpanRef> groundedness = List.of(
                SpanRefs.row(Role.WITNESS).trace("t1").build(),
                SpanRefs.row(Role.WITNESS).span("t1", "sp1").build(),
                SpanRefs.row(Role.MEMBER).trace("t1").build(),
                SpanRefs.row(Role.MEMBER).trace("t2").build());
        EvidenceSides.Split traces = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(groundedness))
                .split(groundedness);
        assertEquals(List.of(groundedness.get(0)), traces.flagged());
        assertEquals(List.of(groundedness.get(3)), traces.comparison());
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

    /** Only the roles the finding recorded are present. The bug is a role with no rows reported as present. */
    @Test
    void theRolesPresentAreTheRolesRecorded() {
        EvidenceSides sides = EvidenceSides.of(ClaimShape.RATE_RISE, SpanRefs.recorded(TOOL_ERROR_ROWS));

        assertEquals(Set.of(Role.WITNESS, Role.MEMBER), sides.rolesPresent());
        assertEquals(3, sides.recordedRows());
    }
}
