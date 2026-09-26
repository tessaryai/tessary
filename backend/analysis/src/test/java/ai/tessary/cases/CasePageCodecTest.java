// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.cases.CaseRepository.PageKey;
import ai.tessary.cases.CaseRepository.PageOrder;
import ai.tessary.ingest.PreviewCursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The case cursor: every test is a token the server must refuse to resume from, since resuming off the current
 * ordering returns rows that look like a page and are not.
 */
class CasePageCodecTest {

    /**
     * A cursor minted on {@code state=open} names a point on {@code (severity, opened_at, id)}; against {@code
     * state=resolved} ({@code (resolved_at, id)}) it would compare opened_at to resolved_at. The order is stamped
     * into the token, and a mismatch restarts at page one.
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
        // Not zero: this order does not rank by severity.
        assertNull(resumed.severity());
    }

    @Test
    void anUnreadableTokenStartsAgainAtPageOne() {
        assertNull(CasePageCodec.decode(null, PageOrder.LIVE_RANK));
        assertNull(CasePageCodec.decode("", PageOrder.LIVE_RANK));
        assertNull(CasePageCodec.decode("not-base64-at-all!!", PageOrder.LIVE_RANK));
        // Well-formed base64 of the wrong shape, as a hand-written token takes.
        assertNull(CasePageCodec.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString("0\nv1".getBytes(StandardCharsets.UTF_8)),
                PageOrder.LIVE_RANK));
    }

    /**
     * A token for this order naming no point on it (non-numeric severity, a live token with none, a missing stamp or
     * id) restarts at page one.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "LIVE_RANK|high|2026-08-02T00:00:00Z|b",
                "LIVE_RANK||2026-08-02T00:00:00Z|b",
                "LIVE_RANK|0.5||b",
                "RESOLVED_RECENT||2026-08-02T00:00:00Z|",
            })
    void aTokenThatNamesNoPointOnItsOrderStartsAgainAtPageOne(
            PageOrder order, @Nullable String severity, @Nullable String at, @Nullable String id) {
        assertNull(CasePageCodec.decode(token(order, severity, at, id), order));
    }

    /** The resolved order has no severity, so a token without one is a real point. */
    @Test
    void aResolvedTokenWithoutASeverityResumes() {
        assertEquals(
                new PageKey(null, "2026-08-02T00:00:00Z", "b"),
                CasePageCodec.decode(
                        token(PageOrder.RESOLVED_RECENT, null, "2026-08-02T00:00:00Z", "b"),
                        PageOrder.RESOLVED_RECENT));
    }

    private static String token(PageOrder order, @Nullable String severity, @Nullable String at, @Nullable String id) {
        String sep = "\u001f";
        return PreviewCursor.encode("v1" + sep + order.name() + sep + nz(severity) + sep + nz(at) + sep + nz(id), 0);
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static CaseRow row(String id, double severity, String openedAt) {
        return sample(id, severity, openedAt, null);
    }

    private static CaseRow resolved(String id, String resolvedAt) {
        return sample(id, 0.4, "2026-08-01T00:00:00Z", resolvedAt);
    }

    private static CaseRow sample(String id, double severity, String openedAt, @Nullable String resolvedAt) {
        return new CaseRow(
                id,
                1L,
                CaseRow.Detector.CLASSIFIER,
                CaseRow.SubjectKind.CLASSIFIER,
                "subject-" + id,
                "Subject " + id,
                "cs-1",
                "pass_rate",
                1L,
                "find-" + id,
                resolvedAt == null ? CaseRow.State.OPEN : CaseRow.State.RESOLVED,
                null,
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
                resolvedAt == null ? null : CaseRow.Resolution.ABSORBED,
                null,
                null,
                null,
                null,
                null);
    }
}
