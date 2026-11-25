// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import ai.tessary.endusers.judge.model.Facts.AliasEdge;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The aliases of spec 3.2: session and thread links are pooled, and only one-to-one links are kept (one successor for
 * the earlier ID, one predecessor for the later ID, reading 4 of spec 5). A chain of links is one unit whose
 * representative is its last ID.
 */
public final class Aliases {

    private Aliases() {}

    /** Alias member to its representative, both key indexes; an ID that is no member is not a key. */
    public static Map<Integer, Integer> representatives(List<AliasEdge> edges) {
        Map<Integer, Set<Integer>> successors = new TreeMap<>();
        Map<Integer, Set<Integer>> predecessors = new HashMap<>();
        for (AliasEdge edge : edges) {
            successors.computeIfAbsent(edge.fromId(), x -> new HashSet<>()).add(edge.toId());
            predecessors.computeIfAbsent(edge.toId(), y -> new HashSet<>()).add(edge.fromId());
        }
        Map<Integer, Integer> next = new HashMap<>();
        successors.forEach((x, ys) -> {
            if (ys.size() == 1) {
                int y = ys.iterator().next();
                if (predecessors.get(y).size() == 1) next.put(x, y);
            }
        });
        Map<Integer, Integer> representative = new TreeMap<>();
        for (int member : next.keySet()) {
            int last = lastOf(member, next);
            if (last != member) representative.put(member, last);
        }
        return representative;
    }

    /** Follows the links to the end; a loop stops where it closes, at the starting ID. */
    private static int lastOf(int id, Map<Integer, Integer> next) {
        Set<Integer> seen = new HashSet<>();
        int u = id;
        while (next.containsKey(u) && seen.add(u)) {
            u = next.get(u);
        }
        return u;
    }
}
