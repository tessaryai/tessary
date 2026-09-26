// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The per-classifier daily trace-volume read behind {@code GET /classifiers/metrics/daily}, against real Postgres:
 * UTC-day buckets, distinct-trace counting, deleted traces out of the denominator, zero-filled aligned arrays
 * (including a classifier that never fired), and the {@code days} clamp.
 */
@SpringBootTest
class ClassifierDailyVolumeIntegrationTest {

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    ClassifierService service;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    void dailyVolumeCountsDistinctTracesPerUtcDayAgainstNonDeletedTotals() {
        String pid = TenantFixture.bootstrap(tenants, "classifier-daily-volume")
                .project()
                .id();
        // Project creation seeds the catalog, and this asserts the exact definition set, so it owns the list.
        jdbc.sql("DELETE FROM classifier WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        // Midday instants stay inside their UTC day even across midnight.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = today.minusDays(1);
        Instant todayMid = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant yesterdayMid = yesterday.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant outsideWindow = today.minusDays(10).atTime(12, 0).toInstant(ZoneOffset.UTC);

        String sessionId = SubstrateV2Fixtures.sessionId();

        // Yesterday: A and B, plus a deleted trace outside the denominator. Buckets use event time, so a backfill
        // lands on the days it ran.
        String traceA = seedTrace(pid, sessionId, yesterdayMid);
        String traceB = seedTrace(pid, sessionId, yesterdayMid);
        String deleted = seedTrace(pid, sessionId, yesterdayMid);
        jdbc.sql("UPDATE trace SET is_deleted = true WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", deleted)
                .update();
        String traceC = seedTrace(pid, sessionId, todayMid);

        String fired = insertClassifier(pid, "secret_leak", "Secret leak");
        String silent = insertClassifier(pid, "quiet", "Quiet");

        // Two detections on trace A are one flagged trace.
        insertDetection(pid, fired, "secret_leak", sessionId, traceA, SubstrateV2Fixtures.spanId(), yesterdayMid);
        insertDetection(
                pid,
                fired,
                "secret_leak",
                sessionId,
                traceA,
                SubstrateV2Fixtures.spanId(),
                yesterdayMid.plusSeconds(60));
        insertDetection(pid, fired, "secret_leak", sessionId, traceB, SubstrateV2Fixtures.spanId(), yesterdayMid);
        insertDetection(pid, fired, "secret_leak", sessionId, traceC, SubstrateV2Fixtures.spanId(), todayMid);
        // Outside the 7-day window.
        insertDetection(pid, fired, "secret_leak", sessionId, traceA, SubstrateV2Fixtures.spanId(), outsideWindow);

        ClassifierService.DailyVolume v = service.dailyVolume(pid, 7);

        assertEquals(7, v.days().size(), "one bucket per requested day");
        assertEquals(7, v.traceTotals().length, "trace totals align with days");
        int yIdx = v.days().indexOf(yesterday.toString());
        int tIdx = v.days().indexOf(today.toString());
        assertTrue(yIdx >= 0 && tIdx > yIdx, "window covers yesterday and today, oldest first");

        long[] expectedTotals = new long[7];
        expectedTotals[yIdx] = 2; // A + B; the deleted trace is excluded
        expectedTotals[tIdx] = 1;
        assertArrayEquals(expectedTotals, v.traceTotals(), "denominator counts non-deleted traces per STARTED day");

        assertEquals(2, v.classifiers().size(), "every definition gets an entry, silent ones included");
        long[] firedCounts = countsFor(v, fired);
        assertEquals(2, firedCounts[yIdx], "two distinct traces flagged yesterday (double-fire on A counts once)");
        assertEquals(1, firedCounts[tIdx], "one trace flagged today");
        assertEquals(3, sum(firedCounts), "nothing outside the window leaks in");
        assertArrayEquals(new long[7], countsFor(v, silent), "a classifier that never fired is a flat zero strip");
    }

    @Test
    void aBackfillChartsOnTheDaysTheSpansRan() {
        String pid = TenantFixture.bootstrap(tenants, "classifier-daily-backfill")
                .project()
                .id();
        jdbc.sql("DELETE FROM classifier WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate backfillDay = today.minusDays(3);
        Instant sweepNow = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant backfillMid = backfillDay.atTime(12, 0).toInstant(ZoneOffset.UTC);

        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceA = seedTrace(pid, sessionId, backfillMid);
        String classifierId = insertClassifier(pid, "secret_leak", "Secret leak");

        // A backfill: checked today, ran three days ago; it charts on the day it ran.
        insertDetection(
                pid,
                classifierId,
                "secret_leak",
                sessionId,
                traceA,
                SubstrateV2Fixtures.spanId(),
                backfillMid,
                sweepNow);
        // A null subject_started_at (span and trace deleted) falls out of every bucket rather than onto the sweep
        // day.
        insertDetection(
                pid, classifierId, "secret_leak", sessionId, traceA, SubstrateV2Fixtures.spanId(), null, sweepNow);

        ClassifierService.DailyVolume v = service.dailyVolume(pid, 7);
        int backfillIdx = v.days().indexOf(backfillDay.toString());
        int todayIdx = v.days().indexOf(today.toString());
        assertTrue(backfillIdx >= 0, "the backfill day is inside the 7-day window");

        long[] counts = countsFor(v, classifierId);
        assertEquals(1, counts[backfillIdx], "the backfilled detection lands on the day its span ran");
        assertEquals(0, counts[todayIdx], "not on the day the sweep checked it");
        assertEquals(1, sum(counts), "the null-time row lands in no bucket at all");
    }

    @Test
    void daysParameterIsClamped() {
        String pid = TenantFixture.bootstrap(tenants, "classifier-daily-clamp")
                .project()
                .id();
        assertEquals(1, service.dailyVolume(pid, 0).days().size(), "days is clamped up to 1");
        assertEquals(30, service.dailyVolume(pid, 365).days().size(), "days is clamped down to 30");
    }

    /** One trace with a root llm span at {@code startedAt}. */
    private String seedTrace(String pid, String sessionId, Instant startedAt) {
        String id = SubstrateV2Fixtures.traceId();
        fx.spanSeed(pid)
                .traceId(id)
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .at(startedAt)
                .write();
        return id;
    }

    private String insertClassifier(String pid, String key, String name) {
        String id = Ids.ulid();
        jdbc.sql(
                        "INSERT INTO classifier (id, project_id, classifier_key, name, detector, built_in, version, enabled, "
                                + "created_at, updated_at) VALUES (:id, :pid, :key, :name, 'keyword', true, 1, true, :now, :now)")
                .param("id", id)
                .param("pid", pid)
                .param("key", key)
                .param("name", name)
                .param("now", Instant.now().toString())
                .update();
        return id;
    }

    /**
     * A span-grain detection in its classifier's table, seeded directly because the writer stamps {@code now()}.
     * Several spans of one trace are what the distinct count collapses. {@code subject_started_at} (migration 0012,
     * decision 8b) is what the read buckets on.
     */
    private void insertDetection(
            String pid, String classifierId, String key, String sessionId, String traceId, String spanId, Instant at) {
        insertDetection(pid, classifierId, key, sessionId, traceId, spanId, at, at);
    }

    /** As above, with the event and sweep clocks set independently; null is a row whose span or trace was deleted. */
    private void insertDetection(
            String pid,
            String classifierId,
            String key,
            String sessionId,
            String traceId,
            String spanId,
            @org.jspecify.annotations.Nullable Instant subjectStartedAt,
            Instant createdAt) {
        jdbc.sql("INSERT INTO secret_leak_detection (id, project_id, classifier_id, classifier_key, "
                        + "subject_session_id, subject_trace_id, subject_span_id, severity, confidence,"
                        + " subject_started_at, created_at) "
                        + "VALUES (:id, :pid, :sid, :key, :ses, :trace, :span, 'warn', 'high',"
                        + " CAST(:subjectAt AS timestamptz), :at::timestamptz)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("sid", classifierId)
                .param("key", key)
                .param("ses", sessionId)
                .param("trace", traceId)
                .param("span", spanId)
                .param("subjectAt", subjectStartedAt == null ? null : subjectStartedAt.toString())
                .param("at", createdAt.toString())
                .update();
    }

    private long[] countsFor(ClassifierService.DailyVolume v, String classifierId) {
        return v.classifiers().stream()
                .filter(c -> c.classifierId().equals(classifierId))
                .findFirst()
                .orElseThrow()
                .counts();
    }

    private long sum(long[] values) {
        long total = 0;
        for (long value : values) total += value;
        return total;
    }
}
