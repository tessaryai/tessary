// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import ai.tessary.endusers.model.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The rank of spec 8.7, in the tiers of the reference: evidence of loss by its lower limit; then few accounts and
 * reaches nearly all by users lost; then not enough evidence and no measurable loss by users lost; then always present,
 * moved, not added and the verdicts that show no number, by users lost; then the page refusal and the level 2 guards.
 * Ties take the lineage key order. Findings that fire together sit side by side at the place of their first member, and
 * inside the group they are ordered by users lost, never by the lower limit alone.
 */
public final class IssueRank {

    private IssueRank() {}

    /** @param lost null where the verdict shows none; such an issue sorts as 0 lost */
    public record Ranked(
            Verdict verdict,
            @Nullable Double lost,
            @Nullable Double low) {}

    private static final int LAST = 9;

    /**
     * @param groups lineage key to its group, only for members of a group of findings that fire together
     * @return lineage key to its place, from 1
     */
    public static Map<String, Integer> rank(Map<String, Ranked> issues, Map<String, String> groups) {
        Comparator<String> byLost = Comparator.<String>comparingInt(
                        k -> tier(issues.get(k).verdict()))
                .thenComparingDouble(k -> -lost(issues.get(k)))
                .thenComparing(Comparator.naturalOrder());
        Comparator<String> byClaim = Comparator.<String>comparingInt(
                        k -> tier(issues.get(k).verdict()))
                .thenComparingDouble(k -> -claim(issues.get(k)))
                .thenComparingDouble(k -> -lost(issues.get(k)))
                .thenComparing(Comparator.naturalOrder());
        List<String> sorted = new ArrayList<>(issues.keySet());
        sorted.sort(byClaim);
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<String> placedGroups = new HashSet<>();
        for (String key : sorted) {
            String group = groups.get(key);
            if (group == null) {
                out.put(key, out.size() + 1);
            } else if (placedGroups.add(group)) {
                sorted.stream()
                        .filter(k -> group.equals(groups.get(k)))
                        .sorted(byLost)
                        .forEach(k -> out.put(k, out.size() + 1));
            }
        }
        return out;
    }

    private static int tier(Verdict v) {
        return switch (v) {
            case EVIDENCE_OF_LOSS -> 0;
            case FEW_ACCOUNTS, REACHES_NEARLY_ALL -> 1;
            case NOT_ENOUGH_EVIDENCE, NO_MEASURABLE_LOSS -> 2;
            case PAGE_REFUSED, HITS_NOT_COMPLETE, HISTORY_TOO_SHORT, CANNOT_TELL_YET, NOT_APPLICABLE -> LAST;
            default -> 3;
        };
    }

    private static double claim(Ranked r) {
        Double low = r.low();
        return r.verdict() == Verdict.EVIDENCE_OF_LOSS && low != null ? low : 0;
    }

    private static double lost(Ranked r) {
        Double lost = r.lost();
        return lost == null ? 0 : lost;
    }
}
