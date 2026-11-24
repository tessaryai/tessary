// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The fact rows of one closed UTC day, as one fold writes them in place of the day's old rows. User keys are lower-case
 * hex.
 *
 * @param traces every settled trace of the day, with or without a user ID
 */
public record DayFacts(
        int day,
        int traces,
        List<IdDay> idDays,
        List<NullDay> nullDays,
        List<AliasEdgeRow> aliasEdges,
        List<EndUserRow> users) {

    static final int SLOTS = 96;

    /**
     * One {@code end_user_day} row. Hour {@code h} is bit {@code h} of {@code hourMask}. Slot {@code s} (the 15-minute
     * slot of the day) is bit {@code s} of {@code slotMaskLow} for {@code s < 64}, else bit {@code s - 64} of
     * {@code slotMaskHigh}, as in the judge's {@code Facts.IdDays}.
     *
     * @param sessionHashes the 32 smallest distinct {@code crc32(session id) & 0x7fffffff}, ascending
     * @param jobKeys the 32 smallest distinct {@code crc32(input_preview) << 7 | slot}, ascending
     */
    public record IdDay(
            String userKey,
            int routeNo,
            int versionNo,
            int traces,
            int tracesWithInput,
            int tracesWithParent,
            int hourMask,
            long slotMaskLow,
            int slotMaskHigh,
            List<Integer> sessionHashes,
            List<Long> jobKeys) {}

    /** One {@code end_user_day_null} row: all traces of a route and version, and those with no user ID. */
    public record NullDay(int routeNo, int versionNo, int traces, int tracesNoUserId) {}

    /** All traces of the day per route number, over every version. */
    public Map<Integer, Integer> tracesByRoute() {
        Map<Integer, Integer> byRoute = new TreeMap<>();
        nullDays.forEach(row -> byRoute.merge(row.routeNo(), row.traces(), Integer::sum));
        return byRoute;
    }

    /** The {@code bit(96)} text of a slot mask: slot 95 is the first character, slot 0 the last. */
    public static String slotMaskText(long low, int high) {
        StringBuilder bits = new StringBuilder(SLOTS);
        for (int slot = SLOTS - 1; slot >= 0; slot--) {
            boolean set = slot < 64 ? (low >>> slot & 1L) == 1L : (high >>> (slot - 64) & 1) == 1;
            bits.append(set ? '1' : '0');
        }
        return bits.toString();
    }

    /** Slots 0 to 63 of a {@code bit(96)} text written by {@link #slotMaskText}. */
    public static long slotMaskLow(String bits) {
        long low = 0;
        for (int slot = 0; slot < 64; slot++) {
            if (bits.charAt(SLOTS - 1 - slot) == '1') low |= 1L << slot;
        }
        return low;
    }

    /** Slots 64 to 95 of a {@code bit(96)} text written by {@link #slotMaskText}. */
    public static int slotMaskHigh(String bits) {
        int high = 0;
        for (int slot = 64; slot < SLOTS; slot++) {
            if (bits.charAt(SLOTS - 1 - slot) == '1') high |= 1 << (slot - 64);
        }
        return high;
    }
}
