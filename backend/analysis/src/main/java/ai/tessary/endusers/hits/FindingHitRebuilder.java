// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.worker.ClassifierArming;
import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.facts.RouteRegistryRepository;
import ai.tessary.endusers.hits.EndUserHitRepository.Finding;
import ai.tessary.endusers.hits.EndUserHitRepository.HitRow;
import ai.tessary.endusers.hits.EndUserHitRepository.RefGroup;
import ai.tessary.endusers.hits.EndUserHitRepository.SessionOwner;
import ai.tessary.endusers.hits.EndUserHitRepository.SessionRefs;
import ai.tessary.endusers.hits.EndUserHitRepository.Summary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuilds one finding's hit rows from its current evidence or detections and writes its summary, in one transaction.
 * The rebuild replaces rows exactly, so running it twice, or over refs read again, gives the same rows.
 */
@Component
public class FindingHitRebuilder {

    static final String NO_USER_KEY = HexFormat.of().formatHex(new byte[EndUserKeys.KEY_BYTES]);

    private final EndUserHitRepository hits;
    private final RouteRegistryRepository routes;
    private final HitRules rules;
    private final ClassifierRepository classifiers;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public FindingHitRebuilder(
            EndUserHitRepository hits,
            RouteRegistryRepository routes,
            HitRules rules,
            ClassifierRepository classifiers,
            ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.hits = hits;
        this.routes = routes;
        this.rules = rules;
        this.classifiers = classifiers;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactions);
    }

    public HitRule ruleFor(Finding finding) {
        return rules.ruleFor(finding.hitFinding());
    }

    /** @return the rows written */
    public int rebuild(String projectId, Finding finding, byte[] salt, @Nullable Integer oldestLiveDay) {
        HitRule rule = ruleFor(finding);
        String source = rule.readsDetections() ? EndUserHitRepository.DETECTION : EndUserHitRepository.EVIDENCE;
        Map<RouteName, Integer> routeNos = new HashMap<>();
        Map<HitKey, Integer> refs = new TreeMap<>();
        for (RefGroup group : groups(projectId, finding, rule)) {
            int routeNo = routeNos.computeIfAbsent(
                    new RouteName(group.traceName(), group.callSiteId()),
                    r -> routes.routeNo(projectId, r.traceName(), r.callSiteId()));
            refs.merge(
                    new HitKey(group.role(), userKey(salt, group.userId()), group.day(), routeNo),
                    group.refs(),
                    Integer::sum);
        }
        if (rule.rows() == HitRule.Rows.EVIDENCE) addSessionOnly(projectId, finding, salt, refs);
        List<HitRow> rows = new ArrayList<>(refs.size());
        refs.forEach((k, n) -> rows.add(new HitRow(k.role(), k.userKey(), k.day(), k.routeNo(), n, source)));
        transaction.executeWithoutResult(tx -> {
            hits.lock(projectId, finding.id());
            hits.replace(projectId, finding.id(), oldestLiveDay, rows);
            hits.writeSummary(projectId, summary(finding.id(), rule, hits.stored(projectId, finding.id())));
        });
        return rows.size();
    }

    private List<RefGroup> groups(String projectId, Finding finding, HitRule rule) {
        return switch (rule.rows()) {
            case EVIDENCE -> hits.evidenceGroups(projectId, finding.id(), rule.onePerTrace());
            case MALFORMED_OUTPUT_DETECTIONS -> hits.malformedOutputGroups(projectId, finding.id());
            case SECRET_LEAK_DETECTIONS ->
                hits.secretLeakGroups(projectId, finding.id(), highOnly(projectId, finding.subjectId()));
        };
    }

    /** The arming's own confidence band; a classifier no longer armed reads every confidence. */
    private boolean highOnly(String projectId, String classifierId) {
        return classifiers
                .findById(projectId, classifierId)
                .map(signal -> {
                    ClassifierArming.Config config = ClassifierArming.parse(mapper, signal.configJson());
                    return config != null && config.highOnly(signal);
                })
                .orElse(false);
    }

    /**
     * A ref to a session only resolves as spec 8.1 says: to the user ID of the session's traces and the day of its last
     * trace. It is stored on {@link EndUserHitRepository#SESSION_ONLY_ROUTE}.
     */
    private void addSessionOnly(String projectId, Finding finding, byte[] salt, Map<HitKey, Integer> refs) {
        List<SessionRefs> sessionRefs = hits.sessionRefs(projectId, finding.id());
        Map<String, SessionOwner> owners = hits
                .sessionOwners(
                        projectId,
                        sessionRefs.stream().map(SessionRefs::sessionId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(SessionOwner::sessionId, o -> o));
        for (SessionRefs ref : sessionRefs) {
            SessionOwner owner = owners.get(ref.sessionId());
            if (owner == null) continue;
            refs.merge(
                    new HitKey(
                            ref.role(),
                            userKey(salt, owner.userId()),
                            owner.lastDay(),
                            EndUserHitRepository.SESSION_ONLY_ROUTE),
                    ref.refs(),
                    Integer::sum);
        }
    }

    static Summary summary(String findingId, HitRule rule, List<HitRow> stored) {
        Set<String> hitRoles = rule.hitRoles(stored.stream().map(HitRow::role).collect(Collectors.toSet()));
        int total = 0;
        int noUserId = 0;
        int sessionOnly = 0;
        for (HitRow row : stored) {
            if (!hitRoles.contains(row.role())) continue;
            total += row.refs();
            if (row.routeNo() == EndUserHitRepository.SESSION_ONLY_ROUTE) {
                sessionOnly += row.refs();
            } else if (NO_USER_KEY.equals(row.userKey())) {
                noUserId += row.refs();
            }
        }
        return new Summary(findingId, total, noUserId, sessionOnly, rule.complete(), rule.name());
    }

    private static String userKey(byte[] salt, @Nullable String userId) {
        return HexFormat.of().formatHex(EndUserKeys.userKey(salt, userId));
    }

    private record RouteName(
            @Nullable String traceName, @Nullable String callSiteId) {}

    private record HitKey(String role, String userKey, int day, int routeNo) implements Comparable<HitKey> {

        @Override
        public int compareTo(HitKey o) {
            int c = role.compareTo(o.role);
            if (c == 0) c = userKey.compareTo(o.userKey);
            if (c == 0) c = Integer.compare(day, o.day);
            return c != 0 ? c : Integer.compare(routeNo, o.routeNo);
        }
    }
}
