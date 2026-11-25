// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.IssueRank.Ranked;
import ai.tessary.endusers.model.Verdict;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The rank of spec 8.7: evidence of loss by its lower limit, then few accounts and reaches nearly all by users lost, then
 * not enough evidence (and no measurable loss) by users lost, then always present and moved, not added. Findings that
 * fire together sit side by side, and neither is put above the other on its lower limit alone. A tier out of order, or
 * a lower limit read where the verdict makes no claim, would put a number with no claim above a claim.
 */
class IssueRankTest {

    private static Ranked r(Verdict v, @Nullable Double lost, @Nullable Double low) {
        return new Ranked(v, lost, low);
    }

    private static List<String> order(Map<String, Integer> rank) {
        return rank.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Test
    void rank() {
        Map<String, Ranked> issues = new LinkedHashMap<>();
        issues.put("j", r(Verdict.PAGE_REFUSED, null, null));
        issues.put("a", r(Verdict.EVIDENCE_OF_LOSS, 30.0, 5.0));
        issues.put("g", r(Verdict.ALWAYS_PRESENT, 40.0, 0.0));
        issues.put("c", r(Verdict.FEW_ACCOUNTS, 10.0, 0.0));
        issues.put("i", r(Verdict.TOO_FEW_IDS, null, null));
        issues.put("e", r(Verdict.NOT_ENOUGH_EVIDENCE, 12.0, 0.0));
        issues.put("b", r(Verdict.EVIDENCE_OF_LOSS, 20.0, 8.0));
        issues.put("h", r(Verdict.MOVED_NOT_ADDED, 50.0, 0.0));
        issues.put("f", r(Verdict.NO_MEASURABLE_LOSS, 3.0, 0.0));
        issues.put("d", r(Verdict.REACHES_NEARLY_ALL, 15.0, 0.0));
        Map<String, Integer> rank = IssueRank.rank(issues, Map.of());
        assertEquals(List.of("b", "a", "d", "c", "e", "f", "h", "g", "i", "j"), order(rank));
        assertEquals(1, rank.get("b"));
    }

    @Test
    void ties_takeTheLineageKeyOrder() {
        Map<String, Ranked> issues = new LinkedHashMap<>();
        issues.put("z", r(Verdict.NOT_ENOUGH_EVIDENCE, 4.0, 0.0));
        issues.put("y", r(Verdict.NOT_ENOUGH_EVIDENCE, 4.0, 0.0));
        assertEquals(List.of("y", "z"), order(IssueRank.rank(issues, Map.of())));
    }

    @Test
    void findingsThatFireTogether_sitSideBySide_andAreNotOrderedByLowerLimit() {
        Map<String, Ranked> issues = new LinkedHashMap<>();
        issues.put("p", r(Verdict.EVIDENCE_OF_LOSS, 10.0, 9.0));
        issues.put("q", r(Verdict.EVIDENCE_OF_LOSS, 25.0, 2.0));
        issues.put("r", r(Verdict.NOT_ENOUGH_EVIDENCE, 30.0, 0.0));
        issues.put("s", r(Verdict.NOT_ENOUGH_EVIDENCE, 5.0, 0.0));
        issues.put("t", r(Verdict.ALWAYS_PRESENT, 1.0, 0.0));
        assertEquals(List.of("p", "q", "r", "s", "t"), order(IssueRank.rank(issues, Map.of())));
        assertEquals(
                List.of("q", "p", "r", "s", "t"),
                order(IssueRank.rank(issues, Map.of("p", "p", "q", "p"))),
                "inside the group by users lost");
        assertEquals(
                List.of("p", "t", "q", "r", "s"),
                order(IssueRank.rank(issues, Map.of("p", "p", "t", "p"))),
                "the group takes the place of its first member");
    }
}
