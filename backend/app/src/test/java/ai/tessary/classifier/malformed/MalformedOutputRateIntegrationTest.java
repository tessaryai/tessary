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
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Malformed Output's rate findings, end to end against Postgres: the tallies counted from the spans the sweep
 * checked, replayed through tool_error's engine per call site, and the finding and state that replay writes.
 *
 * <p>The reference minimum is lowered to fifty calls so a test can reach it; the arithmetic is the shipped
 * one otherwise.
 */
@SpringBootTest
class MalformedOutputRateIntegrationTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"answer\"]}";

    private static final String DETAIL_SCHEMA = "{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{"
            + "\"answer\":{\"type\":\"string\"},"
            + "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}}}}}";

    @Autowired
    MalformedOutputRateService rates;

    @Autowired
    MalformedOutputRateRepository rateRows;

    @Autowired
    MalformedOutputDetailService detailService;

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
                        pid,
                        ClassifierRows.byKey(classifierRows, pid, "secret_leak").orElseThrow()),
                "no other classifier waits on a schema");
    }

    /**
     * "How outputs broke" read back from real detections. Catches a failure filed under the wrong schema row
     * (or under none), one output counted twice under a field or not at all under a second field it also broke,
     * the pre-rework and root-level violations lost instead of landing in {@code other}, a page that drops or
     * repeats a row across the cursor or loses the field's total, an unreadable cursor failing the read instead
     * of restarting it, and a failing output rendered from its message envelope rather than the answer the
     * detector judged.
     */
    @Test
    void howOutputsBrokeCountsEachFieldAndPagesItsFailingOutputs() {
        String pid = project("malformed-detail");
        ClassifierRow signal = malformedOutput(pid);
        callSite(pid, "cs-a", DETAIL_SCHEMA);
        Instant start = hoursAgo(10);
        clean(pid, "cs-a", start, 60);
        failing(pid, signal, "cs-a", start.plus(4, ChronoUnit.HOURS), 12);
        assertEquals(1, rates.refresh(pid, signal, later(), Instant.now()));
        FindingRow finding = finding(pid, signal, "cs-a");

        Instant after = start.plus(5, ChronoUnit.HOURS);
        // Breaks two fields at once: counted once under each.
        String twoFields = failed(
                pid,
                signal,
                after,
                "{\"answer\":7,\"items\":[{\"sku\":1},{\"sku\":\"ok\"}]}",
                "{\"reason\":\"schema_violation\",\"violations\":["
                        + "{\"field\":\"answer\",\"keyword\":\"type\",\"message\":\"answer: integer found, string expected\"},"
                        + "{\"field\":\"items[].sku\",\"keyword\":\"type\",\"message\":\"sku: integer found\"}]}");
        // A gen_ai envelope: the document is the assistant's answer, which is missing the required field.
        String envelope = failed(
                pid,
                signal,
                after.plus(1, ChronoUnit.MINUTES),
                "[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"{\\\"items\\\":[]}\"}]",
                "{\"reason\":\"schema_violation\",\"violations\":["
                        + "{\"field\":\"answer\",\"keyword\":\"required\",\"message\":\"answer is required\"}]}");
        // A pre-rework string violation and a root-level one: neither names a field, both are "other". No payload.
        String legacy = failed(
                pid,
                signal,
                after.plus(2, ChronoUnit.MINUTES),
                null,
                "{\"reason\":\"schema_violation\",\"violations\":[\"$.answer: is missing\"]}");
        String root = failed(
                pid,
                signal,
                after.plus(3, ChronoUnit.MINUTES),
                null,
                "{\"reason\":\"schema_violation\",\"violations\":[{\"field\":\"\",\"message\":\"must be an object\"}]}");
        // Past the document cap: the failing field sits beyond the cut, so nothing on screen is highlighted.
        String huge = failed(
                pid,
                signal,
                after.plus(4, ChronoUnit.MINUTES),
                hugeOutputEndingInAnswer(),
                "{\"reason\":\"schema_violation\",\"violations\":["
                        + "{\"field\":\"answer\",\"keyword\":\"type\",\"message\":\"answer: integer found\"}]}");
        // Not JSON at all, with its text stored.
        String prose = failed(
                pid,
                signal,
                after.plus(5, ChronoUnit.MINUTES),
                "sorry, I can't help with that",
                "{\"reason\":\"not_json\"}");

        MalformedOutputEvidence.MalformedDetail detail = detailService.detail(finding);

        assertNotNull(detail);
        assertEquals(
                List.of(
                        new MalformedOutputEvidence.SchemaFieldView("answer", "answer", "string", true, 0, 3),
                        new MalformedOutputEvidence.SchemaFieldView("items", "items[]", "array", false, 0, 0),
                        new MalformedOutputEvidence.SchemaFieldView("items[].sku", "sku", "string", false, 1, 1)),
                detail.fields());
        assertEquals(13, detail.notJson(), "the twelve that opened the finding and the prose one");
        assertEquals(2, detail.other());
        assertEquals(12, detail.rate().failingTraces().size(), "the finding's witnesses, deduplicated");

        MalformedOutputEvidence.FailingOutputPage first = detailService.failingOutputs(finding, "answer", 2, null);
        assertEquals(3, first.total());
        assertEquals(
                List.of(huge, envelope),
                first.rows().stream().map(r -> r.traceId()).toList());
        MalformedOutputEvidence.FailingOutputView hugeRow = first.rows().get(0);
        assertEquals(20_000, Objects.requireNonNull(hugeRow.document()).length());
        assertEquals(List.of(), hugeRow.highlightLines(), "the field's line is past the cut");
        MalformedOutputEvidence.FailingOutputView envelopeRow = first.rows().get(1);
        assertEquals("{\n  \"items\" : [ ]\n}", envelopeRow.document());
        assertEquals(List.of(1), envelopeRow.highlightLines(), "a missing field points at the object that lacks it");
        assertEquals("answer is required", envelopeRow.message());
        assertNotNull(first.nextCursor());

        MalformedOutputEvidence.FailingOutputPage second =
                detailService.failingOutputs(finding, "answer", 2, first.nextCursor());
        assertEquals(3, second.total(), "the total rides every page");
        assertEquals(1, second.rows().size());
        MalformedOutputEvidence.FailingOutputView twoFieldRow = second.rows().get(0);
        assertEquals(twoFields, twoFieldRow.traceId());
        assertEquals(
                "{\n  \"answer\" : 7,\n  \"items\" : [ {\n    \"sku\" : 1\n  }, {\n    \"sku\" : \"ok\"\n  } ]\n}",
                twoFieldRow.document());
        assertEquals(List.of(2), twoFieldRow.highlightLines());
        assertEquals("answer: integer found, string expected", twoFieldRow.message());
        assertNull(second.nextCursor());

        MalformedOutputEvidence.FailingOutputPage other = detailService.failingOutputs(finding, "other", 10, null);
        assertEquals(2, other.total());
        assertEquals(
                List.of(root, legacy),
                other.rows().stream().map(r -> r.traceId()).toList());
        assertNull(other.rows().get(0).document(), "no payload was stored");
        assertNull(other.rows().get(0).message(), "a bucket has no single message");

        for (String unreadable : new String[] {"!!!", "bm9zZXA"}) {
            MalformedOutputEvidence.FailingOutputPage notJson =
                    detailService.failingOutputs(finding, "not_json", 5, unreadable);
            assertEquals(13, notJson.total());
            assertEquals(5, notJson.rows().size(), "an unreadable cursor restarts the page, it does not fail it");
            MalformedOutputEvidence.FailingOutputView proseRow = notJson.rows().get(0);
            assertEquals(prose, proseRow.traceId());
            assertEquals("sorry, I can't help with that", proseRow.document());
            assertEquals(List.of(), proseRow.highlightLines());
        }
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
        ClassifierRow seeded =
                ClassifierRows.byKey(classifierRows, pid, "malformed_output").orElseThrow();
        jdbc.sql("UPDATE classifier SET config_json = :cfg WHERE id = :id")
                .param("cfg", "{\"min_baseline_calls\":50}")
                .param("id", seeded.id())
                .update();
        return ClassifierRows.byKey(classifierRows, pid, "malformed_output").orElseThrow();
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

    /** One failing output after the finding's onset: its span (with a stored payload when given) and its detection. */
    private String failed(
            String pid,
            ClassifierRow signal,
            Instant at,
            @org.jspecify.annotations.Nullable String output,
            String evidence) {
        SubstrateV2Fixtures.SpanSeed seed =
                fx.spanSeed(pid).callSiteId("cs-a").at(at).previews(null, "a failing output");
        if (output != null) seed = seed.payload(null, output);
        SubstrateV2Fixtures.SpanRef span = seed.writeRef();
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
                evidence);
        return span.traceId();
    }

    /** A JSON object whose pretty print runs well past the document cap, with {@code answer} as its last key. */
    private static String hugeOutputEndingInAnswer() {
        StringBuilder sb = new StringBuilder("{\"pad\":[");
        for (int i = 0; i < 3_000; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"xxxxxxxxxx\"");
        }
        return sb.append("],\"answer\":1}").toString();
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
