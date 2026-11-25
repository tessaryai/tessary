// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CO_FIRING_SHARE;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Guards;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Findings that fire together (level 2 guard {@code findings-fire-together}): two shown issues whose hits share 50% or
 * more of the smaller issue's hit traces are one group, and a group joins every issue linked to it. The facts keep refs
 * per user ID, day and route, not per trace, so the traces two issues share on such a cell are read as the smaller of
 * their two ref counts. Refs with no user ID are in no hit set. Both issues keep their own numbers; the data cannot split
 * their effect.
 */
public final class CoFiring {

    private CoFiring() {}

    private record HitCell(int id, int day, @Nullable Integer route) {}

    /** @return lineage key to its group, the first lineage key of the group; issues in no group are not in the map */
    public static Map<String, String> groups(Facts facts, Set<String> shown, Guards guards) {
        if (!guards.findingsFireTogether()) return Map.of();
        Map<String, Map<HitCell, Integer>> refs = new TreeMap<>();
        for (HitRow row : facts.hits()) {
            Integer id = row.id();
            String key = facts.issues().get(row.issue()).lineageKey();
            if (id == null || !shown.contains(key)) continue;
            refs.computeIfAbsent(key, k -> new HashMap<>())
                    .merge(new HitCell(id, row.day(), row.routeNo()), row.refs(), Integer::sum);
        }
        List<String> keys = new ArrayList<>(refs.keySet());
        Map<String, String> parent = new HashMap<>();
        keys.forEach(k -> parent.put(k, k));
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (fireTogether(refs.get(keys.get(i)), refs.get(keys.get(j)))) {
                    String a = root(parent, keys.get(i));
                    String b = root(parent, keys.get(j));
                    if (a.compareTo(b) < 0) parent.put(b, a);
                    else parent.put(a, b);
                }
            }
        }
        Map<String, List<String>> members = new TreeMap<>();
        keys.forEach(k ->
                members.computeIfAbsent(root(parent, k), r -> new ArrayList<>()).add(k));
        Map<String, String> out = new TreeMap<>();
        members.forEach((root, group) -> {
            if (group.size() > 1) group.forEach(k -> out.put(k, root));
        });
        return out;
    }

    private static boolean fireTogether(Map<HitCell, Integer> a, Map<HitCell, Integer> b) {
        Map<HitCell, Integer> small = a.size() <= b.size() ? a : b;
        Map<HitCell, Integer> large = small == a ? b : a;
        long shared = 0;
        for (Map.Entry<HitCell, Integer> e : small.entrySet()) {
            shared += Math.min(e.getValue(), large.getOrDefault(e.getKey(), 0));
        }
        return shared >= CO_FIRING_SHARE * Math.min(total(a), total(b));
    }

    private static long total(Map<HitCell, Integer> refs) {
        long n = 0;
        for (int r : refs.values()) n += r;
        return n;
    }

    /** The group root is the smallest lineage key of the group. */
    private static String root(Map<String, String> parent, String key) {
        String k = key;
        while (!parent.get(k).equals(k)) k = parent.get(k);
        return k;
    }
}
