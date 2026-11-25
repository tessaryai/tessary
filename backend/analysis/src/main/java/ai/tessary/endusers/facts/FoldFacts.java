// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.endusers.model.AliasVia;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;

/**
 * Folds the settled traces of one closed UTC day into its fact rows (plan 3.1), with the fact contract of the reference
 * {@code fold.py}: whole epoch seconds, trimmed IDs, no row for a trace with no user ID outside the null-day counts.
 */
public final class FoldFacts {

    static final int CAP = 32;
    static final int TOKEN_PIECE = 8;

    private static final int SECONDS_PER_DAY = 86_400;
    private static final int SECONDS_PER_HOUR = 3_600;
    private static final int SECONDS_PER_SLOT = 900;
    private static final int SLOT_BITS = 7;
    private static final Comparator<AliasEdgeRow> EDGE_ORDER = Comparator.comparing(AliasEdgeRow::fromKey)
            .thenComparing(AliasEdgeRow::toKey)
            .thenComparing(AliasEdgeRow::via);

    private FoldFacts() {}

    /**
     * @param dayTraces the settled traces that start on {@code day}
     * @param groupTraces the traces, with a user ID, of the sessions and threads that hold a user ID on {@code day},
     *     over the days around it; only edges whose second ID starts on {@code day} are kept
     */
    public static DayFacts build(
            int day,
            List<FoldTrace> dayTraces,
            List<FoldTrace> groupTraces,
            byte[] salt,
            ToIntFunction<FoldTrace> routeNo,
            ToIntFunction<FoldTrace> versionNo) {
        Keys keys = new Keys(salt);
        Map<RowKey, Row> rows = new TreeMap<>();
        Map<RouteVersion, int[]> nulls = new TreeMap<>();
        Map<String, EndUserRow> users = new TreeMap<>();
        Map<String, Boolean> tokenLinks = new HashMap<>();
        for (FoldTrace t : dayTraces) {
            int route = routeNo.applyAsInt(t);
            int version = versionNo.applyAsInt(t);
            String id = EndUserKeys.normalise(t.userId());
            int[] counts = nulls.computeIfAbsent(new RouteVersion(route, version), k -> new int[2]);
            counts[0]++;
            if (id == null) {
                counts[1]++;
                continue;
            }
            String key = keys.of(id);
            users.computeIfAbsent(key, k -> user(k, id, salt, day));
            String session = EndUserKeys.normalise(t.sessionId());
            if (session != null) tokenLinks.merge(key, hasLink(id, session), Boolean::logicalAnd);
            rows.computeIfAbsent(new RowKey(key, route, version), k -> new Row())
                    .add(t);
        }
        tokenLinks.forEach((key, link) -> users.computeIfPresent(key, (k, u) -> link ? u : withoutTokenLink(u)));
        List<DayFacts.IdDay> idDays = new ArrayList<>(rows.size());
        rows.forEach((k, row) -> idDays.add(row.toIdDay(k)));
        List<DayFacts.NullDay> nullDays = new ArrayList<>(nulls.size());
        nulls.forEach((k, c) -> nullDays.add(new DayFacts.NullDay(k.routeNo(), k.versionNo(), c[0], c[1])));
        return new DayFacts(
                day,
                dayTraces.size(),
                List.copyOf(idDays),
                List.copyOf(nullDays),
                aliasEdges(day, groupTraces, keys),
                List.copyOf(users.values()));
    }

    private static List<AliasEdgeRow> aliasEdges(int day, List<FoldTrace> groupTraces, Keys keys) {
        List<FoldTrace> inTime = new ArrayList<>(groupTraces);
        inTime.sort(Comparator.comparingLong(FoldTrace::startedSecond));
        Map<String, Map<String, long[]>> bySession = new HashMap<>();
        Map<String, Map<String, long[]>> byThread = new HashMap<>();
        for (FoldTrace t : inTime) {
            String id = EndUserKeys.normalise(t.userId());
            if (id == null) continue;
            see(bySession, EndUserKeys.normalise(t.sessionId()), id, t.startedSecond());
            see(byThread, EndUserKeys.normalise(t.threadId()), id, t.startedSecond());
        }
        SortedSet<AliasEdgeRow> edges = new TreeSet<>(EDGE_ORDER);
        link(day, bySession, AliasVia.SESSION, keys, edges);
        link(day, byThread, AliasVia.THREAD, keys, edges);
        return List.copyOf(edges);
    }

    private static void see(Map<String, Map<String, long[]>> groups, @Nullable String group, String id, long second) {
        if (group == null) return;
        groups.computeIfAbsent(group, g -> new LinkedHashMap<>())
                .computeIfAbsent(id, u -> new long[] {second, second})[1] = second;
    }

    private static void link(
            int day, Map<String, Map<String, long[]>> groups, AliasVia via, Keys keys, SortedSet<AliasEdgeRow> edges) {
        for (Map<String, long[]> ids : groups.values()) {
            if (ids.size() != 2) continue;
            List<Map.Entry<String, long[]>> pair = new ArrayList<>(ids.entrySet());
            pair.sort(Comparator.comparingLong(e -> e.getValue()[0]));
            long[] first = pair.get(0).getValue();
            long[] second = pair.get(1).getValue();
            if (first[1] < second[0] && Math.floorDiv(second[0], SECONDS_PER_DAY) == day) {
                edges.add(new AliasEdgeRow(
                        keys.of(pair.get(0).getKey()), keys.of(pair.get(1).getKey()), via, day));
            }
        }
    }

    private static EndUserRow user(String key, String id, byte[] salt, int day) {
        String cluster = ShapeSignature.clusterOf(id);
        String clusterHash =
                cluster == null ? null : HexFormat.of().formatHex(ShapeSignature.clusterHash(salt, cluster));
        return new EndUserRow(key, id, ShapeSignature.of(id).wire(), clusterHash, day, day, true);
    }

    private static EndUserRow withoutTokenLink(EndUserRow u) {
        return new EndUserRow(
                u.userKey(), u.idText(), u.shapeSignature(), u.clusterHash(), u.firstDay(), u.lastDay(), false);
    }

    /**
     * Reference {@code has_link(u, s)}: the ID equals the session ID, or one of its 8-character pieces is in the session
     * ID. Characters are code points, as in the reference's Python strings.
     */
    static boolean hasLink(String id, String sessionId) {
        if (id.equals(sessionId)) return true;
        int length = id.codePointCount(0, id.length());
        for (int i = 0, start = 0; i + TOKEN_PIECE <= length; i++, start = id.offsetByCodePoints(start, 1)) {
            if (sessionId.contains(id.substring(start, id.offsetByCodePoints(start, TOKEN_PIECE)))) return true;
        }
        return false;
    }

    private static long crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private record RowKey(String userKey, int routeNo, int versionNo) implements Comparable<RowKey> {
        @Override
        public int compareTo(RowKey o) {
            int byKey = userKey.compareTo(o.userKey);
            if (byKey != 0) return byKey;
            return routeNo != o.routeNo ? Integer.compare(routeNo, o.routeNo) : Integer.compare(versionNo, o.versionNo);
        }
    }

    private record RouteVersion(int routeNo, int versionNo) implements Comparable<RouteVersion> {
        @Override
        public int compareTo(RouteVersion o) {
            return routeNo != o.routeNo ? Integer.compare(routeNo, o.routeNo) : Integer.compare(versionNo, o.versionNo);
        }
    }

    private static final class Keys {
        private final byte[] salt;
        private final Map<String, String> byId = new HashMap<>();

        Keys(byte[] salt) {
            this.salt = salt;
        }

        String of(String id) {
            return byId.computeIfAbsent(id, u -> HexFormat.of().formatHex(EndUserKeys.userKey(salt, u)));
        }
    }

    private static final class Row {
        private int traces;
        private int withInput;
        private int withParent;
        private int hourMask;
        private long slotMaskLow;
        private int slotMaskHigh;
        private final TreeSet<Integer> sessions = new TreeSet<>();
        private final TreeSet<Long> jobs = new TreeSet<>();

        void add(FoldTrace t) {
            int secondOfDay = (int) Math.floorMod(t.startedSecond(), (long) SECONDS_PER_DAY);
            int slot = secondOfDay / SECONDS_PER_SLOT;
            traces++;
            hourMask |= 1 << (secondOfDay / SECONDS_PER_HOUR);
            if (slot < 64) {
                slotMaskLow |= 1L << slot;
            } else {
                slotMaskHigh |= 1 << (slot - 64);
            }
            if (t.hasParent()) withParent++;
            String input = t.inputPreview();
            if (input != null && !input.isEmpty()) {
                withInput++;
                jobs.add(crc32(input) << SLOT_BITS | slot);
            }
            String session = EndUserKeys.normalise(t.sessionId());
            if (session != null) sessions.add((int) (crc32(session) & 0x7FFF_FFFFL));
        }

        DayFacts.IdDay toIdDay(RowKey k) {
            return new DayFacts.IdDay(
                    k.userKey(),
                    k.routeNo(),
                    k.versionNo(),
                    traces,
                    withInput,
                    withParent,
                    hourMask,
                    slotMaskLow,
                    slotMaskHigh,
                    sessions.stream().limit(CAP).toList(),
                    jobs.stream().limit(CAP).toList());
        }
    }
}
