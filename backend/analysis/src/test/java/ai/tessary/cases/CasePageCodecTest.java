// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.cases.CaseRepository.PageKey;
import ai.tessary.cases.CaseRepository.PageOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The case cursor. Every test here is about a token the server should REFUSE to resume from, because that is
 * the whole risk in a keyset cursor: resuming from a point that is not on the current ordering returns rows
 * that look like a page and are not, and nothing downstream can tell.
 */
class CasePageCodecTest {

    @Test
    void aCursorResumesFromTheLastRowOfThePageItWasMintedFrom() {
        // Three rows for a page of two: the extra row proves there is a next page, and the cursor must be
        // seeded from row 2 — seeding from row 3 would skip it, since row 3 IS the next page's first row.
        CasePageCodec.Page page = CasePageCodec.trim(
                List.of(
                        row("a", 0.9, "2026-08-03T00:00:00Z"),
                        row("b", 0.5, "2026-08-02T00:00:00Z"),
                        row("c", 0.1, "2026-08-01T00:00:00Z")),
                2,
                PageOrder.LIVE_RANK);

        assertEquals(2, page.rows().size());
        PageKey resumed = CasePageCodec.decode(page.nextCursor(), PageOrder.LIVE_RANK);
        assertNotNull(resumed);
        assertEquals("b", resumed.id());
        assertEquals(0.5, resumed.severity());
        assertEquals("2026-08-02T00:00:00Z", resumed.at());
    }

    @Test
    void aLastPageMintsNoCursor() {
        CasePageCodec.Page page =
                CasePageCodec.trim(List.of(row("a", 0.9, "2026-08-03T00:00:00Z")), 2, PageOrder.LIVE_RANK);

        assertEquals(1, page.rows().size());
        assertNull(page.nextCursor(), "there is no next page to point at");
    }

    /**
     * <b>The one that matters.</b> A cursor minted on {@code state=open} names a point on
     * {@code (severity, opened_at, id)}; replayed against {@code state=resolved} the query ranks by
     * {@code (resolved_at, id)}, so that point is not on the ordering at all and resuming from it would
     * compare an {@code opened_at} against a {@code resolved_at} — an arbitrary slice that reads like page 2.
     * The order is stamped into the token so the mismatch is detectable, and a mismatch restarts at page one.
     */
    @Test
    void aCursorReplayedAgainstTheOtherOrderIsDiscardedRatherThanMisResumed() {
        CasePageCodec.Page live = CasePageCodec.trim(
                List.of(row("a", 0.9, "2026-08-03T00:00:00Z"), row("b", 0.5, "2026-08-02T00:00:00Z")),
                1,
                PageOrder.LIVE_RANK);

        assertNotNull(CasePageCodec.decode(live.nextCursor(), PageOrder.LIVE_RANK));
        assertNull(
                CasePageCodec.decode(live.nextCursor(), PageOrder.RESOLVED_RECENT),
                "a live-ranked cursor says nothing about where a resolved page resumes");
    }

    @Test
    void theResolvedCursorCarriesTheClosureStampAndNoSeverity() {
        CasePageCodec.Page page = CasePageCodec.trim(
                List.of(resolved("a", "2026-08-06T00:00:00Z"), resolved("b", "2026-08-05T00:00:00Z")),
                1,
                PageOrder.RESOLVED_RECENT);

        PageKey resumed = CasePageCodec.decode(page.nextCursor(), PageOrder.RESOLVED_RECENT);
        assertNotNull(resumed);
        assertEquals("a", resumed.id());
        assertEquals("2026-08-06T00:00:00Z", resumed.at());
        // Not zero. This order does not rank by severity, and a bound on a column the query never reads
        // would be a filter nobody asked for.
        assertNull(resumed.severity());
    }

    @Test
    void anUnreadableTokenStartsAgainAtPageOne() {
        assertNull(CasePageCodec.decode(null, PageOrder.LIVE_RANK));
        assertNull(CasePageCodec.decode("", PageOrder.LIVE_RANK));
        assertNull(CasePageCodec.decode("not-base64-at-all!!", PageOrder.LIVE_RANK));
        // Well-formed base64 of the wrong shape — the failure mode a hand-written token actually takes.
        assertNull(CasePageCodec.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString("0\nv1".getBytes(StandardCharsets.UTF_8)),
                PageOrder.LIVE_RANK));
    }

    // ------------------------------------------------------------------ helpers

    private static CaseRow row(String id, double severity, String openedAt) {
        return sample(id, severity, openedAt, null);
    }

    private static CaseRow resolved(String id, String resolvedAt) {
        return sample(id, 0.4, "2026-08-01T00:00:00Z", resolvedAt);
    }

    private static CaseRow sample(String id, double severity, String openedAt, @Nullable String resolvedAt) {
        return new CaseRow(
                id,
                "proj-1",
                1L,
                CaseRow.Detector.BEHAVIOR_DRIFT,
                CaseRow.SubjectKind.CLASSIFIER,
                "subject-" + id,
                "Subject " + id,
                "cs-1",
                "pass_rate",
                "find-" + id,
                resolvedAt == null ? CaseRow.State.OPEN : CaseRow.State.RESOLVED,
                "Something fell",
                "Sustained drop against its own baseline.",
                severity,
                "2026-07-01T10:00:00Z",
                0.55,
                0.95,
                -0.4,
                openedAt,
                openedAt,
                resolvedAt,
                resolvedAt == null ? null : CaseRow.Resolution.RECOVERED,
                null,
                null,
                null,
                null,
                openedAt);
    }
}
