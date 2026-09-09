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
 * The async usage-metering worker. On an operational heartbeat it (1) schedules a per-(project,
 * bucket) job for the most-recent CLOSED hour bucket, (2) claims a bounded batch of due jobs with
 * {@code FOR UPDATE SKIP LOCKED} (so running N backends is safe and no two run the same scan), and (3) for
 * each claimed job runs bounded aggregation queries over already-committed rows ({@code observation} /
 * {@code verdict} / {@code signal_event}) for that closed bucket and idempotently upserts the per-unit
 * values into {@code metric_rollup}, then marks the job done.
 *
 * <p>Strictly off the ingest/grade hot path — it reads committed rows on a schedule (the {@code AlertWorker}
 * model), never tees the write path. Counting a CLOSED bucket makes the aggregate stable and the
 * {@code (org, project, env, unit, bucket_start, granularity)} upsert idempotent: a re-run is a no-op.
 *
 * <p>On by default — usage metering is infrastructure for billing quotas, always on; no enablement gate.
 *
 * <p><b>Grains.</b> Each heartbeat schedules the most-recent CLOSED <b>hour</b> and the most-recent CLOSED
 * <b>day</b>. A {@code day} rollup re-aggregates the same producer rows over the wider
 * {@code [dayStart, dayStart+24h)} window (not a sum of the 24 hourly rollups), so it carries no
 * rollup-of-rollups ordering/lateness semantics and lands in a separate {@code granularity='day'} key space.
 *
 * <p><b>Freshness, and what quota enforcement does about it.</b> Because only closed buckets are metered,
 * usage lags by up to one bucket grain plus one heartbeat. This is correct for billing (invoices cover closed
 * periods). Quota enforcement ({@code ingest/IngestQuotaGate}) reads this lagged rollup ANYWAY and accepts the
 * overshoot rather than live-querying the source tables for the open bucket: the caps are monthly, so the
 * tolerance is roughly an hour in a month, and closing it would mean an unbounded aggregate on the ingest hot
 * path. The consequence to know is that a freshly-lowered cap does not bite until the current bucket is
 * metered, and an org already past its cap keeps ingesting for that long.
 *
 * <p>Each closed (project, bucket) is metered as one idempotent upsert per unit. Re-running a closed
 * bucket overwrites each row with the same stable count (see {@link MetricRollupRepository}).
 *
 * <p>The {@code storage} unit is a LEVEL, not an event stream: it is snapshotted as a
 * {@code COUNT(*)} of ingested-observation rows at rest AS-OF the bucket end, not counted
 * over the bucket window. It is gated behind {@code tessary.metering.storage-enabled} (default off) because
 * the billable basis (rows vs bytes vs retention-days) is an open billing product decision.
 */
@Component
public class MeteringWorker {

    private static final Logger log = LoggerFactory.getLogger(MeteringWorker.class);

    /** The hourly grain — the most-recent CLOSED hour is metered every heartbeat. */
    static final String BUCKET_HOUR = UsageUnit.BUCKET_HOUR;

    /**
     * The daily grain. A {@code day} rollup re-aggregates the SAME producer rows as the hour
     * grain over the wider {@code [dayStart, dayStart+24h)} window — NOT a sum-of-24-hourly-rollups — so it
     * needs no rollup-of-rollups ordering/lateness semantics and the existing idempotent upsert is unchanged
     * (a {@code day} row is a separate key space from the {@code hour} rows via {@code granularity}). See
     * {@code decision.md}.
     */
    static final String BUCKET_DAY = UsageUnit.BUCKET_DAY;

    /**
     * Back-compat alias for the original hourly constant ({@code MeteringService} / tests still reference it).
     * Kept as {@link #BUCKET_HOUR}.
     */
    static final String BUCKET_UNIT = BUCKET_HOUR;

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
                // No jobs get scheduled this heartbeat — a genuine, actionable failure, not
                // expected/retriable (the next heartbeat retries, but nothing here degrades gracefully).
                log.error(Markers.OPS, "metering scheduling failed", e);
            }
            meterClaimedJobs();
        }
    }

    /** Schedule a job for the most-recent CLOSED hour bucket (the hour strictly before the current one). */
    private void scheduleClosedHour(Instant now) {
        Instant closedBucket = now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        int scheduled = jobs.scheduleDueBuckets(closedBucket.toString(), BUCKET_HOUR);
        if (scheduled > 0) {
            log.info(Markers.OPS, "metering scheduled hour jobs={} bucket={}", scheduled, closedBucket);
        }
    }

    /**
     * Schedule a job for the most-recent CLOSED day bucket — the UTC day strictly before today.
     * Re-scheduling the same day every heartbeat is a no-op (the job queue's {@code ON CONFLICT DO NOTHING}
     * on {@code (project, bucket_start, granularity)}), so the day is metered once when first closed.
     */
    private void scheduleClosedDay(Instant now) {
        Instant closedBucket = now.truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);
        int scheduled = jobs.scheduleDueBuckets(closedBucket.toString(), BUCKET_DAY);
        if (scheduled > 0) {
            log.info(Markers.OPS, "metering scheduled day jobs={} bucket={}", scheduled, closedBucket);
        }
    }

    /** Claim and aggregate a bounded batch of due jobs. */
    private void meterClaimedJobs() {
        List<MetricRollupJobRow> due;
        try {
            due = jobs.claimBatch(props.getClaimBatch(), props.getLeaseSeconds());
        } catch (RuntimeException e) {
            // Zero jobs get claimed/metered this heartbeat — a genuine, actionable failure.
            log.error(Markers.OPS, "metering claim failed", e);
            return;
        }
        int metered = 0;
        for (MetricRollupJobRow job : due) {
            try (LogContext ignored = LogContext.with(LogContext.PROJECT_ID, job.projectId())) {
                meterOne(job);
                metered++;
            } catch (RuntimeException e) {
                // Leave the job claimed; its lease expires and a later heartbeat reclaims it. The idempotent
                // upsert makes a re-run safe, so a transient failure is retried, not silently dropped.
                log.warn(Markers.OPS, "metering aggregation failed bucket={}", job.bucketStart(), e);
            }
        }
        if (metered > 0) {
            log.info(Markers.OPS, "metering tick metered={}", metered);
        }
    }

    /**
     * Test seam: run the production {@link #meterOne} aggregation for one already-claimed job (so day-grain
     * windowing and storage gating both run through real code in integration tests).
     */
    void meterClaimedForTest(MetricRollupJobRow job) {
        meterOne(job);
    }

    /**
     * Aggregate one closed (project, bucket) and upsert one row per metered unit, then mark the job done.
     * Re-running a closed bucket overwrites each row with the same stable count — a no-op.
     *
     * <p><b>Four units left with Track A</b> and are not re-sourced: {@code l2_evals},
     * {@code llm_tokens} and both {@code llm_cost_micro_usd_*} were all aggregated {@code FROM verdict},
     * a table grading took with it. Per-lane, per-model LLM spend is unaffected — that view reads the
     * {@code llm_call} ledger through {@code LlmUsageQueryRepository}, which this worker never touched.
     */
    private void meterOne(MetricRollupJobRow job) {
        String from = job.bucketStart();
        String to = bucketEnd(job.bucketStart(), job.granularity());
        String createdAt = Instant.now().toString();
        upsertUnit(job, UsageUnit.INGESTED_SPANS, rollups.countIngestedSpans(job.projectId(), from, to), createdAt);
        upsertUnit(job, UsageUnit.L1_EVALS, rollups.countL1Evals(job.projectId(), from, to), createdAt);
        meterStorage(job, to, createdAt);
        jobs.markDone(job.id());
    }

    /**
     * Snapshot the project's storage LEVEL as of the bucket's end and upsert it as the
     * {@code storage} unit. Unlike the event-stream units, storage is not a closed-bucket
     * COUNT over {@code [from, to)} — it is a level (rows at rest), so it is a {@code COUNT(*)} snapshot taken
     * AS-OF the bucket boundary {@code asOf}. The upsert's {@code DO UPDATE SET value = EXCLUDED.value} is
     * last-writer-wins, which is exactly right for a level: re-running a bucket overwrites the snapshot.
     *
     * <p>Gated behind {@code tessary.metering.storage-enabled} (default off): the billable BASIS (rows vs bytes
     * vs retention-days) is an open billing product decision, so the slot stays reserved until a deployment
     * opts in. The v1 basis is <b>ingested-observation rows at rest</b> — the durable, already-counted
     * substrate level.
     */
    private void meterStorage(MetricRollupJobRow job, String asOf, String createdAt) {
        if (!props.isStorageEnabled()) return; // basis-ratification fence
        upsertUnit(job, UsageUnit.STORAGE, rollups.snapshotStorageRows(job.projectId(), asOf), createdAt);
    }

    /** Upsert the one {@code metric_rollup} row this unit's aggregation produced. */
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

    /**
     * The exclusive end of a bucket: {@code bucketStart + 1h} for an {@code hour} grain, {@code bucketStart +
     * 24h} for a {@code day} grain. A {@code day} job therefore re-aggregates the producer rows
     * over the whole closed day with the unchanged aggregation queries — no rollup-of-rollups.
     */
    private static String bucketEnd(String bucketStart, String granularity) {
        ChronoUnit step = BUCKET_DAY.equals(granularity) ? ChronoUnit.DAYS : ChronoUnit.HOURS;
        return Instant.parse(bucketStart).plus(1, step).toString();
    }
}
