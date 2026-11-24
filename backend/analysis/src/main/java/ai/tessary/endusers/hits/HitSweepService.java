// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.endusers.hits.EndUserHitRepository.Finding;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.retention.RetentionResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Keeps {@code end_user_hit} current (plan 3.2). The hourly sweep rebuilds each finding with evidence newer than the
 * watermark, read again from 2 hours before it because writers commit late, plus each open finding whose hits come from
 * a detection table, since those keep firing after their evidence stops at 50. The first sweep reads all evidence. The
 * daily reconcile rebuilds open findings and findings with evidence in the last 7 days, which catches a user ID a later
 * batch filled and evidence that was removed.
 */
@Service
public class HitSweepService {

    private static final Logger log = LoggerFactory.getLogger(HitSweepService.class);

    static final Duration LOOKBACK = Duration.ofHours(2);
    static final Duration RECONCILE_WINDOW = Duration.ofDays(7);

    /** The day the retention cut falls in, and one more of margin, can hold partly deleted traces. */
    static final int EDGE_DAYS = 2;

    private final EndUserHitRepository hits;
    private final FindingHitRebuilder rebuilder;
    private final EndUserProjectRepository projects;
    private final RetentionResolver retention;
    private final Clock clock;

    @Autowired
    public HitSweepService(
            EndUserHitRepository hits,
            FindingHitRebuilder rebuilder,
            EndUserProjectRepository projects,
            RetentionResolver retention) {
        this(hits, rebuilder, projects, retention, Clock.systemUTC());
    }

    HitSweepService(
            EndUserHitRepository hits,
            FindingHitRebuilder rebuilder,
            EndUserProjectRepository projects,
            RetentionResolver retention,
            Clock clock) {
        this.hits = hits;
        this.rebuilder = rebuilder;
        this.projects = projects;
        this.retention = retention;
        this.clock = clock;
    }

    /** @param findings the findings rebuilt @param rows the hit rows they hold on live days */
    public record Result(int findings, int rows) {}

    public Result sweep(String projectId) {
        Instant began = Instant.now();
        Instant runStart = clock.instant();
        Instant watermark = projects.state(projectId).hitsWatermark();
        Set<String> ids = new TreeSet<>(hits.findingsWithEvidenceSince(
                projectId, watermark == null ? Instant.EPOCH : watermark.minus(LOOKBACK)));
        for (Finding open : hits.findings(projectId, hits.openFindings(projectId))) {
            if (rebuilder.ruleFor(open).readsDetections()) ids.add(open.id());
        }
        Result result = rebuild(projectId, ids, runStart);
        projects.setHitsWatermark(projectId, runStart);
        StructuredLog.info(log, Markers.OPS, "endusers.hits.sweep")
                .message("swept hits of %d findings into %d rows", result.findings(), result.rows())
                .field("project", projectId)
                .field("findings", result.findings())
                .field("rows", result.rows())
                .field("backfill", watermark == null)
                .durationMs(began)
                .log();
        return result;
    }

    public Result reconcile(String projectId) {
        Instant began = Instant.now();
        Instant runStart = clock.instant();
        Set<String> ids = new TreeSet<>(hits.openFindings(projectId));
        ids.addAll(hits.findingsWithEvidenceSince(projectId, runStart.minus(RECONCILE_WINDOW)));
        Result result = rebuild(projectId, ids, runStart);
        StructuredLog.info(log, Markers.OPS, "endusers.hits.reconcile")
                .message("reconciled hits of %d findings into %d rows", result.findings(), result.rows())
                .field("project", projectId)
                .field("findings", result.findings())
                .field("rows", result.rows())
                .durationMs(began)
                .log();
        return result;
    }

    private Result rebuild(String projectId, Set<String> ids, Instant runStart) {
        byte[] salt = projects.salt(projectId);
        Integer oldestLiveDay = oldestLiveDay(projectId, EndUserKeys.dayIndex(runStart));
        int rows = 0;
        int findings = 0;
        for (Finding finding : hits.findings(projectId, ids)) {
            rows += rebuilder.rebuild(projectId, finding, salt, oldestLiveDay);
            findings++;
        }
        return new Result(findings, rows);
    }

    /** Null when the trace TTL is 0: traces are kept for good. */
    private @Nullable Integer oldestLiveDay(String projectId, int today) {
        int ttlDays = retention.resolve(projectId).stream()
                .filter(r -> r.dataClass() == RetentionResolver.DataClass.TRACES)
                .findFirst()
                .orElseThrow()
                .ttlDays();
        return ttlDays > 0 ? today - ttlDays + EDGE_DAYS : null;
    }
}
