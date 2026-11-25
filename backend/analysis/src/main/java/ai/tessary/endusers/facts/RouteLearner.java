// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.endusers.judge.JudgeConstants;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stage 3 of the daily job: learns the route classes of spec section 1 from the live traces of the 28 days that end on
 * the as-of day, and stores them on the route rows (plan 3.2). A route near a cut keeps its stored class. A route with
 * no trace in the window keeps its stored row, and its class still counts for the anchor test and the lenient fallback.
 */
@Service
public class RouteLearner {

    private static final Logger log = LoggerFactory.getLogger(RouteLearner.class);

    private final RouteTraceRepository traces;
    private final RouteClassRepository classes;
    private final RouteRegistryRepository registry;

    public RouteLearner(RouteTraceRepository traces, RouteClassRepository classes, RouteRegistryRepository registry) {
        this.traces = traces;
        this.classes = classes;
        this.registry = registry;
    }

    public RouteClasses learn(String projectId, int asOfDay) {
        Instant began = Instant.now();
        int fromDay = asOfDay - JudgeConstants.LEARN_DAYS + 1;
        int toDay = asOfDay + 1;
        List<RouteTally> tallies = traces.tallies(projectId, fromDay, toDay);
        Map<Integer, RouteTally> byRoute = new TreeMap<>();
        for (RouteTally tally : tallies) {
            byRoute.put(registry.routeNo(projectId, tally.traceName(), tally.callSiteId()), tally);
        }
        Map<Integer, List<RouteSample>> samples = new TreeMap<>();
        for (RouteSample sample : traces.samples(projectId, fromDay, toDay)) {
            samples.computeIfAbsent(sample.routeNo(), r -> new ArrayList<>()).add(sample);
        }
        Map<Integer, Route> stored = classes.stored(projectId).byRoute();
        boolean appSendsInput = RouteClassRules.appSendsInput(tallies);

        Map<Integer, Route> routes = new TreeMap<>(stored);
        Set<Integer> aRoutes = new TreeSet<>();
        Set<Integer> bRoutes = new TreeSet<>();
        byRoute.forEach((routeNo, tally) -> {
            RouteStatistics statistics = RouteClassRules.statistics(tally, samples.getOrDefault(routeNo, List.of()));
            Route before = stored.get(routeNo);
            RouteClass routeClass =
                    RouteClassRules.keptClass(statistics, appSendsInput, before == null ? null : before.routeClass());
            routes.put(routeNo, new Route(routeClass, null, statistics, RouteClassRules.nearCut(statistics)));
            if (routeClass == RouteClass.A) aRoutes.add(routeNo);
            if (routeClass == RouteClass.B) bRoutes.add(routeNo);
        });
        Map<Integer, Double> coAnchor =
                bRoutes.isEmpty() ? Map.of() : traces.anchorShares(projectId, fromDay, toDay, aRoutes, bRoutes);
        RouteClasses learned = RouteClassRules.anchor(routes, coAnchor, stored, asOfDay);

        Map<Integer, Route> seen = new TreeMap<>(learned.byRoute());
        seen.keySet().retainAll(byRoute.keySet());
        classes.save(projectId, seen);

        long paced = learned.byRoute().values().stream()
                .filter(r -> r.routeClass() == RouteClass.A || r.routeClass() == RouteClass.A2)
                .count();
        int traceCount = tallies.stream().mapToInt(RouteTally::traces).sum();
        StructuredLog.info(log, Markers.OPS, "endusers.learn")
                .message(
                        "learned %d routes from %d traces of the last %d days (%d kept with no traces), %d paced,"
                                + " lenient %s",
                        seen.size(),
                        traceCount,
                        JudgeConstants.LEARN_DAYS,
                        learned.byRoute().size() - seen.size(),
                        paced,
                        learned.lenient())
                .field("project", projectId)
                .field("asOfDay", asOfDay)
                .field("routes", seen.size())
                .field("idleRoutes", learned.byRoute().size() - seen.size())
                .field("traces", traceCount)
                .field("pacedRoutes", paced)
                .field(
                        "nearCutRoutes",
                        seen.values().stream().filter(Route::nearCut).count())
                .field("lenient", learned.lenient())
                .durationMs(began)
                .log();
        return learned;
    }
}
