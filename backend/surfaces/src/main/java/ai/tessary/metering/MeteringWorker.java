// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import ai.tessary.config.MeteringProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.tenant.Ids;
import ai.tessary.usage.MetricRollupJobRepository;
import ai.tessary.usage.MetricRollupJobRow;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.MetricRollupRow;
import ai.tessary.usage.UsageUnit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Meters only closed buckets, so the upsert is idempotent and a re-run is a no-op. Quota enforcement reads this lagged
 * rollup and accepts about an hour of overshoot per month rather than run an unbounded aggregate on the ingest path; a
 * lowered cap bites only once the current bucket is metered. A {@code day} rollup re-aggregates source rows, never sums
 * hours.
 */
@Component
public class MeteringWorker {

    private static final Logger log = LoggerFactory.getLogger(MeteringWorker.class);

    static final String BUCKET_HOUR = UsageUnit.BUCKET_HOUR;

    /**
     * Re-aggregates the same rows over the whole day, not a sum of hourly rollups, in its own {@code granularity} key
     * space.
     */
    static final String BUCKET_DAY = UsageUnit.BUCKET_DAY;

    private final MetricRollupJobRepository jobs;
    private final MetricRollupRepository rollups;
    private final MeteringProperties props;
    private final TraceMdcBridge traceBridge;

    public MeteringWorker(
            MetricRollupJobRepository jobs,
            MetricRollupRepository rollups,
            MeteringProperties props,
            TraceMdcBridge traceBridge) {
        this.jobs = jobs;
        this.rollups = rollups;
        this.props = props;
        this.traceBridge = traceBridge;
    }

    @Scheduled(fixedDelayString = "${tessary.metering.heartbeat-ms:300000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            try {
                scheduleClosedHour(Instant.now());
                scheduleClosedDay(Instant.now());
            } catch (RuntimeException e) {
                // No jobs get scheduled this heartbeat: a genuine, actionable failure.
                log.error(Markers.OPS, "metering scheduling failed", e);
            }
            meterClaimedJobs();
        }
    }

    private void scheduleClosedHour(Instant now) {
        Instant closedBucket = now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        int scheduled = jobs.scheduleDueBuckets(closedBucket.toString(), BUCKET_HOUR);
        if (scheduled > 0) {
            log.info(Markers.OPS, "metering scheduled hour jobs={} bucket={}", scheduled, closedBucket);
        }
    }

    /** Re-scheduling the same day is a no-op via {@code ON CONFLICT DO NOTHING}. */
    private void scheduleClosedDay(Instant now) {
        Instant closedBucket = now.truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);
        int scheduled = jobs.scheduleDueBuckets(closedBucket.toString(), BUCKET_DAY);
        if (scheduled > 0) {
            log.info(Markers.OPS, "metering scheduled day jobs={} bucket={}", scheduled, closedBucket);
        }
    }

    private void meterClaimedJobs() {
        List<MetricRollupJobRow> due;
        try {
            due = jobs.claimBatch(props.getClaimBatch(), props.getLeaseSeconds());
        } catch (RuntimeException e) {
            log.error(Markers.OPS, "metering claim failed", e);
            return;
        }
        int metered = 0;
        for (MetricRollupJobRow job : due) {
            try (LogContext ignored = LogContext.with(LogContext.PROJECT_ID, job.projectId())) {
                meterOne(job);
                metered++;
            } catch (RuntimeException e) {
                // The lease expires and a later heartbeat reclaims it; the idempotent upsert makes the re-run safe.
                log.warn(Markers.OPS, "metering aggregation failed bucket={}", job.bucketStart(), e);
            }
        }
        if (metered > 0) {
            log.info(Markers.OPS, "metering tick metered={}", metered);
        }
    }

    /** Re-running a closed bucket overwrites each row with the same count. */
    void meterOne(MetricRollupJobRow job) {
        String from = job.bucketStart();
        String to = bucketEnd(job.bucketStart(), job.granularity());
        String createdAt = Instant.now().toString();
        upsertUnit(job, UsageUnit.INGESTED_SPANS, rollups.countIngestedSpans(job.projectId(), from, to), createdAt);
        upsertUnit(job, UsageUnit.INGESTED_TRACES, rollups.countIngestedTraces(job.projectId(), from, to), createdAt);
        upsertUnit(job, UsageUnit.L1_EVALS, rollups.countL1Evals(job.projectId(), from, to), createdAt);
        meterStorage(job, to, createdAt);
        jobs.markDone(job.id());
    }

    /**
     * A level, snapshotted as of the bucket boundary, so last-writer-wins is right. Gated off by default while the
     * billable basis is undecided.
     */
    private void meterStorage(MetricRollupJobRow job, String asOf, String createdAt) {
        if (!props.isStorageEnabled()) return; // basis-ratification fence
        upsertUnit(job, UsageUnit.STORAGE, rollups.snapshotStorageRows(job.projectId(), asOf), createdAt);
    }

    private void upsertUnit(MetricRollupJobRow job, UsageUnit unit, long value, String createdAt) {
        rollups.upsert(MetricRollupRow.of(
                Ids.ulid(),
                job.orgId(),
                job.projectId(),
                unit.wire(),
                value,
                job.bucketStart(),
                job.granularity(),
                createdAt));
    }

    private static String bucketEnd(String bucketStart, String granularity) {
        ChronoUnit step = BUCKET_DAY.equals(granularity) ? ChronoUnit.DAYS : ChronoUnit.HOURS;
        return Instant.parse(bucketStart).plus(1, step).toString();
    }
}
