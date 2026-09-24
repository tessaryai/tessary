// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository.Assessment;
import ai.tessary.classifier.substrate.CallSiteShapeReads;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    void theMigrationAddsBothTablesTheClearColumnAndTheWidenedChecks() {
        for (String table : List.of("groundedness_assessment", "groundedness_state")) {
            assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '" + table + "'"));
        }
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_name = 'groundedness_detection' AND column_name = 'cleared_at'"));
        assertTrue(checkDefinition("eval_case_detector_check").contains("'groundedness'"));
        assertTrue(checkDefinition("rca_report_report_kind_check").contains("'groundedness_causes'"));
    }

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

    @Test
    void aSpanThatIsGoneFallsBackToTheIngestTime() {
        Fixture f = fixture("ga-fallback");
        assertTrue(assessments.insert(new Assessment(
                Ids.ulid(),
                f.pid(),
                f.row().id(),
                null,
                "t-gone",
                "s-gone",
                "",
                0.1,
                false,
                "gnd-a",
                "2026-09-02T08:00:00Z")));
        Instant started = jdbc.sql("SELECT observation_started_at FROM groundedness_assessment WHERE project_id = :pid")
                .param("pid", f.pid())
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .single();
        assertEquals(Instant.parse("2026-09-02T08:00:00Z"), started);
    }

    @Test
    void aRescoreDoesNotDoubleCount() {
        Fixture f = fixture("ga-rescore");
        CallSiteShapeReads shapes = (projectId, ids) -> Map.of("cs-1", "extract");
        GroundingEvidenceReads none = (projectId, ids) -> Map.of();
        EncoderScorer head = new EncoderScorer() {
            @Override
            public List<Double> score(String h, List<String> texts) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ResponseScore> scoreResponses(String h, List<Response> responses) {
                return responses.stream()
                        .map(r -> new ResponseScore(
                                0.99, 0.1, List.of(new Span(0, r.answer().length(), 0.99, 0.1))))
                        .toList();
            }
        };
        GroundednessDetector detector = new GroundednessDetector(head, shapes, none, assessments, new ObjectMapper());
        List<SubstrateObservation> page = List.of(
                observation(f, "span-1", "The refund took 9 days."),
                observation(f, "span-2", "Your plan renews on 3 March."));

        detector.sweepBatch(f.row(), page, null);
        detector.sweepBatch(f.row(), page, null);

        assertEquals(2, rowsFor(f), "a sweep rewound over the same page counts each answer once");
    }

    @Test
    void lastScoredAtIsTheNewestRowOrEmpty() {
        Fixture f = fixture("ga-last");
        assertTrue(assessments.lastScoredAt(f.pid(), f.row().id()).isEmpty());
        assessments.insert(assessment(f, "t-1", "s-1", "gnd-a", false));
        Instant before = Instant.now().minusSeconds(60);
        assertTrue(assessments.lastScoredAt(f.pid(), f.row().id()).orElseThrow().isAfter(before));
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

    private static SubstrateObservation observation(Fixture f, String spanId, String answer) {
        return new SubstrateObservation(
                spanId,
                f.pid(),
                "trace-1",
                null,
                null,
                "cs-1",
                "llm",
                "chat",
                "[{\"role\":\"user\",\"content\":\"what happened to my refund and my plan?\"}]",
                "[{\"role\":\"assistant\",\"content\":\"" + answer + "\"}]",
                null,
                RAN.toString());
    }

    private long rowsFor(Fixture f) {
        return jdbc.sql("SELECT COUNT(*) FROM groundedness_assessment WHERE project_id = :pid")
                .param("pid", f.pid())
                .query(Long.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private String checkDefinition(String name) {
        return jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = :name")
                .param("name", name)
                .query(String.class)
                .single();
    }
}
