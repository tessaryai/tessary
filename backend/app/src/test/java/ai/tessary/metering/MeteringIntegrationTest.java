// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.billing.BillingController;
import ai.tessary.billing.BillingController.UsageLine;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.metering.MeteringDtos.LlmUsageCellView;
import ai.tessary.metering.MeteringDtos.LlmUsageSeriesView;
import ai.tessary.metering.MeteringDtos.LlmUsageSliceView;
import ai.tessary.metering.MeteringDtos.LlmUsageView;
import ai.tessary.metering.MeteringDtos.TriageSpendRowView;
import ai.tessary.metering.MeteringDtos.TriageSpendView;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.usage.LlmCallRow;
import ai.tessary.usage.LlmCallWriteRepository;
import ai.tessary.usage.MetricRollupJobRepository;
import ai.tessary.usage.MetricRollupRepository;
import ai.tessary.usage.MetricRollupRepository.UsageBucket;
import ai.tessary.usage.MetricRollupRepository.UsageTotal;
import ai.tessary.usage.MetricRollupRow;
import ai.tessary.usage.UsageUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * <p>{@code metric_rollup} carries one row per (project, unit, bucket), and the only two units with
 * a live producer are {@code ingested_spans} and {@code l1_evals}, which is what this test covers.
 *
 * <p>Live LLM spend is read from the {@code llm_call} ledger rather than the rollup. The ledger tests
 * below seed a fixed window of calls across two projects of one org (plus one call outside the window
 * and one in another org) and read it through {@code BillingController}, so the SQL, the org scoping and
 * the wire mapping are exercised together.
 */
@SpringBootTest
class MeteringIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
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

    @Autowired
    LlmCallWriteRepository llmCalls;

    @Autowired
    BillingController billing;

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

        // 2 ingested spans, reused as the real subjects of two of the three detections below.
        Obs obs1 = observationAt(pid, newSession(pid, inBucket), inBucket);
        Obs obs2 = observationAt(pid, newSession(pid, inBucket), inBucket);

        // 3 classifier detections. A detection carries no tokens (the row has no token columns), so
        // it moves the L1 count and nothing else.
        String sessionId = newSession(pid, inBucket);
        seedSessionDetection(pid, "frustration", sessionId, inBucket);
        seedDetection(pid, "secret_leak", obs1, inBucket);
        seedDetection(pid, "secret_leak", obs2, inBucket);

        // ---- schedule + claim (the worker's coordination) ----
        int scheduled = jobs.scheduleDueBuckets(bucketStartIso, MeteringWorker.BUCKET_HOUR);
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

        worker.meterOne(job);

        // ---- claim is exhausted (no redundant re-scan of a metered bucket) ----
        var afterDone = jobs.claimBatch(50, 600).stream()
                .filter(j -> j.projectId().equals(pid) && j.bucketStart().equals(bucketStartIso))
                .toList();
        assertTrue(afterDone.isEmpty(), "a done job is not re-claimed");

        // ---- idempotency: re-metering the same closed bucket is a no-op (overwrite, not add) ----
        worker.meterOne(job);

        // ---- project-scoped timeseries read surface ----
        String from = bucketStart.minus(1, ChronoUnit.HOURS).toString();
        String until = bucketStart.plus(1, ChronoUnit.HOURS).toString();
        List<UsageBucket> series = meteringService.projectTimeseries(pid, UsageUnit.L1_EVALS.wire(), null, from, until);
        assertEquals(1, series.size(), "exactly one metered bucket in range");
        assertEquals(bucketStartIso, series.get(0).bucketStart());
        assertEquals(3, series.get(0).value(), "l1_evals value is stable across the re-upsert");

        // One row per (project, unit, bucket): the per-environment fan-out is gone, so a second row
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
     * The {@code storage} unit is a level: a {@code COUNT(*)} snapshot of span rows at rest as of
     * the bucket end, produced when {@code tessary.metering.storage-enabled} is on and queryable like every
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
        worker.meterOne(job);
        worker.meterOne(job); // re-run: idempotent overwrite, not accumulation

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
        worker.meterOne(job);
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
     * span key are stored because that pair IS a span's identity. It carries no tokens (a detection
     * row has no token columns at all), so it counts as an L1 eval and nothing else.
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
        // A trace-grain (spanId == null) row is a frustration detection; a span-grain one goes to
        // secret leak's table. Both are registered, so MetricRollupRepository's union reads them.
        String table = spanId == null ? "frustration_detection" : "secret_leak_detection";
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

    // ---- the llm_call ledger, read through the billing endpoints --------------------------------------

    /** The ledger window every ledger test reads: two whole UTC days. */
    private static final String DAY_1 = "2026-03-10T00:00:00Z";

    private static final String DAY_2 = "2026-03-11T00:00:00Z";

    private static final String WINDOW_END = "2026-03-12T00:00:00Z";

    /** The seeded org: an owner, and the two projects its calls are split across. */
    private record Ledger(TenantContext owner, String orgSlug, Project p1, Project p2) {}

    /**
     * Four calls in the window, split so every axis has two keys:
     *
     * <ul>
     *   <li>day 1, p1, lane triage, model m-1, platform-funded: 100 in + 50 out, $0.10, ruling on finding F1
     *   <li>day 1, p1, lane triage, model m-1, BYO: 200 in + 100 out + 10 cache read + 5 cache write, $0.20, F1
     *       again (a re-triage)
     *   <li>day 2, p2, lane observer (no {@code ModelLane}), no model, BYO: 1000 in, unpriced
     *   <li>day 2, p1, lane triage, model m-1, BYO: 10 in + 10 out, $0.05, ruling on finding F2
     * </ul>
     *
     * plus one p1 call after the window and one call in another org inside it, which no read may count.
     */
    private Ledger seedLedger() {
        var fix = TenantFixture.bootstrap(tenants, "ledger");
        Project p1 = fix.project();
        Project p2 = tenants.createProject(fix.org().id(), "ledger-second", null);
        call(p1.id(), "triage", "m-1", "platform", 100, 50, 0, 0, "0.10", "F1", "2026-03-10T01:00:00Z");
        call(p1.id(), "triage", "m-1", "byo", 200, 100, 10, 5, "0.20", "F1", "2026-03-10T02:00:00Z");
        call(p2.id(), "observer", null, "byo", 1000, 0, 0, 0, null, null, "2026-03-11T03:00:00Z");
        call(p1.id(), "triage", "m-1", "byo", 10, 10, 0, 0, "0.05", "F2", "2026-03-11T04:00:00Z");
        call(p1.id(), "triage", "m-1", "byo", 7, 7, 0, 0, "9.00", "F3", "2026-03-15T00:00:00Z");
        String otherOrgProject =
                TenantFixture.bootstrap(tenants, "ledger-other").project().id();
        call(otherOrgProject, "triage", "m-1", "byo", 5, 5, 0, 0, "7.00", "F4", "2026-03-10T05:00:00Z");
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        return new Ledger(owner, fix.org().slug(), p1, p2);
    }

    private void call(
            String projectId,
            String lane,
            @Nullable String model,
            String funding,
            int in,
            int out,
            int cacheRead,
            int cacheWrite,
            @Nullable String cost,
            @Nullable String findingId,
            String at) {
        llmCalls.insert(new LlmCallRow(
                Ids.ulid(),
                projectId,
                lane,
                model,
                null,
                funding,
                in,
                out,
                cacheRead,
                cacheWrite,
                cost == null ? null : new BigDecimal(cost),
                null,
                null,
                findingId == null ? null : "behavior_finding",
                findingId,
                at));
    }

    private static LlmUsageSliceView slice(
            String key,
            @Nullable String label,
            long calls,
            long in,
            long out,
            long cacheRead,
            long cacheWrite,
            String cost,
            String platform,
            String byo,
            long unpriced) {
        return new LlmUsageSliceView(
                key,
                label,
                calls,
                in,
                out,
                cacheRead,
                cacheWrite,
                in + out + cacheRead + cacheWrite,
                new BigDecimal(cost),
                new BigDecimal(platform),
                new BigDecimal(byo),
                unpriced);
    }

    private static LlmUsageCellView cell(
            String bucket,
            String key,
            @Nullable String label,
            long calls,
            long in,
            long out,
            long cr,
            long cw,
            String cost) {
        return new LlmUsageCellView(
                bucket, key, label, calls, in, out, cr, cw, in + out + cr + cw, new BigDecimal(cost));
    }

    /** The whole window: four calls, $0.35 priced (0.10 platform, 0.25 BYO), one call unpriced. */
    private static LlmUsageSliceView windowTotal() {
        return slice("", null, 4, 1310, 160, 10, 5, "0.3500000000", "0.1000000000", "0.2500000000", 1);
    }

    /** The p1 / triage / m-1 calls: three calls, $0.35, all priced. */
    private static LlmUsageSliceView triageSide(String key, @Nullable String label) {
        return slice(key, label, 3, 310, 160, 10, 5, "0.3500000000", "0.1000000000", "0.2500000000", 0);
    }

    /** The one p2 / observer / no-model call: unpriced, so it adds tokens and a blind spot, never cost. */
    private static LlmUsageSliceView observerSide(String key, @Nullable String label) {
        return slice(key, label, 1, 1000, 0, 0, 0, "0", "0", "0", 1);
    }

    /**
     * The breakdown behind the {@code llm_tokens} bill: one org total and the same calls cut by lane, project
     * and model, busiest first, with the four token buckets and the two funding sides kept apart. Only this
     * org's calls inside {@code [from, to)} count; the unpriced call adds to {@code unpriced_calls} rather than
     * reading as free; a call that reported no model groups under the empty key; an unknown lane keeps its row.
     */
    @Test
    void theLedgerBreakdownCutsTheOrgsWindowByLaneProjectAndModel() {
        Ledger l = seedLedger();

        LlmUsageView view =
                billing.getLlmUsage(l.owner(), l.orgSlug(), DAY_1, WINDOW_END).data();

        assertEquals(
                new LlmUsageView(
                        DAY_1,
                        WINDOW_END,
                        view.asOf(),
                        windowTotal(),
                        List.of(observerSide("observer", null), triageSide("triage", "Triage")),
                        List.of(observerSide(l.p2().id(), l.p2().name()), triageSide(l.p1().id(), l.p1().name())),
                        List.of(observerSide("", null), triageSide("m-1", null))),
                view);
    }

    /**
     * The usage chart: the full bucket axis over the window and one cell per non-empty (bucket, series), cut by
     * each grouping and narrowed by each filter. The week grain buckets on the ISO Monday (2026-03-10 is a
     * Tuesday, so its week starts 2026-03-09); a filter on the empty model key matches the call that reported
     * no model; the headline total always covers the same filtered window as the bars.
     */
    @Test
    void theLedgerSeriesBucketsTheWindowByEveryGrainAndGrouping() {
        Ledger l = seedLedger();

        LlmUsageSeriesView byProject = billing.getLlmUsageSeries(
                        l.owner(), l.orgSlug(), DAY_1, WINDOW_END, "day", "project", null, null, null)
                .data();
        assertEquals(
                new LlmUsageSeriesView(
                        DAY_1,
                        WINDOW_END,
                        "day",
                        "project",
                        byProject.asOf(),
                        windowTotal(),
                        List.of(DAY_1, DAY_2),
                        List.of(
                                cell(DAY_1, l.p1().id(), l.p1().name(), 2, 300, 150, 10, 5, "0.3000000000"),
                                cell(DAY_2, l.p2().id(), l.p2().name(), 1, 1000, 0, 0, 0, "0"),
                                cell(DAY_2, l.p1().id(), l.p1().name(), 1, 10, 10, 0, 0, "0.0500000000"))),
                byProject);

        LlmUsageSeriesView triageByModelWeekly = billing.getLlmUsageSeries(
                        l.owner(), l.orgSlug(), DAY_1, WINDOW_END, "week", "model", "triage", null, null)
                .data();
        assertEquals(List.of("2026-03-09T00:00:00Z"), triageByModelWeekly.buckets());
        assertEquals(
                List.of(cell("2026-03-09T00:00:00Z", "m-1", null, 3, 310, 160, 10, 5, "0.3500000000")),
                triageByModelWeekly.cells());
        assertEquals(triageSide("", null), triageByModelWeekly.total());

        LlmUsageSeriesView noModelByLaneHourly = billing.getLlmUsageSeries(
                        l.owner(), l.orgSlug(), DAY_1, WINDOW_END, "hour", "lane", null, null, "")
                .data();
        assertEquals(48, noModelByLaneHourly.buckets().size(), "two days of hours, empty ones included");
        assertEquals(DAY_1, noModelByLaneHourly.buckets().get(0));
        assertEquals("2026-03-11T23:00:00Z", noModelByLaneHourly.buckets().get(47));
        assertEquals(
                List.of(cell("2026-03-11T03:00:00Z", "observer", null, 1, 1000, 0, 0, 0, "0")),
                noModelByLaneHourly.cells());
        assertEquals(observerSide("", null), noModelByLaneHourly.total());

        LlmUsageSeriesView p2Default = billing.getLlmUsageSeries(
                        l.owner(), l.orgSlug(), DAY_1, WINDOW_END, null, null, null, l.p2().id(), null)
                .data();
        assertEquals("day", p2Default.grain());
        assertEquals("none", p2Default.grouping());
        assertEquals(List.of(cell(DAY_2, "", null, 1, 1000, 0, 0, 0, "0")), p2Default.cells());
    }

    /**
     * The per-ruling triage spend: the triage lane's cost over its run count ($0.35 over three runs is
     * $0.116667, half-up), and the rulings listed costliest first with a re-triaged finding's two runs summed.
     * {@code limit} is clamped to at least one, so {@code limit=0} lists the costliest ruling instead of
     * nothing, while the aggregate still covers the whole window.
     */
    @Test
    void theTriageSpendPricesOneRulingAndListsTheCostliestFirst() {
        Ledger l = seedLedger();

        TriageSpendView all = billing.getTriageSpend(l.owner(), l.orgSlug(), DAY_1, WINDOW_END, 50)
                .data();
        TriageSpendView clamped = billing.getTriageSpend(l.owner(), l.orgSlug(), DAY_1, WINDOW_END, 0)
                .data();

        TriageSpendRowView f1 =
                new TriageSpendRowView("F1", 2, 465, new BigDecimal("0.3000000000"), 0, "2026-03-10T02:00:00Z");
        TriageSpendRowView f2 =
                new TriageSpendRowView("F2", 1, 20, new BigDecimal("0.0500000000"), 0, "2026-03-11T04:00:00Z");
        assertEquals(
                new TriageSpendView(
                        DAY_1,
                        WINDOW_END,
                        all.asOf(),
                        3,
                        new BigDecimal("0.3500000000"),
                        new BigDecimal("0.116667"),
                        0,
                        List.of(f1, f2)),
                all);
        assertEquals(List.of(f1), clamped.byRuling());
        assertEquals(3, clamped.rulings(), "a truncated list must not truncate the aggregate");
    }

    /**
     * The billing summary totals the org's rollups at the HOUR grain only. The day rows re-aggregate the same
     * producer rows as the hours beside them, so a grain-agnostic total would bill the same spans twice.
     */
    @Test
    void theBillingSummaryTotalsTheHourGrainOnly() {
        var fix = TenantFixture.bootstrap(tenants, "billing-summary");
        String orgId = fix.org().id();
        String pid = fix.project().id();
        String now = Instant.now().toString();
        rollups.upsert(MetricRollupRow.of(
                Ids.ulid(), orgId, pid, UsageUnit.INGESTED_SPANS.wire(), 5, "2026-03-10T01:00:00Z", "hour", now));
        rollups.upsert(MetricRollupRow.of(
                Ids.ulid(), orgId, pid, UsageUnit.INGESTED_SPANS.wire(), 7, "2026-03-10T00:00:00Z", "day", now));
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);

        var summary =
                billing.getBilling(owner, fix.org().slug(), DAY_1, WINDOW_END).data();

        assertEquals(List.of(new UsageLine(UsageUnit.INGESTED_SPANS.wire(), 5)), summary.usage());
    }
}
