// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierDtos;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.finding.CauseKey;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.StubEncoderScorerConfig;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Malformed Output's rate findings, end to end against Postgres: the tallies counted from the spans the sweep
 * checked, replayed through tool_error's engine per call site, and the finding and state that replay writes.
 *
 * <p>The reference minimum is lowered to fifty calls so a test can reach it; the arithmetic is the shipped
 * one otherwise.
 */
@SpringBootTest
@Import(StubEncoderScorerConfig.class)
class MalformedOutputRateIntegrationTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"answer\"]}";

    @Autowired
    MalformedOutputRateService rates;

    @Autowired
    MalformedOutputRateRepository rateRows;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    ClassifierRepository classifierRows;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

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

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void fixtures() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    @Test
    void aCallSiteThatStartsFailingItsSchemaOpensOneFindingWithItsFailuresAsWitnesses() {
        String pid = project("malformed-rise");
        ClassifierRow signal = malformedOutput(pid);
        callSite(pid, "cs-a", SCHEMA);
        Instant start = hoursAgo(10);
        clean(pid, "cs-a", start, 60); // the reference: sixty outputs that parse
        failing(pid, signal, "cs-a", start.plus(4, ChronoUnit.HOURS), 12); // then a run that does not

        int spells = rates.refresh(pid, signal, later(), Instant.now());

        assertEquals(1, spells);
        FindingRow finding = finding(pid, signal, "cs-a");
        assertEquals("malformed_output", finding.classifierKey());
        assertEquals(FindingRow.Cause.MALFORMED_RATE, finding.causeKind());
        assertEquals("cs-a", finding.callSiteId());
        assertEquals("cs-a outputs failing their schema", FindingTitle.of(finding));
        assertEquals(12, count(finding.id(), FindingEvidenceRow.Role.WITNESS), "the failing spans are witnesses");
        assertEquals(
                1,
                jdbc.sql("SELECT COUNT(*) FROM malformed_output_state WHERE project_id = :pid")
                        .param("pid", pid)
                        .query(Long.class)
                        .single(),
                "the accumulator is carried per call site, in this classifier's own table");

        rates.refresh(pid, signal, later(), Instant.now());
        assertEquals(1, liveFindings(pid), "a second pass refreshes the finding rather than opening another");
        assertEquals(
                0,
                jdbc.sql("SELECT COUNT(*) FROM tool_error_state WHERE project_id = :pid")
                        .param("pid", pid)
                        .query(Long.class)
                        .single(),
                "and never writes into tool_error's accumulators");
    }

    /**
     * The reset {@code CaseService.resolve} performs must survive the next pass. This classifier rebuilds every
     * pass rather than resuming after a watermark, so without the reset fence the rebuild re-folded the hours
     * before the reset and the spell a human had just closed came straight back.
     */
    @Test
    void aResetSurvivesTheNextRebuild() {
        String pid = project("malformed-reset");
        ClassifierRow signal = malformedOutput(pid);
        callSite(pid, "cs-a", SCHEMA);
        Instant start = hoursAgo(10);
        clean(pid, "cs-a", start, 60);
        failing(pid, signal, "cs-a", start.plus(4, ChronoUnit.HOURS), 12);
        assertEquals(1, rates.refresh(pid, signal, later(), Instant.now()));

        rateRows.states()
                .reset(pid, "cs-a", null, "resolved in a test", Instant.now().toString());

        assertEquals(0, rates.refresh(pid, signal, later(), Instant.now()), "the closed spell stays closed");
        CarriedState after = carried(pid, "cs-a");
        assertEquals(0.0, after.state().sUp(), "the rebuild did not re-accumulate the hours before the reset");
        assertNotNull(after.baseline(), "a plain reset keeps the learned reference");
        assertNotNull(after.resetAt(), "and the fence is read back onto the carried state");
    }

    /** The variant that also drops the reference: nothing before the reset may teach the new one. */
    @Test
    void aResetAndRelearnDropsTheReferenceAndLearnsNothingFromBeforeIt() {
        String pid = project("malformed-relearn");
        ClassifierRow signal = malformedOutput(pid);
        callSite(pid, "cs-a", SCHEMA);
        Instant start = hoursAgo(10);
        clean(pid, "cs-a", start, 60);
        failing(pid, signal, "cs-a", start.plus(4, ChronoUnit.HOURS), 12);
        assertEquals(1, rates.refresh(pid, signal, later(), Instant.now()));

        rateRows.states()
                .resetAndRelearn(
                        pid,
                        "cs-a",
                        "someone",
                        "relearn in a test",
                        Instant.now().toString());
        CarriedState cleared = carried(pid, "cs-a");
        assertNull(cleared.baseline(), "the reference is gone");
        assertNull(cleared.watermarkBucket());
        assertEquals(0.0, cleared.state().sUp());

        assertEquals(0, rates.refresh(pid, signal, later(), Instant.now()));
        assertNull(
                carried(pid, "cs-a").baseline(),
                "every hour before the reset is fenced off, so there is nothing to learn from yet");
    }

    @Test
    void aCallSiteStillLearningItsReferenceFilesNothingHoweverBadItLooks() {
        String pid = project("malformed-learning");
        ClassifierRow signal = malformedOutput(pid);
        callSite(pid, "cs-a", SCHEMA);
        failing(pid, signal, "cs-a", hoursAgo(3), 20); // twenty outputs, every one malformed, under the fifty

        assertEquals(0, rates.refresh(pid, signal, later(), Instant.now()));
        assertEquals(0, liveFindings(pid), "a rate needs a reference before anything can be judged against it");
    }

    @Test
    void outputsTheSweepHasNotCheckedAreNotCountedAsPassing() {
        String pid = project("malformed-cursor");
        ClassifierRow signal = malformedOutput(pid);
        callSite(pid, "cs-a", SCHEMA);
        Instant start = hoursAgo(10);
        clean(pid, "cs-a", start, 60);
        failing(pid, signal, "cs-a", start.plus(4, ChronoUnit.HOURS), 12);
        String beforeTheFailures = jdbc.sql(
                        "SELECT MIN(created_at) FROM malformed_output_detection WHERE project_id = :pid")
                .param("pid", pid)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant()
                .toString();

        assertEquals(
                0,
                rates.refresh(pid, signal, beforeTheFailures, Instant.now()),
                "the sweep had not reached the failing spans, so the replay may not either");
        assertEquals(1, rates.refresh(pid, signal, later(), Instant.now()), "and once it has, they count");
    }

    @Test
    void aCallSiteWithNoDeclaredSchemaIsNeverJudged() {
        String pid = project("malformed-no-schema");
        ClassifierRow signal = malformedOutput(pid);
        callSite(pid, "cs-a", null);
        Instant start = hoursAgo(10);
        clean(pid, "cs-a", start, 60);
        failing(pid, signal, "cs-a", start.plus(4, ChronoUnit.HOURS), 12);

        assertEquals(0, rates.refresh(pid, signal, later(), Instant.now()));
        assertEquals(0, liveFindings(pid));
    }

    @Test
    void theClassifierReadsAsWaitingUntilACallSiteDeclaresASchema() {
        String pid = project("malformed-readiness");
        ClassifierRow signal = malformedOutput(pid);

        assertEquals(ClassifierDtos.ClassifierView.WAITING_ON_SCHEMAS, classifiers.readiness(pid, signal));
        callSite(pid, "cs-a", SCHEMA);
        assertNull(classifiers.readiness(pid, signal), "one schema is enough to start judging");
        assertNull(
                classifiers.readiness(
                        pid, classifierRows.findByKey(pid, "secret_leak").orElseThrow()),
                "no other classifier waits on a schema");
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), Capability.MALFORMED_OUTPUT))
                .project()
                .id();
    }

    /** The seeded Malformed Output row, with the reference minimum lowered to something a test can reach. */
    private ClassifierRow malformedOutput(String pid) {
        classifiers.seedBuiltIns(pid);
        ClassifierRow seeded = classifierRows.findByKey(pid, "malformed_output").orElseThrow();
        jdbc.sql("UPDATE classifier SET config_json = :cfg WHERE id = :id")
                .param("cfg", "{\"min_baseline_calls\":50}")
                .param("id", seeded.id())
                .update();
        return classifierRows.findByKey(pid, "malformed_output").orElseThrow();
    }

    private void callSite(String pid, String id, @org.jspecify.annotations.Nullable String schema) {
        jdbc.sql("INSERT INTO call_site (project_id, id, output_schema) VALUES (:pid, :id, :schema)")
                .param("pid", pid)
                .param("id", id)
                .param("schema", schema)
                .update();
    }

    private void clean(String pid, String callSite, Instant from, int n) {
        for (int i = 0; i < n; i++) {
            fx.spanSeed(pid)
                    .callSiteId(callSite)
                    .at(from.plus(i, ChronoUnit.MINUTES))
                    .previews(null, "{\"answer\":\"ok\"}")
                    .write();
        }
    }

    private void failing(String pid, ClassifierRow signal, String callSite, Instant from, int n) {
        for (int i = 0; i < n; i++) {
            SubstrateV2Fixtures.SpanRef span = fx.spanSeed(pid)
                    .callSiteId(callSite)
                    .at(from.plus(i, ChronoUnit.SECONDS))
                    .previews(null, "sorry, I can't help with that")
                    .writeRef();
            detections.insert(
                    Ids.ulid(),
                    "malformed_output",
                    pid,
                    signal.id(),
                    signal.classifierKey(),
                    null,
                    null,
                    span.traceId(),
                    span.spanId(),
                    "warn",
                    "high",
                    "{\"reason\":\"not_json\",\"violations\":[]}");
        }
    }

    private CarriedState carried(String pid, String callSite) {
        CarriedState state = rateRows.states().byTool(pid).get(callSite);
        assertNotNull(state, "the call site has a state row");
        return state;
    }

    private FindingRow finding(String pid, ClassifierRow signal, String callSite) {
        String id = jdbc.sql("SELECT id FROM finding WHERE project_id = :pid AND cause_key = :key")
                .param("pid", pid)
                .param("key", CauseKey.malformedOutput(signal.id(), callSite))
                .query(String.class)
                .single();
        return findings.findById(pid, id).orElseThrow();
    }

    private long count(String findingId, String role) {
        return jdbc.sql("SELECT COUNT(*) FROM finding_evidence WHERE finding_id = :id AND role = :role")
                .param("id", findingId)
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private long liveFindings(String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM finding WHERE project_id = :pid AND status IN ('open','blocked')")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }

    private static Instant hoursAgo(long hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
    }

    /** A cursor past everything seeded: the sweep has checked it all. */
    private static String later() {
        return Instant.now().plus(1, ChronoUnit.MINUTES).toString();
    }
}
