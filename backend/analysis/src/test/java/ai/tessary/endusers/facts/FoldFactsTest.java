// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.model.AliasVia;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * One closed UTC day of traces folded into fact rows. Every number here is read again by the judge after the traces are
 * gone, so a wrong bit, a lost session or a wrong edge changes a judgment with nothing left to check it against.
 */
class FoldFactsTest {

    private static final byte[] SALT = new byte[32];
    private static final int DAY = 20514; // 2026-03-02

    @Test
    void sessionHashes_keep32Smallest() {
        List<FoldTrace> forty = IntStream.range(0, 40)
                .mapToObj(i -> trace("usr_1", String.format(Locale.ROOT, "s%02d", i), "chat.turn", at("10:%02d:00", i)))
                .toList();
        List<Integer> smallest32 = IntStream.range(0, 40)
                .mapToObj(i -> sessionHash(String.format(Locale.ROOT, "s%02d", i)))
                .sorted()
                .limit(32)
                .toList();

        List<FoldTrace> twoRoutes = new ArrayList<>();
        IntStream.range(0, 15).forEach(i -> twoRoutes.add(trace("usr_1", "a" + i, "chat.turn", at("09:%02d:00", i))));
        IntStream.range(0, 10).forEach(i -> twoRoutes.add(trace("usr_1", "b" + i, "agent.run", at("11:%02d:00", i))));
        Set<Integer> union = new HashSet<>();
        fold(twoRoutes).idDays().forEach(row -> union.addAll(row.sessionHashes()));

        assertAll(
                () -> assertEquals(smallest32, fold(forty).idDays().getFirst().sessionHashes()),
                () -> assertEquals(2, fold(twoRoutes).idDays().size()),
                () -> assertEquals(25, union.size(), "15 and 10 distinct sessions on two routes are 25 in all"));
    }

    @Test
    void aliasEdge_onlyWhenFirstIdEndsBeforeSecondStarts() {
        List<FoldTrace> handOver = List.of(
                trace("acct:x", "sa", "chat.turn", at("10:00:00")),
                trace("acct:x", "sa", "chat.turn", at("10:05:00")),
                trace("acct:y", "sa", "chat.turn", at("10:06:00")));
        List<FoldTrace> interleaved = List.of(
                trace("acct:x", "sb", "chat.turn", at("10:00:00")),
                trace("acct:y", "sb", "chat.turn", at("10:03:00")),
                trace("acct:x", "sb", "chat.turn", at("10:05:00")));

        assertAll(
                () -> assertEquals(
                        List.of(new AliasEdgeRow(key("acct:x"), key("acct:y"), AliasVia.SESSION, DAY)),
                        fold(handOver).aliasEdges()),
                () -> assertEquals(List.of(), fold(interleaved).aliasEdges()));
    }

    @Test
    void aliasEdge_belongsToTheDayTheSecondIdStarts_andThreadsLinkToo() {
        List<FoldTrace> acrossMidnight = List.of(
                new FoldTrace("acct:x", null, "th1", "chat.turn", null, null, second(DAY - 1, "23:00:00"), null, false),
                new FoldTrace("acct:y", null, "th1", "chat.turn", null, null, second(DAY, "01:00:00"), null, false));
        List<FoldTrace> dayTraces = List.of(acrossMidnight.get(1));

        assertAll(
                () -> assertEquals(
                        List.of(new AliasEdgeRow(key("acct:x"), key("acct:y"), AliasVia.THREAD, DAY)),
                        FoldFacts.build(DAY, dayTraces, acrossMidnight, SALT, t -> 1, t -> 1)
                                .aliasEdges()),
                () -> assertEquals(
                        List.of(),
                        FoldFacts.build(
                                        DAY - 1,
                                        List.of(acrossMidnight.getFirst()),
                                        acrossMidnight,
                                        SALT,
                                        t -> 1,
                                        t -> 1)
                                .aliasEdges(),
                        "the edge is written with the day the second ID starts, once"));
    }

    @Test
    void masks() {
        List<FoldTrace> traces = List.of(
                new FoldTrace("usr_1", "s1", null, "chat.turn", null, "v1", at("09:07:00"), "daily digest", false),
                new FoldTrace("usr_1", "s1", null, "chat.turn", null, "v1", at("10:00:00"), null, true),
                new FoldTrace("usr_1", "s1", null, "chat.turn", null, "v1", at("23:50:00"), "", false));

        assertEquals(
                List.of(new DayFacts.IdDay(
                        key("usr_1"),
                        1,
                        1,
                        3,
                        1,
                        1,
                        (1 << 9) | (1 << 10) | (1 << 23),
                        (1L << 36) | (1L << 40),
                        1 << 31,
                        List.of(sessionHash("s1")),
                        List.of(322_225_040_932L))),
                fold(traces).idDays(),
                "09:07 is hour 9 and slot 36; the job key is crc32(input) << 7 | 36; no input, no job key");
    }

    @Test
    void slotMask_textRoundTrips_withSlotZeroRightmost() {
        String text = DayFacts.slotMaskText((1L << 36) | 1L, 1 << 31);

        assertAll(
                () -> assertEquals(96, text.length()),
                () -> assertEquals('1', text.charAt(0), "slot 95 is the leftmost bit"),
                () -> assertEquals('1', text.charAt(95 - 36)),
                () -> assertEquals('1', text.charAt(95), "slot 0 is the rightmost bit"),
                () -> assertEquals(3, text.chars().filter(c -> c == '1').count()),
                () -> assertEquals((1L << 36) | 1L, DayFacts.slotMaskLow(text)),
                () -> assertEquals(1 << 31, DayFacts.slotMaskHigh(text)));
    }

    @Test
    void nullId_countsInNullDays_notInIdDays() {
        List<FoldTrace> traces = List.of(
                trace("  ", null, "chat.turn", at("11:00:00")),
                trace(null, null, "chat.turn", at("11:30:00")),
                trace("usr_7Hq2kD", "s1", "chat.turn", at("12:00:00")));

        DayFacts facts = fold(traces);

        assertAll(
                () -> assertEquals(3, facts.traces()),
                () -> assertEquals(List.of(new DayFacts.NullDay(1, 1, 3, 2)), facts.nullDays()),
                () -> assertEquals(1, facts.idDays().size()),
                () -> assertEquals(1, facts.idDays().getFirst().traces()),
                () -> assertEquals(
                        List.of(new EndUserRow(
                                "daac9c65a48cdd6b98d14e8573b331b6", "usr_7Hq2kD", "usr:10", null, DAY, DAY, false)),
                        facts.users(),
                        "a trace with no user ID makes no end_user row"));
    }

    @Test
    void users_carryTheClusterHash_andNoOtherReadablePartOfTheId() {
        DayFacts facts = fold(List.of(trace(" acme:u1 ", "s1", "chat.turn", at("08:00:00"))));

        assertEquals(
                List.of(new EndUserRow(
                        key("acme:u1"), "acme:u1", "acme:7", "c9a7e476bacbbf87e60c30ba2612595b", DAY, DAY, false)),
                facts.users());
    }

    /** Spec 3.1 {@code token_link}: reference {@code has_link(u, s)} over every session of the ID. */
    @Test
    void sessionTokenLink_everySessionOfTheDayHoldsAPieceOfTheId() {
        DayFacts facts = fold(List.of(
                trace("anon-3f9a2c71b8", "web:3f9a2c71:1", "chat.turn", at("08:00:00")),
                trace("anon-3f9a2c71b8", "app/9a2c71b8", "agent.run", at("08:10:00")),
                trace("anon-3f9a2c71b8", null, "chat.turn", at("08:20:00")),
                trace("acct-77d1e0aa", "web:77d1e0aa:1", "chat.turn", at("09:00:00")),
                trace("acct-77d1e0aa", "s-other", "chat.turn", at("09:05:00")),
                trace("usr_no_session", null, "chat.turn", at("10:00:00")),
                trace("usr_blank", "  ", "chat.turn", at("10:30:00"))));

        assertEquals(
                List.of("acct-77d1e0aa|false", "anon-3f9a2c71b8|true", "usr_blank|true", "usr_no_session|true"),
                facts.users().stream()
                        .map(u -> u.idText() + "|" + u.sessionTokenLink())
                        .sorted()
                        .toList(),
                "one session without a piece of the ID clears it; an ID with no session keeps all() of nothing");
    }

    @Test
    void hasLink_portsTheReference() {
        assertAll(
                () -> assertTrue(FoldFacts.hasLink("abc", "abc"), "an ID equal to its session links at any length"),
                () -> assertFalse(FoldFacts.hasLink("abcdefg", "xabcdefgx"), "7 characters are too few"),
                () -> assertTrue(FoldFacts.hasLink("abcdefgh", "xxabcdefghxx")),
                () -> assertTrue(FoldFacts.hasLink("0123456789", "s-23456789"), "the last 8 characters"),
                () -> assertFalse(FoldFacts.hasLink("0123456789", "s-1234567")),
                () -> assertFalse(
                        FoldFacts.hasLink("ab\uD83D\uDE00cdef", "s-ab\uD83D\uDE00cdef-1"),
                        "7 code points in 8 UTF-16 units are too few"),
                () -> assertTrue(
                        FoldFacts.hasLink("ab\uD83D\uDE00cdefgh", "s-b\uD83D\uDE00cdefgh-1"),
                        "the second piece of 8 code points"),
                () -> assertFalse(
                        FoldFacts.hasLink("ab\uD83D\uDE00cdefgh", "x\uD83D\uDE00cdefghx"),
                        "8 UTF-16 units that are 7 code points are no piece"));
    }

    private static DayFacts fold(List<FoldTrace> traces) {
        return FoldFacts.build(DAY, traces, traces, SALT, t -> "agent.run".equals(t.name()) ? 2 : 1, t -> 1);
    }

    private static FoldTrace trace(@Nullable String userId, @Nullable String sessionId, String name, long second) {
        return new FoldTrace(userId, sessionId, null, name, null, null, second, null, false);
    }

    private static long at(String time, Object... args) {
        return second(DAY, String.format(Locale.ROOT, time, args));
    }

    private static long second(int day, String time) {
        return Instant.parse("1970-01-01T" + time + "Z").getEpochSecond() + day * 86_400L;
    }

    private static int sessionHash(String sessionId) {
        CRC32 crc = new CRC32();
        crc.update(sessionId.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() & 0x7FFF_FFFFL);
    }

    private static String key(String id) {
        return HexFormat.of().formatHex(EndUserKeys.userKey(SALT, id));
    }
}
