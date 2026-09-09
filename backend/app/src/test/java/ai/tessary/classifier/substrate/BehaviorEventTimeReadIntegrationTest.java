// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.substrate.BehaviorSubstrateRepository.TraceHead;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The FORMAT of the event time the sweep reads, against real Postgres and the real driver.
 *
 * <p><b>Why this exists as its own test.</b> The lifecycle tests hand-write ISO strings straight into
 * {@code upsertCounts}, so they exercise the comparison logic and never touch the substrate read that
 * produces those strings. A revision of this change read {@code started_at} with {@code getString} on a
 * {@code timestamptz} column, which returns the driver's own rendering —
 * {@code 2026-07-28 18:46:37.416851+05:30}: space separator, no {@code T}, offset in the JVM's zone —
 * and {@code Instant.parse} rejects it at index 10. Every span rule then failed its parse silently and
 * fell through to a wall-clock default, which not only left the bug in place but made retention delete
 * the quarantined tail. The lifecycle tests were all green throughout. A value's format is part of its
 * contract, and only the real driver can attest to it.
 */
@SpringBootTest
class BehaviorEventTimeReadIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BehaviorSubstrateRepository substrate;

    @Autowired
    TenantService tenants;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    @DisplayName("the event time a trace head carries is a parseable instant, not the driver's rendering")
    void eventTimeIsAnIsoInstant() {
        String pid =
                TenantFixture.bootstrap(tenants, "drift-event-format").project().id();
        // The backfill shape: the trace RAN a month ago and LANDED just now.
        Instant ran = Instant.now().minus(Duration.ofDays(30));
        seedSettledTrace(pid, ran);

        List<TraceHead> heads = substrate.tracesAfter(pid, null, null, 10);
        assertEquals(1, heads.size());
        TraceHead head = heads.get(0);

        // The assertion that the getString revision failed: parseable at all.
        Instant parsed = Instant.parse(head.eventAt());
        assertEquals(
                ran.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                parsed.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                "the event time must survive the round trip as an instant: " + head.eventAt());
        assertTrue(head.eventAt().endsWith("Z"), "normalised to UTC, not the JVM's offset: " + head.eventAt());
        assertTrue(head.eventAt().contains("T"), "ISO-8601, not the driver's space form: " + head.eventAt());

        // There is no second clock left to be confused with. trace has no created_at at all, so a
        // backfilled trace cannot report its ingest time here even by accident — the bug this test was
        // written for is now unrepresentable rather than merely absent.
        assertTrue(
                parsed.isBefore(Instant.now().minus(Duration.ofDays(29))),
                "and eventAt is the RUN time, not the ingest time: " + head.eventAt());
    }

    @Test
    @DisplayName("started_at is NOT NULL now, so the fallback the old COALESCE guarded cannot arise")
    void theStartIsAlwaysPresentAndAlwaysAnInstant() {
        String pid = TenantFixture.bootstrap(tenants, "drift-event-fallback")
                .project()
                .id();
        Instant ran = Instant.now().minusSeconds(120);
        seedSettledTrace(pid, ran);

        TraceHead head = substrate.tracesAfter(pid, null, null, 10).get(0);

        // v1 read COALESCE(started_at, created_at) because a producer that omits started_at was common
        // and the trace row had a second clock to fall back on. v2 has neither: started_at is NOT NULL
        // (the trace takes the min over its spans' starts, and a span's start is NOT NULL too), and
        // trace carries no created_at at all. So this asserts the property that replaced the
        // fallback rather than the fallback itself — the column is always there, and always parses.
        assertEquals(
                ran.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                Instant.parse(head.eventAt()).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertTrue(head.eventAt().endsWith("Z"), head.eventAt());
        assertEquals(
                0L,
                jdbc.sql("SELECT count(*) FROM trace WHERE project_id = :pid AND started_at IS NULL")
                        .param("pid", pid)
                        .query(Long.class)
                        .single(),
                "no trace can be seeded without a start");
    }

    /** A settled single-span trace: the sweep reads only settled traces, so seeding stops short of useless. */
    private void seedSettledTrace(String pid, Instant ran) {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(SubstrateV2Fixtures.sessionId())
                .kind("agent")
                .name("loop")
                .at(ran)
                .write();
        fx.rollup(pid, traceId);
    }
}
