// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.detector.Detection;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import ai.tessary.usage.MetricRollupJobRepository;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.MetricRollupRepository.UsageBucket;
import ai.tessary.usage.MetricRollupRepository.UsageTotal;
import ai.tessary.usage.UsageUnit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance for usage metering. Exercised against the real pgvector Postgres (Testcontainers)
 * so the metering schema applies for real. Seeds the already-committed billable rows (spans and
 * per-classifier detections) into a known CLOSED hour bucket, then drives the {@link MeteringWorker}
 * pipeline (schedule → SKIP-LOCKED claim → bounded aggregation → idempotent upsert) and asserts:
 *
 * <ol>
 *   <li>each metered unit is counted per (org, project) for the closed bucket;
 *   <li>re-running a closed bucket is idempotent (the unique upsert overwrites, never double-counts);
 *   <li>the claim is exhausted after a bucket is metered (no redundant re-scan);
 *   <li>the project-scoped timeseries and org-scoped totals read surfaces return the metered values;
 *   <li>the day grain is a raw re-aggregation of the window, not a sum of the hours beside it.
 * </ol>
 *
 * <p><b>Two whole assertion families left with Track A, and the coverage loss is real.</b> This test
 * used to seed grader {@code verdict} rows and assert on {@code l2_evals}, {@code llm_tokens} and the
 * per-environment rollup split — a unit-by-unit proof that L1 detections and L2 grader rulings were
 * counted from their own stores and never from each other, and that the per-env values summed back to
 * the project total. Grading and the Environment concept are both gone, and neither has a substitute
 * here: {@code metric_rollup} now carries one row per (project, unit, bucket), and the only two units
 * with a live producer are {@code ingested_spans} and {@code l1_evals}. Live LLM spend is unaffected —
 * it is read from the {@code llm_call} ledger, which this test never covered.
 */
@SpringBootTest
@Import(TurnGrainTestDetectionConfig.class)
class MeteringIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("tessary.metering.storage-enabled", () -> "true");
    }

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    MetricRollupJobRepository jobs;

    @Autowired
    MetricRollupRepository rollups;

    @Autowired
    MeteringService meteringService;

    @Autowired
    MeteringWorker worker;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    void metersEachUnitForAClosedBucket_idempotently_andExposesBothReadSurfaces() {
        var fix = TenantFixture.bootstrap(tenants, "metering");
        String orgId = fix.org().id();
        String pid = fix.project().id();

        // A fixed CLOSED hour bucket in the past, and an instant inside it for the seeded rows' created_at.
        Instant bucketStart = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
        String inBucket = bucketStart.plus(10, ChronoUnit.MINUTES).toString();
        String bucketStartIso = bucketStart.toString();

        // 2 ingested spans — reused as the real subjects of two of the three detections below.
        Obs obs1 = observationAt(pid, newSession(pid, inBucket), inBucket);
        Obs obs2 = observationAt(pid, newSession(pid, inBucket), inBucket);

        // 3 classifier detections. A detection carries NO tokens — the row has no token columns — so it
        // moves the L1 count and nothing else.
        String sessionId = newSession(pid, inBucket);
        seedSessionDetection(pid, "frustration", sessionId, inBucket);
        seedDetection(pid, "secret_leak", obs1, inBucket);
        seedDetection(pid, "secret_leak", obs2, inBucket);

        // ---- schedule + claim (the worker's coordination) ----
        int scheduled = jobs.scheduleDueBuckets(bucketStartIso, MeteringWorker.BUCKET_UNIT);
        assertTrue(scheduled >= 1, "a job is scheduled for the project's closed bucket");

        var claimed = jobs.claimBatch(50, 600);
        var job = claimed.stream()
                .filter(j -> j.projectId().equals(pid) && j.bucketStart().equals(bucketStartIso))
                .findFirst()
                .orElseThrow();

        // ---- aggregate the closed bucket (what MeteringWorker.meterOne does) ----
        String to = bucketStart.plus(1, ChronoUnit.HOURS).toString();
        assertEquals(2, rollups.countIngestedSpans(pid, bucketStartIso, to), "two ingested spans");
        assertEquals(
                3,
                rollups.countL1Evals(pid, bucketStartIso, to),
                "three classifier detections, read off the stitched detection view");

        worker.meterClaimedForTest(job);

        // ---- claim is exhausted (no redundant re-scan of a metered bucket) ----
        var afterDone = jobs.claimBatch(50, 600).stream()
                .filter(j -> j.projectId().equals(pid) && j.bucketStart().equals(bucketStartIso))
                .toList();
        assertTrue(afterDone.isEmpty(), "a done job is not re-claimed");

        // ---- idempotency: re-metering the same closed bucket is a no-op (overwrite, not add) ----
        worker.meterClaimedForTest(job);

        // ---- project-scoped timeseries read surface ----
        String from = bucketStart.minus(1, ChronoUnit.HOURS).toString();
        String until = bucketStart.plus(1, ChronoUnit.HOURS).toString();
        List<UsageBucket> series = meteringService.projectTimeseries(pid, UsageUnit.L1_EVALS.wire(), null, from, until);
        assertEquals(1, series.size(), "exactly one metered bucket in range");
        assertEquals(bucketStartIso, series.get(0).bucketStart());
        assertEquals(3, series.get(0).value(), "l1_evals value is stable across the re-upsert");

        // One row per (project, unit, bucket) — the per-environment fan-out is gone, so a second row
        // under the same key would mean the upsert's conflict target no longer matches its unique index.
        assertEquals(1, rollupRowCount(pid, UsageUnit.L1_EVALS.wire(), bucketStartIso));

        // ---- org-scoped totals read surface (billing reads from this) ----
        List<UsageTotal> totals = meteringService.orgTotals(orgId, from, until);
        assertEquals(2, totalFor(totals, UsageUnit.INGESTED_SPANS));
        assertEquals(3, totalFor(totals, UsageUnit.L1_EVALS));
    }

    /**
     * The DAY grain re-aggregates the raw window; it is not a sum of the hour rows beside it. Two spans
     * are seeded in two distinct hours of one closed UTC day; the hourly grain produces two buckets of 1,
     * the daily grain one bucket of 2.
     */
    @Test
    void dayRollupEqualsTheSumOfItsHours_viaRawReaggregation() {
        var fix = TenantFixture.bootstrap(tenants, "metering-day");
        String pid = fix.project().id();

        // A fixed CLOSED UTC day in the past, and two distinct hours within it.
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS);
        Instant hourA = dayStart.plus(1, ChronoUnit.HOURS);
        Instant hourB = dayStart.plus(5, ChronoUnit.HOURS);
        observationAt(
                pid,
                newSession(pid, hourA.toString()),
                hourA.plus(10, ChronoUnit.MINUTES).toString());
        observationAt(
                pid,
                newSession(pid, hourB.toString()),
                hourB.plus(10, ChronoUnit.MINUTES).toString());

        // ---- hourly grain: two buckets of 1 ----
        meterClosedBucket(pid, hourA.toString(), MeteringWorker.BUCKET_HOUR);
        meterClosedBucket(pid, hourB.toString(), MeteringWorker.BUCKET_HOUR);
        // ---- daily grain: one bucket of 2 (raw re-aggregation over the whole day) ----
        meterClosedBucket(pid, dayStart.toString(), MeteringWorker.BUCKET_DAY);

        String from = dayStart.minus(1, ChronoUnit.DAYS).toString();
        String to = dayStart.plus(1, ChronoUnit.DAYS).toString();

        List<UsageBucket> hourly =
                meteringService.projectTimeseries(pid, UsageUnit.INGESTED_SPANS.wire(), "hour", from, to);
        assertEquals(2, hourly.size(), "two hourly buckets (one per seeded hour)");
        long hourlySum = hourly.stream().mapToLong(UsageBucket::value).sum();
        assertEquals(2, hourlySum, "two spans across the two hours");

        List<UsageBucket> daily =
                meteringService.projectTimeseries(pid, UsageUnit.INGESTED_SPANS.wire(), "day", from, to);
        assertEquals(1, daily.size(), "one daily bucket");
        assertEquals(dayStart.toString(), daily.get(0).bucketStart(), "the day bucket starts at the UTC day boundary");
        assertEquals(hourlySum, daily.get(0).value(), "the day rollup equals the sum of its hourly rollups");
    }

    /**
     * The {@code storage} unit is a LEVEL — a {@code COUNT(*)} snapshot of span rows at rest as of the
     * bucket end — produced when {@code tessary.metering.storage-enabled} is on and queryable like every
     * other unit. Idempotent: re-snapshotting the same closed bucket overwrites with the same level
     * (last-writer-wins), never accumulates.
     */
    @Test
    void metersStorageAsASnapshotLevel_idempotently() {
        var fix = TenantFixture.bootstrap(tenants, "metering-storage");
        String pid = fix.project().id();

        Instant bucketStart = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
        String inBucket = bucketStart.plus(10, ChronoUnit.MINUTES).toString();
        String to = bucketStart.plus(1, ChronoUnit.HOURS).toString();

        observationAt(pid, newSession(pid, inBucket), inBucket);
        observationAt(pid, newSession(pid, inBucket), inBucket);
        observationAt(pid, newSession(pid, inBucket), inBucket);

        // The snapshot is taken AS-OF the bucket end (everything created before `to`).
        assertEquals(3, rollups.snapshotStorageRows(pid, to), "three rows at rest");

        // Drive the real worker pipeline (storage-enabled in props) for the closed bucket, twice.
        jobs.scheduleDueBuckets(bucketStart.toString(), MeteringWorker.BUCKET_HOUR);
        var job = jobs.claimBatch(50, 600).stream()
                .filter(j -> j.projectId().equals(pid) && j.bucketStart().equals(bucketStart.toString()))
                .findFirst()
                .orElseThrow();
        worker.meterClaimedForTest(job);
        worker.meterClaimedForTest(job); // re-run: idempotent overwrite, not accumulation

        String from = bucketStart.minus(1, ChronoUnit.HOURS).toString();
        String until = bucketStart.plus(1, ChronoUnit.HOURS).toString();
        List<UsageBucket> series =
                meteringService.projectTimeseries(pid, UsageUnit.STORAGE.wire(), "hour", from, until);
        assertEquals(1, series.size(), "one storage bucket");
        assertEquals(3, series.get(0).value(), "storage level is the 3 rows at rest, stable across the re-run");
    }

    /** Meter one closed bucket end-to-end through the worker (schedule → claim → aggregate + upsert). */
    private void meterClosedBucket(String pid, String bucketStart, String granularity) {
        jobs.scheduleDueBuckets(bucketStart, granularity);
        var job = jobs.claimBatch(50, 600).stream()
                .filter(j -> j.projectId().equals(pid)
                        && j.bucketStart().equals(bucketStart)
                        && j.granularity().equals(granularity))
                .findFirst()
                .orElseThrow();
        worker.meterClaimedForTest(job);
    }

    private long rollupRowCount(String pid, String unit, String bucketStart) {
        Long n = jdbc.sql("""
                SELECT COUNT(*) FROM metric_rollup
                WHERE project_id = :pid AND metric = :unit AND bucket_start = :bstart
                """)
                .param("pid", pid)
                .param("unit", unit)
                .param("bstart", bucketStart)
                .query(Long.class)
                .single();
        return n == null ? 0 : n;
    }

    private static long totalFor(List<UsageTotal> totals, UsageUnit unit) {
        return totals.stream()
                .filter(t -> t.metric().equals(unit.wire()))
                .mapToLong(UsageTotal::value)
                .sum();
    }

    // ---- seeding helpers ---------------------------------------------------------------------------

    private String newSession(String pid, String at) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.session(pid, sessionId, Instant.parse(at));
        return sessionId;
    }

    /** A seeded span together with the session and trace it hangs off (for FK-safe detections). */
    private record Obs(String sessionId, String traceId, String spanId) {}

    /**
     * One ingested span, in its own trace.
     *
     * <p>{@code created_at} is back-dated to {@code at}. It has to be: metering windows on the INGEST
     * clock, and a span written now would land in the current hour rather than the closed bucket every
     * assertion here is about.
     */
    private Obs observationAt(String pid, String sessionId, String at) {
        Instant when = Instant.parse(at);
        SpanRow span = fx.spanSeed(pid)
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(when)
                .payload("in", "out")
                .write();
        fx.ingestedAt(span, when);
        return new Obs(sessionId, span.traceId(), span.id());
    }

    /** The classifier row a detection's FK needs, created once per (project, key). */
    private String classifier(String pid, String classifierKey) {
        return jdbc.sql("INSERT INTO classifier (id, project_id, classifier_key, name, detector, built_in, version,"
                        + " enabled, created_at, updated_at)"
                        + " VALUES (:id, :pid, :key, :key, 'keyword', true, 1, true, :now, :now)"
                        + " ON CONFLICT (project_id, classifier_key) DO UPDATE SET updated_at = EXCLUDED.updated_at"
                        + " RETURNING id")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("key", classifierKey)
                .param("now", Instant.now().toString())
                .query(String.class)
                .single();
    }

    /**
     * A classifier detection over a REAL span subject, in the classifier's own table. Both halves of the
     * span key are stored because that pair IS a span's identity. It carries no tokens — a detection row
     * has no token columns at all — so it counts as an L1 eval and nothing else.
     */
    private void seedDetection(String pid, String classifierKey, Obs subject, String at) {
        insertDetection(pid, classifierKey, subject.sessionId(), subject.traceId(), subject.spanId(), at);
    }

    /**
     * A detection whose finest subject is a TRACE. Session-only detections are not expressible: every
     * detection table requires a trace, because every classifier that writes one judges something inside
     * a turn.
     */
    private void seedSessionDetection(String pid, String classifierKey, String sessionId, String at) {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(pid, traceId, sessionId, Instant.parse(at));
        insertDetection(pid, classifierKey, sessionId, traceId, null, at);
    }

    private void insertDetection(
            String pid, String classifierKey, String sessionId, String traceId, @Nullable String spanId, String at) {
        // Route by DetectionTableRegistry's actual registration, not a hard-coded guess: "frustration"
        // is only registered in this test JVM by TurnGrainTestDetectionConfig (no paid module is on
        // backend/app's test classpath), so a trace-grain (spanId == null) row belongs in ITS table —
        // writing straight to the real open baseline frustration table would insert rows
        // MetricRollupRepository's registry-built union never reads, silently undercounting.
        String table = spanId == null ? TurnGrainTestDetectionConfig.TABLE : "secret_leak_detection";
        jdbc.sql("INSERT INTO " + table + " (id, project_id, classifier_id, classifier_key, subject_session_id,"
                        + " subject_trace_id, subject_span_id, severity, confidence, created_at)"
                        + " VALUES (:id, :pid, :sid, :key, :ses, :trace, :span, :sev, :conf, :at::timestamptz)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("sid", classifier(pid, classifierKey))
                .param("key", classifierKey)
                .param("ses", sessionId)
                .param("trace", traceId)
                .param("span", spanId)
                .param("sev", Detection.Severity.WARN)
                .param("conf", Detection.Confidence.HIGH)
                .param("at", at)
                .update();
    }
}
