// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository.Assessment;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code groundedness_assessment} and the rest of migration 0025 against Postgres: one row per scored
 * answer, written once per (trace, span, scorer) however often a sweep re-scores it, stamped with the
 * span's own start, and read back as the status's last scored time. The encoder and the shape and
 * evidence reads are faked; the table is real.
 */
@SpringBootTest
class GroundednessAssessmentIntegrationTest {

    private static final Instant RAN = Instant.parse("2026-09-01T10:15:00Z");

    @Autowired
    GroundednessAssessmentRepository assessments;

    @Autowired
    ClassifierRepository rows;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Test
    void anAnswerIsWrittenOncePerScorerAndStampedWithItsSpansStart() {
        Fixture f = fixture("ga-unique");
        String traceId = SubstrateV2Fixtures.traceId();
        String spanId = SubstrateV2Fixtures.spanId();
        fixtures().span(f.pid(), traceId, spanId, null, "llm", RAN, RAN.plusSeconds(2));

        assertTrue(assessments.insert(assessment(f, traceId, spanId, "gnd-a", true)));
        assertFalse(assessments.insert(assessment(f, traceId, spanId, "gnd-a", false)), "the same key again");
        assertTrue(assessments.insert(assessment(f, traceId, spanId, "gnd-b", false)), "a new scorer is new");

        assertEquals(2, rowsFor(f));
        Instant started = jdbc.sql("SELECT observation_started_at FROM groundedness_assessment"
                        + " WHERE project_id = :pid AND scorer_version = 'gnd-a'")
                .param("pid", f.pid())
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .single();
        assertEquals(RAN, started, "event time is the span's own start, not the ingest time");
        Boolean flagged = jdbc.sql("SELECT flagged FROM groundedness_assessment"
                        + " WHERE project_id = :pid AND scorer_version = 'gnd-a'")
                .param("pid", f.pid())
                .query(Boolean.class)
                .single();
        assertTrue(flagged, "the first write stands");
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private record Fixture(String pid, ClassifierRow row) {}

    private Fixture fixture(String name) {
        String pid = TenantFixture.bootstrap(tenants, name).project().id();
        String now = Instant.now().toString();
        ClassifierRow row = new ClassifierRow(
                Ids.ulid(),
                pid,
                "groundedness-assessment",
                "Groundedness (assessment test)",
                null,
                BuiltInDetector.Kind.GROUNDEDNESS,
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                now,
                now);
        rows.insert(row);
        return new Fixture(pid, row);
    }

    private SubstrateV2Fixtures fixtures() {
        return new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    private static Assessment assessment(Fixture f, String traceId, String spanId, String scorer, boolean flagged) {
        return new Assessment(
                Ids.ulid(),
                f.pid(),
                f.row().id(),
                null,
                traceId,
                spanId,
                "cs-1",
                flagged ? 0.99 : 0.1,
                flagged,
                scorer,
                RAN.plusSeconds(30).toString());
    }

    private long rowsFor(Fixture f) {
        return jdbc.sql("SELECT COUNT(*) FROM groundedness_assessment WHERE project_id = :pid")
                .param("pid", f.pid())
                .query(Long.class)
                .single();
    }
}
