// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CARRY_OVER_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.GROUP_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.NON_RETURNING_CARRY_OVER;
import static ai.tessary.endusers.judge.JudgeConstants.NON_RETURNING_LATER;
import static ai.tessary.endusers.judge.JudgeConstants.NON_RETURNING_RETURN_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.OLD_ID_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_IDENT_RECENT_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.RETURN_DAYS;
import static java.util.Objects.requireNonNull;

import ai.tessary.endusers.facts.ShapeSignature;
import ai.tessary.endusers.judge.IdentityFlags.Flags;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.SetAsideReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * ID groups, return evidence, coverage and the identity shares of spec 3.3 and 3.5. Group members are the IDs that are
 * neither aggregate nor service; every ID gets a group key. {@code oneDayShareByShape} is read over the same members.
 */
public final class IdGroups {

    private static final String APP = "(app)";

    private IdGroups() {}

    public static Identity identity(
            Facts facts, Map<Integer, Integer> aliasOf, Series series, Map<Integer, Flags> flags) {
        int asOfDay = facts.asOfDay();
        Map<Integer, IdSeries> byId = series.byId();
        Predicate<Integer> old = u -> byId.get(u).firstSeen() <= asOfDay - OLD_ID_DAYS;
        List<Integer> pool = byId.keySet().stream()
                .filter(u -> !flags.get(u).aggregate() && !flags.get(u).service())
                .toList();

        Map<String, Integer> oldPerShape = new HashMap<>();
        Map<String, Integer> oldPerPrefix = new HashMap<>();
        pool.stream().filter(old).forEach(u -> {
            IdInfo info = facts.ids().get(u);
            oldPerShape.merge(IdentityFlags.shapeKey(info), 1, Integer::sum);
            oldPerPrefix.merge(info.shapePrefix(), 1, Integer::sum);
        });
        Map<Integer, String> groupKey = new TreeMap<>();
        byId.keySet().forEach(u -> {
            IdInfo info = facts.ids().get(u);
            String shape = IdentityFlags.shapeKey(info);
            if (oldPerShape.getOrDefault(shape, 0) >= GROUP_MIN_IDS) groupKey.put(u, shape);
            else if (oldPerPrefix.getOrDefault(info.shapePrefix(), 0) >= GROUP_MIN_IDS)
                groupKey.put(u, info.shapePrefix() + "*");
            else groupKey.put(u, APP);
        });

        Map<String, List<Integer>> members = new TreeMap<>();
        Map<String, List<Integer>> perShape = new TreeMap<>();
        for (int u : pool) {
            members.computeIfAbsent(groupKey.get(u), g -> new ArrayList<>()).add(u);
            perShape.computeIfAbsent(IdentityFlags.shapeKey(facts.ids().get(u)), g -> new ArrayList<>())
                    .add(u);
        }
        Map<String, GroupStats> groups = new TreeMap<>();
        members.forEach((g, us) -> groups.put(g, judged(stats(us, byId, old, asOfDay))));
        Map<String, Double> oneDayShareByShape = new TreeMap<>();
        perShape.forEach((shape, us) -> {
            Double share = stats(us, byId, old, asOfDay).oneDayShare();
            if (share != null) oneDayShareByShape.put(shape, share);
        });

        Map<Integer, Integer> accountOf = accounts(facts.ids(), byId.keySet());
        Map<Integer, IdIdentity> identities = new TreeMap<>();
        byId.forEach((u, s) -> {
            Flags f = flags.get(u);
            String g = groupKey.get(u);
            SetAsideReason setAside =
                    setAside(f, groups.containsKey(g) && groups.get(g).nonReturning(), s.basis());
            identities.put(
                    u,
                    new IdIdentity(
                            f.aggregate(),
                            f.groupHint(),
                            f.service(),
                            f.oddShape(),
                            f.tokenLink(),
                            g,
                            accountOf.get(u),
                            setAside == null,
                            setAside));
        });

        Map<Integer, Integer> weight = new HashMap<>();
        byId.keySet().forEach(u -> weight.put(u, flags.get(u).sizeDays()));
        if (weight.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            byId.forEach((u, s) -> weight.put(u, s.days().size()));
        }
        int totalWeight =
                Math.max(1, weight.values().stream().mapToInt(Integer::intValue).sum());
        long tokenLinks =
                byId.keySet().stream().filter(u -> flags.get(u).tokenLink()).count();

        return new Identity(
                identities,
                aliasOf,
                groups,
                stats(pool, byId, old, asOfDay),
                oneDayShareByShape,
                pd(weight, totalWeight, u -> identities.get(u).covered()),
                pd(weight, totalWeight, u -> flags.get(u).aggregate()),
                pd(weight, totalWeight, u -> flags.get(u).groupHint()),
                identShare(facts, Integer.MIN_VALUE),
                identShare(facts, asOfDay - PAGE_IDENT_RECENT_DAYS + 1),
                byId.isEmpty() ? 0.0 : (double) tokenLinks / byId.size());
    }

    private static GroupStats stats(
            List<Integer> ids, Map<Integer, IdSeries> byId, Predicate<Integer> old, int asOfDay) {
        List<IdSeries> oldIds = ids.stream().filter(old).map(byId::get).toList();
        List<IdSeries> active = ids.stream()
                .map(byId::get)
                .filter(s -> s.days().getLast() >= asOfDay - CARRY_OVER_DAYS)
                .toList();
        return new GroupStats(
                ids.size(),
                oldIds.size(),
                share(oldIds, s -> s.days().getLast() - s.firstSeen() >= RETURN_DAYS),
                share(oldIds, s -> s.days().size() >= 2),
                share(active, s -> s.firstSeen() < asOfDay - CARRY_OVER_DAYS),
                share(oldIds, s -> s.days().size() == 1),
                false);
    }

    /** A group with old IDs has both shares of old IDs; only the carry-over share, of active IDs, can be missing. */
    private static GroupStats judged(GroupStats s) {
        Double carryOver = s.carryOver();
        boolean nonReturning = s.old() >= GROUP_MIN_IDS
                && (requireNonNull(s.returnLater()) < NON_RETURNING_LATER
                        || (requireNonNull(s.returnShare()) < NON_RETURNING_RETURN_SHARE
                                && carryOver != null
                                && carryOver < NON_RETURNING_CARRY_OVER));
        return new GroupStats(
                s.ids(), s.old(), s.returnLater(), s.returnShare(), s.carryOver(), s.oneDayShare(), nonReturning);
    }

    private static @Nullable Double share(List<IdSeries> ids, Predicate<IdSeries> test) {
        return ids.isEmpty() ? null : (double) ids.stream().filter(test).count() / ids.size();
    }

    private static @Nullable SetAsideReason setAside(Flags f, boolean nonReturningGroup, ActivityBasis basis) {
        if (f.aggregate()) return SetAsideReason.AGGREGATE;
        if (f.service()) return SetAsideReason.SERVICE;
        if (f.oddShape()) return SetAsideReason.ODD_SHAPE;
        if (f.tokenLink()) return SetAsideReason.TOKEN_LINK;
        if (nonReturningGroup) return SetAsideReason.NON_RETURNING_GROUP;
        if (basis == ActivityBasis.MACHINE) return SetAsideReason.MACHINE_ONLY;
        return null;
    }

    /** The account cluster of each ID whose cluster counts as an account. */
    private static Map<Integer, Integer> accounts(List<IdInfo> ids, Set<Integer> keys) {
        Map<Integer, Integer> idsPerCluster = new HashMap<>();
        for (int u : keys) {
            Integer cluster = ids.get(u).cluster();
            if (cluster != null) idsPerCluster.merge(cluster, 1, Integer::sum);
        }
        Set<Integer> accounts = ShapeSignature.accountClusters(idsPerCluster);
        Map<Integer, Integer> accountOf = new HashMap<>();
        for (int u : keys) {
            Integer cluster = ids.get(u).cluster();
            if (cluster != null && accounts.contains(cluster)) accountOf.put(u, cluster);
        }
        return accountOf;
    }

    private static double pd(Map<Integer, Integer> weight, int totalWeight, Predicate<Integer> in) {
        return (double) weight.entrySet().stream()
                        .filter(e -> in.test(e.getKey()))
                        .mapToInt(Map.Entry::getValue)
                        .sum()
                / totalWeight;
    }

    /** Share of traces on routes not of class C that carry a user ID, from {@code fromDay}; 1 with no such trace. */
    private static double identShare(Facts facts, int fromDay) {
        long traces = 0;
        long noUserId = 0;
        for (NullDay row : facts.nullDays()) {
            if (row.day() < fromDay) continue;
            if (DaySeries.classOf(facts.storedRouteClasses(), row.routeNo()) == RouteClass.C) continue;
            traces += row.traces();
            noUserId += row.tracesNoUserId();
        }
        return traces == 0 ? 1.0 : 1 - (double) noUserId / traces;
    }
}
