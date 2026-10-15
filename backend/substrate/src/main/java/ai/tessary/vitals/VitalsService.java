// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.vitals.VitalsDtos.Cost;
import ai.tessary.vitals.VitalsDtos.Duration;
import ai.tessary.vitals.VitalsDtos.Group;
import ai.tessary.vitals.VitalsDtos.Vitals;
import ai.tessary.vitals.VitalsDtos.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Spend and turn latency for a window against the equal-length window before it, grouped by a dimension. Writes nothing
 * and enqueues nothing, so escalation is structurally impossible here.
 */
@Service
public class VitalsService {

    /** Beyond this the read stops being interactive. */
    public static final int MAX_WINDOW_DAYS = 90;

    private final VitalsRepository repo;

    private final PriceBookRepository priceBook;

    public VitalsService(VitalsRepository repo, PriceBookRepository priceBook) {
        this.repo = repo;
        this.priceBook = priceBook;
    }

    public Vitals compute(String projectId, int windowDays, VitalsRepository.Dimension by) {
        int days = Math.max(1, Math.min(MAX_WINDOW_DAYS, windowDays));
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);
        Instant baseFrom = from.minus(days, ChronoUnit.DAYS);

        Raw current = read(projectId, from, now, by);
        Raw baseline = read(projectId, baseFrom, from, by);

        Map<String, String> labels =
                by == VitalsRepository.Dimension.CALL_SITE ? repo.callSiteLabels(projectId) : Map.of();

        // Union of both windows: a dimension value that vanished this window is as interesting as one
        // that appeared, and dropping it would hide a call site that stopped serving traffic entirely.
        Set<String> keys = new LinkedHashSet<>(current.byDimension().keySet());
        keys.addAll(baseline.byDimension().keySet());

        List<Group> groups = new ArrayList<>();
        for (String key : keys) {
            groups.add(group(
                    key,
                    labels.getOrDefault(key, key),
                    current.byDimension().getOrDefault(key, Bucket.empty()),
                    baseline.byDimension().getOrDefault(key, Bucket.empty())));
        }
        // Flagged first, then by spend — the page's job is to put the concern at the top, and among
        // unflagged rows the expensive ones are the ones worth a look.
        groups.sort(Comparator.comparing(Group::flagged)
                .reversed()
                .thenComparing(g -> g.cost().usd(), Comparator.reverseOrder()));

        Group total = group(null, null, current.total(), baseline.total());

        return new Vitals(
                new Window(from.toString(), now.toString(), baseFrom.toString(), from.toString(), days),
                by.name().toLowerCase(java.util.Locale.ROOT),
                total,
                List.copyOf(groups),
                priceBook.pricedModelCount());
    }

    private Raw read(String projectId, Instant from, Instant to, VitalsRepository.Dimension by) {
        Map<String, Bucket> byDim = new HashMap<>();
        Bucket total = Bucket.empty();

        for (VitalsRepository.UsageRow r : repo.usageIn(projectId, from, to, by)) {
            byDim.computeIfAbsent(r.dimension(), k -> Bucket.empty()).add(r);
            total.add(r);
        }
        for (VitalsRepository.DurationRow r : repo.turnDurationsIn(projectId, from, to, by)) {
            byDim.computeIfAbsent(r.dimension(), k -> Bucket.empty()).addDuration(r.millis());
            total.addDuration(r.millis());
        }
        // Per dimension AND on the total: a call site whose turns increasingly hang would otherwise show a shrinking
        // sample and a flattering p95.
        for (VitalsRepository.UnterminatedRow r : repo.unterminatedTurnsIn(projectId, from, to, by)) {
            if (r.total()) {
                // The GROUPING SET's grand total, which counts each trace once however many dimension
                // values its parentless spans span. Summing the buckets would over-count those traces.
                total.unterminated = r.turns();
            } else {
                byDim.computeIfAbsent(r.dimension(), k -> Bucket.empty()).unterminated = r.turns();
            }
        }
        return new Raw(byDim, total);
    }

    private static Group group(@Nullable String key, @Nullable String label, Bucket cur, Bucket base) {
        Cost cost = cost(cur, base);
        Duration duration = duration(cur, base);
        return new Group(key, label, cost, duration, cost.flagged() || duration.flagged());
    }

    private static Cost cost(Bucket cur, Bucket base) {
        // The denominator comes from the spending population, not the root-span one: a turn reaching a second call site
        // books cost there but its turn in the first, and root spans carry no model.
        long turns = cur.costTurns();
        long baseTurns = base.costTurns();
        double perTurn = turns > 0 ? cur.usd.doubleValue() / turns : 0;
        double basePerTurn = baseTurns > 0 ? base.usd.doubleValue() / baseTurns : 0;

        Double deltaPct = basePerTurn > 0 ? ((perTurn - basePerTurn) / basePerTurn) * 100.0 : null;
        boolean flagged = VitalsThresholds.costWorsened(perTurn, basePerTurn, turns, baseTurns);

        return new Cost(
                cur.usd.setScale(6, RoundingMode.HALF_UP),
                cur.tokens,
                cur.calls,
                cur.unpricedCalls,
                turns > 0 ? perTurn : null,
                baseTurns > 0 ? base.usd.setScale(6, RoundingMode.HALF_UP) : null,
                deltaPct,
                flagged);
    }

    private static Duration duration(Bucket cur, Bucket base) {
        double[] c = cur.sortedDurations();
        double[] b = base.sortedDurations();
        Long p50 = VitalsThresholds.percentile(c, 0.50);
        Long p95 = VitalsThresholds.percentile(c, 0.95);
        Long basep95 = VitalsThresholds.percentile(b, 0.95);
        Double deltaPct =
                (p95 != null && basep95 != null && basep95 > 0) ? ((double) (p95 - basep95) / basep95) * 100.0 : null;
        return new Duration(
                c.length,
                p50,
                p95,
                basep95,
                deltaPct,
                VitalsThresholds.durationWorsened(p95, basep95, c.length, b.length),
                cur.unterminated);
    }

    private record Raw(Map<String, Bucket> byDimension, Bucket total) {}

    /** The {@code total} bucket folds several dimension rows together, so this adds pre-summed group totals. */
    private static final class Bucket {
        private BigDecimal usd = BigDecimal.ZERO;
        private long tokens;
        private long calls;
        private long unpricedCalls;
        private long spendingTraces;
        private long unterminated;
        private final List<Double> durations = new ArrayList<>();

        static Bucket empty() {
            return new Bucket();
        }

        void add(VitalsRepository.UsageRow r) {
            usd = usd.add(r.usd());
            tokens += r.tokens();
            calls += r.calls();
            unpricedCalls += r.unpricedCalls();
            // Summed, not de-duplicated: a trace spanning two call sites is two turns' worth of attribution, which
            // makes the total's cost-per-turn conservative rather than inflated.
            spendingTraces += r.spendingTraces();
        }

        /** The cost denominator. */
        long costTurns() {
            return spendingTraces;
        }

        void addDuration(double millis) {
            if (millis >= 0) durations.add(millis);
        }

        double[] sortedDurations() {
            double[] a = durations.stream().mapToDouble(Double::doubleValue).toArray();
            java.util.Arrays.sort(a);
            return a;
        }
    }
}
