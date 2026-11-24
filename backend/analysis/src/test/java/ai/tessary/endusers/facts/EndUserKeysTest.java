// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.JudgeConstants;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The salted user key and the day number are the join keys of every fact row: a key that drifts splits one user ID
 * into two, and a day off by one moves every trace of a day into its neighbour. The constants pin the spec table, so a
 * typo in one value cannot change a verdict unnoticed.
 */
class EndUserKeysTest {

    private static final byte[] SALT = filled(32, (byte) 0x5a);

    @Test
    void userKey_matchesRfc4231Case1() {
        assertArrayEquals(
                HexFormat.of().parseHex("b0344c61d8db38535ca8afceaf0bf12b"),
                EndUserKeys.userKey(filled(20, (byte) 0x0b), "Hi There"),
                "the key is the first 16 bytes of HMAC-SHA-256 over the UTF-8 ID with the salt as the HMAC key");
    }

    @Test
    void normalise_trimsAndMapsEmptyToNull() {
        byte[] none = new byte[16];
        assertAll(
                () -> assertArrayEquals(EndUserKeys.userKey(SALT, "a"), EndUserKeys.userKey(SALT, "  a ")),
                () -> assertArrayEquals(none, EndUserKeys.userKey(SALT, ""), "an empty ID is no ID"),
                () -> assertArrayEquals(none, EndUserKeys.userKey(SALT, "   "), "a blank ID is no ID"),
                () -> assertArrayEquals(none, EndUserKeys.userKey(SALT, null), "no ID uses the all-zero key"));
    }

    @Test
    void dayIndex_isUtcFloor() {
        assertAll(
                () -> assertEquals(20781, EndUserKeys.dayIndex(Instant.parse("2026-11-24T00:00:00Z"))),
                () -> assertEquals(20780, EndUserKeys.dayIndex(Instant.parse("2026-11-23T23:59:59Z"))));
    }

    @Test
    void constants_matchSpecTable() {
        assertAll(
                () -> assertEquals(0.6, JudgeConstants.STOP_CUT),
                () -> assertEquals(0.3, JudgeConstants.OVERDUE_FLOOR),
                () -> assertEquals(0.8, JudgeConstants.GRADE_CUT),
                () -> assertEquals(0.04, JudgeConstants.TAU),
                () -> assertEquals(60, JudgeConstants.NULL_DRAWS),
                () -> assertEquals(30, JudgeConstants.NULL_DRAWS_LARGE),
                () -> assertEquals(1500, JudgeConstants.NULL_DRAWS_LARGE_ABOVE_IDS),
                () -> assertEquals(1.6, JudgeConstants.PANEL_DAYS_PER_WEEK),
                () -> assertEquals(0.15, JudgeConstants.SILENCE_EXCESS),
                () -> assertEquals(28, JudgeConstants.HOLD_DAYS),
                () -> assertEquals(0.25, JudgeConstants.PAGE_AGGREGATE_ID_DAYS),
                () -> assertEquals(0.10, JudgeConstants.PAGE_COVERED_REFUSE),
                () -> assertEquals(0.90, JudgeConstants.PAGE_COVERED_PARTIAL),
                () -> assertEquals(0.2, JudgeConstants.PAGE_IDENT_SHARE_REFUSE),
                () -> assertEquals(0.7, JudgeConstants.PAGE_IDENT_SHARE_PARTIAL),
                () -> assertEquals(21, JudgeConstants.TOO_EARLY_DAYS),
                () -> assertEquals(20, JudgeConstants.TOO_EARLY_IDS),
                () -> assertEquals(120, JudgeConstants.HISTORY_FLOOR_DAYS));
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
