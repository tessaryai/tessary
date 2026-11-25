// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.IssueResult.AccountLine;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.Stratum;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The account lines of a few-accounts verdict (spec 8.7): "K of the M IDs of an account were hit and J of them are
 * silent since a date", for the up to 3 accounts with the most hit IDs. A line that counts a hit ID the estimate cannot
 * follow, counts an account's IDs over hit IDs only, or calls an ID silent under the stop cut tells the reader the
 * hits sit in other accounts than they do.
 */
class AccountLinesTest {

    private final Map<Integer, IdIdentity> identity = new TreeMap<>();
    private final Map<Integer, IdSeries> series = new TreeMap<>();
    private final Map<Integer, IdOdds> odds = new TreeMap<>();
    private final Map<Integer, List<Integer>> hitDays = new HashMap<>();

    /**
     * The bug: the lines name the wrong accounts, count IDs the estimate leaves out, or give a since-date at which some
     * of the silent IDs were still active.
     */
    @Test
    void lines_top3AccountsByHitIds() {
        for (int u = 0; u <= 5; u++) id(u, 7, 0.1, 70);
        for (int u = 6; u <= 10; u++) id(u, 8, 0.1, 70);
        for (int u = 11; u <= 15; u++) id(u, 9, 0.1, 70);
        id(16, null, 0.1, 70);
        hit(0, 0.9, 50).hit(1, 0.6, 60).hit(2, 0.2, 70).hit(3, 0.7, 55).hit(4, 0.9, 52);
        hit(6, 0.1, 70).hit(7, 0.1, 70).hit(8, 0.59, 61);
        hit(11, 0.8, 40);
        hit(16, 0.9, 30);

        List<AccountLine> lines = AccountLines.lines(
                new LineageHits(hitDays, 30, 60, List.of(1), true, false),
                Set.of(0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
                identity(),
                odds(),
                new Series(0, series));

        assertEquals(
                List.of(
                        new AccountLine(1, 6, 4, 3, 60),
                        new AccountLine(2, 5, 3, 0, null),
                        new AccountLine(3, 5, 1, 1, 40)),
                lines,
                "ID 4 cannot be followed; 0.59 is under the stop cut; the tie of one hit ID goes to the account seen"
                        + " first in key order, and an ID with no cluster is an account of one");
    }

    private void id(int u, @Nullable Integer account, double pStop, int lastDay) {
        identity.put(u, new IdIdentity(false, false, false, false, false, "u/8", account, true, null));
        List<Integer> days = List.of(10, lastDay);
        series.put(u, new IdSeries(10, days, ActivityBasis.HUMAN, days, List.of(), List.of(), false, null));
        odds.put(u, new IdOdds(Stratum.E, pStop, pStop, pStop, null, false, false, 80 - lastDay, 80 - lastDay));
    }

    private AccountLinesTest hit(int u, double pStop, int lastDay) {
        id(u, identity.get(u).account(), pStop, lastDay);
        hitDays.put(u, List.of(30));
        return this;
    }

    private Identity identity() {
        return new Identity(
                identity,
                Map.of(),
                Map.of(),
                new GroupStats(17, 17, null, null, null, null, false),
                Map.of(),
                1.0,
                0.0,
                0.0,
                1.0,
                1.0,
                0.0);
    }

    private StopOdds odds() {
        return new StopOdds(odds, 7.0, 7.0, 21, List.of(), List.of(), null, null, Map.of(), Map.of(), Map.of());
    }
}
