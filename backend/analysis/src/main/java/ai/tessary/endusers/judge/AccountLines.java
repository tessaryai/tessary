// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.STOP_CUT;

import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.IssueResult.AccountLine;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The account lines of the few-accounts words (spec 8.7, reference lines 3800 to 3806): the up to 3 accounts with the
 * most hit IDs the estimate can follow. An ID with no account cluster is an account of its own. A tie goes to the
 * account whose first hit ID comes first in key order. The since-date is not in the reference: it is the day after
 * which every silent hit ID of the account has been silent.
 */
public final class AccountLines {

    private static final int LINES = 3; // spec 8.7

    private AccountLines() {}

    private record Account(boolean cluster, int value) {}

    /**
     * @param estimable covered IDs the estimate can follow (not vanished in an ID change)
     */
    public static List<AccountLine> lines(
            LineageHits hits, Set<Integer> estimable, Identity identity, StopOdds odds, Series series) {
        Map<Account, TreeSet<Integer>> hitByAccount = new HashMap<>();
        for (int u : hits.hitDays().keySet()) {
            if (estimable.contains(u))
                hitByAccount
                        .computeIfAbsent(account(identity, u), a -> new TreeSet<>())
                        .add(u);
        }
        Map<Account, Integer> idsByAccount = new HashMap<>();
        identity.byId().keySet().forEach(u -> idsByAccount.merge(account(identity, u), 1, Integer::sum));
        List<Account> ranked = hitByAccount.keySet().stream()
                .sorted(Comparator.comparingInt(
                                (Account a) -> -hitByAccount.get(a).size())
                        .thenComparingInt(a -> hitByAccount.get(a).first()))
                .limit(LINES)
                .toList();
        List<AccountLine> lines = new ArrayList<>(ranked.size());
        for (Account a : ranked) {
            Set<Integer> hit = hitByAccount.get(a);
            int silent = 0;
            Integer since = null;
            for (int u : hit) {
                IdOdds o = odds.byId().get(u);
                if (o == null || o.pStop() < STOP_CUT) continue;
                silent++;
                int last = series.byId().get(u).days().getLast();
                since = since == null ? last : Math.max(since, last);
            }
            lines.add(new AccountLine(lines.size() + 1, idsByAccount.get(a), hit.size(), silent, since));
        }
        return lines;
    }

    private static Account account(Identity identity, int u) {
        @Nullable Integer cluster = identity.byId().get(u).account();
        return cluster == null ? new Account(false, u) : new Account(true, cluster);
    }
}
