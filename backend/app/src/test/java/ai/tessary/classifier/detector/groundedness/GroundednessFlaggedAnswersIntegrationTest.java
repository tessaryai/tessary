// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository.Assessment;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /findings/{id}/flagged-answers} against a finding the rate test really filed: every flagged answer it
 * cites comes back newest first, a page at a time; one whose span and retrieval are stored comes back with its
 * question, the exact answer, the documents it was compared against and flagged sentences that slice to their
 * text; one whose trace was never stored says so; an RCA cause narrows the list to its traces; and the finding
 * page's block carries the first page.
 */
@SpringBootTest
class GroundednessFlaggedAnswersIntegrationTest {

    private static final String VERSION = GroundednessDetector.scorerVersion(GroundednessConfig.DEFAULT_THRESHOLD);
    private static final String CALL_SITE = "cs-rag";
    private static final String QUESTION = "When will my refund arrive?";
    private static final String FIRST = "The refund was issued on March 3.";
    private static final String SECOND = "It arrives within two business days by bank transfer.";
    private static final String ANSWER = FIRST + " " + SECOND;
    private static final String DOCUMENT = "Card refunds reach the customer within five to ten business days.";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.auth.cookie-password", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("workos.api-key", () -> "");
        r.add("workos.client-id", () -> "");
        r.add("tessary.auth.disabled", () -> "false");
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AuthFilter authFilter;

    @Autowired
    OrganizationRepository orgs;

    @Autowired
    ProjectRepository projects;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    GroundednessAssessmentRepository assessments;

    @Autowired
    GroundednessRateService rates;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    GroundednessDetailService detail;

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

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

    @Test
    void theEndpointPagesEveryFlaggedAnswerAndReadsBackTheStoredOne() throws Exception {
        MockHttpServletResponse signup = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("email", "flagged-answers@example.com", "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie session = Objects.requireNonNull(signup.getCookie("tessary-session"));
        String orgId = mapper.readTree(signup.getContentAsString())
                .path("data")
                .path("orgId")
                .asText();
        Organization org = orgs.findById(orgId).orElseThrow();
        Project project = projects.findDefaultForOrg(org.id()).orElseThrow();
        String pid = project.id();
        capabilities.grant(org.id(), Capability.GROUNDEDNESS);
        classifierService.seedBuiltIns(pid);
        ClassifierRow signal = classifiers.findByKey(pid, "groundedness").orElseThrow();

        Instant start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        seedHours(pid, signal, start, 0, 7, 0.05);
        seedHours(pid, signal, start, 7, 6, 0.40);
        rates.refresh(pid, signal, Instant.now());
        List<FindingRow> filed = findings.listByProject(pid, null, null, "groundedness", false, 10);
        assertEquals(1, filed.size());
        FindingRow finding = filed.get(0);
        long cited = evidence.listByFinding(pid, finding.id()).stream()
                .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role()) && r.spanId() != null)
                .count();

        String base = "/api/orgs/" + org.slug() + "/projects/" + project.slug() + "/findings/" + finding.id()
                + "/flagged-answers";
        JsonNode first = page(session, base + "?limit=2");
        assertEquals(cited, first.path("total").asLong(), "every flagged answer the finding cites");
        assertEquals(2, first.path("rows").size());
        assertEquals("2", first.path("nextCursor").asText());
        JsonNode newest = first.path("rows").get(0);
        assertFalse(newest.path("stored").asBoolean(), "no span was stored for it");
        assertTrue(newest.path("answer").isNull());
        assertEquals(1, newest.path("flaggedSentences").size(), "the offsets survive the payload");

        // Store the newest answer's span, its retrieval, and the sentences the model flagged in it.
        String trace = newest.path("traceId").asText();
        String span = newest.path("spanId").asText();
        Instant at = Instant.parse(newest.path("flaggedAt").asText());
        storeAnswer(pid, signal, trace, span, at);

        JsonNode again = page(session, base + "?limit=2");
        JsonNode stored = again.path("rows").get(0);
        assertEquals(span, stored.path("spanId").asText(), "the same answer, still first");
        assertTrue(stored.path("stored").asBoolean());
        assertEquals(ANSWER, stored.path("answer").asText());
        assertEquals(QUESTION, stored.path("question").asText());
        assertTrue(stored.path("premiseHadEvidence").asBoolean());
        assertEquals(DOCUMENT, stored.path("documents").get(0).path("text").asText());
        assertTrue(stored.path("documents").get(0).path("title").isNull());
        JsonNode marks = stored.path("flaggedSentences");
        assertEquals(2, marks.size());
        assertEquals(
                FIRST,
                ANSWER.substring(
                        marks.get(0).path("start").asInt(),
                        marks.get(0).path("end").asInt()));
        assertEquals(
                SECOND,
                ANSWER.substring(
                        marks.get(1).path("start").asInt(),
                        marks.get(1).path("end").asInt()));
        assertEquals(0.992, stored.path("score").asDouble(), "the highest marked sentence");

        JsonNode last = page(session, base + "?limit=2&cursor=" + (cited - 1));
        assertEquals(1, last.path("rows").size());
        assertTrue(last.path("nextCursor").isNull(), "the last page");

        // An RCA cause narrows the list to the answers in the traces it names; an index past its causes is none.
        String report = rcaReport(
                pid,
                finding.id(),
                "[{\"title\":\"One document\",\"evidence_trace_ids\":[\"" + trace
                        + "\",\"not-a-cited-trace\"],\"evidence_session_ids\":[]}]");
        JsonNode share = page(session, base + "?limit=50&rcaReport=" + report + "&cause=0");
        assertEquals(1, share.path("total").asLong(), "only the cause's trace");
        assertEquals(span, share.path("rows").get(0).path("spanId").asText());
        assertTrue(share.path("nextCursor").isNull());
        assertEquals(
                0,
                page(session, base + "?rcaReport=" + report + "&cause=1")
                        .path("total")
                        .asLong());
        assertEquals(
                cited,
                page(session, base + "?rcaReport=" + report).path("total").asLong(),
                "both or neither");

        GroundednessEvidence.GroundednessDetail block = detail.detail(finding);
        assertNotNull(block);
        assertEquals(
                Math.min(cited, GroundednessDetailService.PAGE_SIZE),
                block.answers().size());
        assertEquals(span, block.answers().getFirst().spanId());
        assertEquals(
                finding.payload().path("traces_since_onset").asLong(),
                block.rate().nCur());
    }

    private JsonNode page(Cookie session, String path) throws Exception {
        String body = mvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(body).path("data");
    }

    /** A finished groundedness RCA report on {@code findingId} carrying {@code causes}, and its job. */
    private String rcaReport(String pid, String findingId, String causes) {
        String job = Ids.ulid();
        String report = Ids.ulid();
        String now = Instant.now().toString();
        jdbc.sql("INSERT INTO job (id, project_id, kind, status, payload, created_at, updated_at)"
                        + " VALUES (:id, :pid, 'rca', 'done', CAST('{}' AS jsonb), :now, :now)")
                .param("id", job)
                .param("pid", pid)
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO rca_report (id, project_id, job_id, subject_kind, subject_id, subject_label, metric,"
                        + " window_from, window_split, window_to, current_value, prior_value, delta, status,"
                        + " created_at, engine, finding_id, report_kind, causes)"
                        + " VALUES (:id, :pid, :job, 'classifier', 'groundedness', 'Groundedness', 'groundedness',"
                        + " :now, :now, :now, 0, 0, 0, 'done', :now, 'agentic', :fid, 'groundedness_causes',"
                        + " CAST(:causes AS jsonb))")
                .param("id", report)
                .param("pid", pid)
                .param("job", job)
                .param("now", now)
                .param("fid", findingId)
                .param("causes", causes)
                .update();
        return report;
    }

    private void storeAnswer(String pid, ClassifierRow signal, String trace, String span, Instant at) {
        jdbc.sql("INSERT INTO call_site (project_id, id, shape) VALUES (:pid, :id, 'rag_answer')")
                .param("pid", pid)
                .param("id", CALL_SITE)
                .update();
        SubstrateV2Fixtures fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        SubstrateV2Fixtures.SpanRef retrieval = fx.spanSeed(pid)
                .traceId(trace)
                .spanId(span + "-r")
                .kind("retrieval")
                .at(at.minusSeconds(1))
                .writeRef();
        fx.retrievedDoc(pid, retrieval, DOCUMENT, 0, null, at.minusSeconds(1));
        fx.spanSeed(pid)
                .traceId(trace)
                .spanId(span)
                .callSiteId(CALL_SITE)
                .kind("llm")
                .at(at)
                .payload(QUESTION, ANSWER)
                .write();
        int second = ANSWER.indexOf(SECOND);
        jdbc.sql("UPDATE groundedness_detection SET evidence = CAST(:evidence AS jsonb)"
                        + " WHERE project_id = :pid AND classifier_id = :cid"
                        + " AND subject_trace_id = :trace AND subject_span_id = :span")
                .param(
                        "evidence",
                        "{\"head\":\"groundedness\",\"unsupported\":0.992,\"flagged_sentences\":["
                                + "{\"start\":0,\"end\":" + FIRST.length() + ",\"unsupported\":0.981},"
                                + "{\"start\":" + second + ",\"end\":" + ANSWER.length() + ",\"unsupported\":0.992}"
                                + "]}")
                .param("pid", pid)
                .param("cid", signal.id())
                .param("trace", trace)
                .param("span", span)
                .update();
    }

    /** 30 traces an hour, each with one scored answer; the first {@code rate} of each hour flagged. */
    private void seedHours(String pid, ClassifierRow signal, Instant start, int fromHour, int hours, double rate) {
        long flaggedPerHour = Math.round(30 * rate);
        for (int h = fromHour; h < fromHour + hours; h++) {
            for (int c = 0; c < 30; c++) {
                String trace = CALL_SITE + "-" + h + "-" + String.format(Locale.ROOT, "%02d", c);
                boolean flagged = c < flaggedPerHour;
                Instant at = start.plus(Duration.ofHours(h)).plusSeconds(c);
                assessments.insert(new Assessment(
                        Ids.ulid(),
                        pid,
                        signal.id(),
                        null,
                        trace,
                        trace + "-a",
                        CALL_SITE,
                        flagged ? 0.99 : 0.10,
                        flagged,
                        VERSION,
                        at.toString()));
                if (!flagged) continue;
                jdbc.sql("INSERT INTO groundedness_detection"
                                + " (id, project_id, classifier_id, classifier_key, subject_trace_id, subject_span_id,"
                                + " severity, confidence, evidence, subject_started_at)"
                                + " VALUES (:id, :pid, :cid, 'groundedness', :trace, :span, 'warn', 'high',"
                                + " CAST(:evidence AS jsonb), :at)")
                        .param("id", Ids.ulid())
                        .param("pid", pid)
                        .param("cid", signal.id())
                        .param("trace", trace)
                        .param("span", trace + "-a")
                        .param(
                                "evidence",
                                "{\"head\":\"groundedness\",\"unsupported\":0.99,\"flagged_sentences\":"
                                        + "[{\"start\":0,\"end\":20,\"unsupported\":0.99}]}")
                        .param("at", Timestamp.from(at))
                        .update();
            }
        }
    }
}
