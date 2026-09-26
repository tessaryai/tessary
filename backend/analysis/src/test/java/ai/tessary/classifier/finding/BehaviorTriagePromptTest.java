// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The triage system prompt (one copy for every classifier) and the per-run dossier and user message {@link
 * BehaviorTriageEngine} builds. The MCP catalogue is left to the server's {@code initialize} instructions (decision
 * R8); pinned here: the dossier carries only the finding's facts and the detector's method, the claim's numbers come
 * live from {@code get_finding}, and the budget is the config value.
 */
@ExtendWith(MockitoExtension.class)
class BehaviorTriagePromptTest {

    /** The job argument neither {@code dossier} nor {@code buildPrompt} reads; a fixed stand-in. */
    private static final BehaviorTriageJobRow JOB = new BehaviorTriageJobRow(
            "job-1", "proj-1", "fnd-1", "claimed", null, null, 0, null, "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");

    private static BehaviorTriageEngine engine() {
        return engine(new ObserverProperties());
    }

    private static BehaviorTriageEngine engine(ObserverProperties observerProps) {
        return engine(observerProps, mock(ClassifierDetectionWriteRepository.class));
    }

    private static BehaviorTriageEngine engine(
            ObserverProperties observerProps, ClassifierDetectionWriteRepository detections) {
        return new BehaviorTriageEngine(
                List.of(),
                new ClassifierProperties(),
                observerProps,
                mock(ApiKeyService.class),
                mock(ProjectRepository.class),
                mock(OrgMembershipRepository.class),
                new ObjectMapper(),
                detections);
    }

    private static FindingRow finding(
            String classifierKey,
            String causeKey,
            @Nullable String callSiteId,
            @Nullable String payloadJson,
            @Nullable String evidenceCountsJson) {
        return new FindingRow(
                "fnd-1",
                "proj-1",
                classifierKey,
                causeKey,
                FindingRow.SubjectKind.TOOL,
                "search_docs",
                null,
                callSiteId,
                FindingRow.Status.OPEN,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                null,
                null,
                null,
                128,
                payloadJson,
                evidenceCountsJson,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z");
    }

    private static String findingMd(BehaviorTriageEngine engine, FindingRow row) {
        String md = engine.dossier(JOB, row).get("finding.md");
        assertNotNull(md, "dossier() always writes finding.md");
        return md;
    }

    @Test
    void theSystemPromptDefinesBothVerdictsAndNoOthers() {
        String prompt = BehaviorTriageEngine.SYSTEM_PROMPT;

        assertTrue(prompt.contains("`positive`"), "positive has to be defined");
        assertTrue(prompt.contains("`negative`"), "and so does negative");
        assertTrue(prompt.contains("`blocked`"), "and the non-verdict for an unreachable surface");
        assertFalse(prompt.contains("unclear"), "unclear is gone from the vocabulary");
    }

    @Test
    void theSystemPromptNamesTheCoreCallsAndTheWorkspace() {
        String prompt = BehaviorTriageEngine.SYSTEM_PROMPT;

        assertTrue(prompt.contains("get_finding_evidence"), "the rows are load-bearing");
        assertTrue(prompt.contains("get_finding"), "and so is the claim's own numbers");
        assertTrue(prompt.contains("checks/"), "where the agent's own scripts go");
        assertTrue(prompt.contains("dossier/finding.md"), "the dossier files must be named");
        assertTrue(prompt.contains("dossier/method.md"), "and both of them");
    }

    /**
     * A groundedness rate finding ships {@code detections.md}: each flagged answer since onset with its score,
     * offsets, and strongest sentence.
     */
    @Test
    void aGroundednessRateFindingShipsItsFlaggedAnswers() {
        ClassifierDetectionWriteRepository detections = mock(ClassifierDetectionWriteRepository.class);
        when(detections.listWitnessDetections(
                        BuiltInDetector.Kind.GROUNDEDNESS,
                        "proj-1",
                        "search_docs",
                        "rag-answer",
                        "2026-08-01T00:00:00Z",
                        "2026-08-02T01:00:00Z",
                        BehaviorTriageEngine.DETECTIONS_CAP + 1))
                .thenReturn(List.of(new ClassifierDetectionWriteRepository.DetectionInWindow(
                        "tr-1",
                        "sp-1",
                        null,
                        "warn",
                        "high",
                        "{\"unsupported\":0.991,\"flagged_sentences\":[{\"start\":0,\"end\":24,"
                                + "\"unsupported\":0.991},{\"start\":25,\"end\":40,\"unsupported\":0.98}],"
                                + "\"claim\":\"The refund window is 90 days.\"}",
                        "2026-08-01T12:00:00Z")));
        FindingRow row = finding(
                BuiltInDetector.Kind.GROUNDEDNESS,
                "sig-1:rag-answer",
                "rag-answer",
                "{\"cause_kind\":\"groundedness_rate\",\"native_cause_key\":\"rag-answer\"}",
                null);

        Map<String, String> dossier =
                engine(new ObserverProperties(), detections).dossier(JOB, row);

        assertEquals(Set.of("finding.md", "method.md", "detections.md"), dossier.keySet());
        String md = dossier.get("detections.md");
        assertNotNull(md);
        assertTrue(md.contains("trace `tr-1` span `sp-1` at 2026-08-01T12:00:00Z"), md);
        assertTrue(md.contains("score 0.991; flagged [0, 24) 0.991, [25, 40) 0.98"), md);
        assertTrue(md.contains("strongest: \"The refund window is 90 days.\""), md);
        assertTrue(md.contains("1 flagged answer(s), every one since onset."), md);
    }

    /**
     * Non-JSON evidence is listed as written, missing evidence says so, and an unreadable last-seen still ships the
     * file.
     */
    @Test
    void aGroundednessDossierSurvivesUnreadableEvidenceAndAnUnreadableLastSeen() {
        ClassifierDetectionWriteRepository detections = mock(ClassifierDetectionWriteRepository.class);
        when(detections.listWitnessDetections(
                        eq(BuiltInDetector.Kind.GROUNDEDNESS), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new ClassifierDetectionWriteRepository.DetectionInWindow(
                                "tr-1", "sp-1", null, "warn", "high", "score=0.9 (legacy)", "2026-08-01T12:00:00Z"),
                        new ClassifierDetectionWriteRepository.DetectionInWindow(
                                "tr-2", "sp-2", null, "warn", "high", null, "2026-08-01T11:00:00Z")));
        FindingRow row = FindingRowBuilder.of(BuiltInDetector.Kind.GROUNDEDNESS)
                .callSiteId("rag-answer")
                .lastSeenAt("2026-08-02 00:00:00+00")
                .payload("{\"cause_kind\":\"groundedness_rate\",\"native_cause_key\":\"rag-answer\"}")
                .build();

        String md =
                engine(new ObserverProperties(), detections).dossier(JOB, row).get("detections.md");

        assertEquals(
                "# Flagged answers since onset\n\n"
                        + "One line per answer the classifier flagged at this call site, newest first: trace and"
                        + " span ids (the `get_trace` / `get_span` arguments), when the span ran, the answer's"
                        + " score (P(unsupported) of its strongest sentence), and each flagged sentence as"
                        + " `[start, end)` offsets into the answer (UTF-16 code units) with its own score. The"
                        + " strongest sentence's text follows in quotes. A flagged sentence is where the model"
                        + " saw no support in the retrieved documents, not proof that the sentence is wrong.\n\n"
                        + "- trace `tr-1` span `sp-1` at 2026-08-01T12:00:00Z: score=0.9 (legacy)\n"
                        + "- trace `tr-2` span `sp-2` at 2026-08-01T11:00:00Z: (no evidence recorded)\n"
                        + "\n2 flagged answer(s), every one since onset.\n",
                md);
    }

    /** Past {@link BehaviorTriageEngine#DETECTIONS_CAP} the file lists the newest 50 and says the rest are paged. */
    @Test
    void aGroundednessFindingWithMoreFlaggedAnswersThanTheCapListsTheNewest50AndSaysSo() {
        ClassifierDetectionWriteRepository detections = mock(ClassifierDetectionWriteRepository.class);
        List<ClassifierDetectionWriteRepository.DetectionInWindow> rows = new ArrayList<>();
        StringBuilder listed = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            rows.add(new ClassifierDetectionWriteRepository.DetectionInWindow(
                    "tr-" + i, "sp-" + i, null, "warn", "high", "{\"unsupported\":0.99}", "2026-08-01T12:00:00Z"));
            if (i < 50)
                listed.append("- trace `tr-")
                        .append(i)
                        .append("` span `sp-")
                        .append(i)
                        .append("` at 2026-08-01T12:00:00Z: score 0.99\n");
        }
        when(detections.listWitnessDetections(
                        BuiltInDetector.Kind.GROUNDEDNESS,
                        "proj-1",
                        "search_docs",
                        "rag-answer",
                        "2026-08-01T00:00:00Z",
                        "2026-08-02T01:00:00Z",
                        51))
                .thenReturn(rows);
        FindingRow row = finding(
                BuiltInDetector.Kind.GROUNDEDNESS,
                "sig-1:rag-answer",
                "rag-answer",
                "{\"cause_kind\":\"groundedness_rate\",\"native_cause_key\":\"rag-answer\"}",
                null);

        String md =
                engine(new ObserverProperties(), detections).dossier(JOB, row).get("detections.md");

        assertEquals(
                "# Flagged answers since onset\n\n"
                        + "One line per answer the classifier flagged at this call site, newest first: trace and"
                        + " span ids (the `get_trace` / `get_span` arguments), when the span ran, the answer's"
                        + " score (P(unsupported) of its strongest sentence), and each flagged sentence as"
                        + " `[start, end)` offsets into the answer (UTF-16 code units) with its own score. The"
                        + " strongest sentence's text follows in quotes. A flagged sentence is where the model"
                        + " saw no support in the retrieved documents, not proof that the sentence is wrong.\n\n"
                        + listed
                        + "\nThe newest 50 shown; page the rest through `get_finding_evidence`.\n",
                md);
    }

    @Test
    void theDossierShipsOnlyFindingMdWhenNoCardExists() {
        FindingRow row = finding("user-authored-thing", "custom:1", null, null, null);

        assertEquals(Set.of("finding.md"), engine().dossier(JOB, row).keySet());
    }

    @Test
    void findingMdCarriesNoDetectorMethodOrCauseExplanation() {
        FindingRow row =
                finding(BuiltInDetector.Kind.TOOL_ERROR, "tool_error_rate:tool:search_docs:up", null, null, null);

        String findingMd = findingMd(engine(), row);

        assertFalse(findingMd.contains("RATE SHIFT"), "the cause explanation moved to the method card");
        assertFalse(findingMd.contains("CUSUM"), "the method itself is method.md's job, not finding.md's");
        assertTrue(findingMd.contains("Read the matching section of `dossier/method.md`"), findingMd);
    }

    @Test
    void findingMdOmitsTheMethodPointerWhenThereIsNoCard() {
        FindingRow row = finding("user-authored-thing", "custom:1", null, null, null);

        String findingMd = findingMd(engine(), row);

        assertFalse(
                findingMd.contains("dossier/method.md"),
                "promising a file the dossier does not carry costs the agent a turn on a missing read");
    }

    @Test
    void findingMdStatesTheFactsButNotTheDetectorsNumbers() {
        FindingRow row = finding(
                BuiltInDetector.Kind.TOOL_ERROR,
                "tool_error_rate:tool:search_docs:up",
                "cs-checkout",
                null,
                "{\"member\": 426, \"witness\": 5}");

        String findingMd = findingMd(engine(), row);

        assertTrue(findingMd.contains("`fnd-1`"), "the finding id");
        assertTrue(findingMd.contains("`" + BuiltInDetector.Kind.TOOL_ERROR + "`"), "the classifier");
        assertTrue(findingMd.contains("`tool_error_rate:tool:search_docs:up`"), "the pattern");
        assertTrue(findingMd.contains("128 sample(s)"), "the sample count");
        assertTrue(findingMd.contains("`cs-checkout`"), "the call site");
        assertTrue(findingMd.contains("`member`: 426 row(s)"), "member's count");
        assertTrue(findingMd.contains("`witness`: 5 row(s)"), "witness's count");
        assertTrue(findingMd.contains("`baseline`: 0 row(s)"), "a role the detector wrote none under is a stated zero");
        assertFalse(findingMd.contains("0.94"), "no detector number belongs here — get_finding is the one source");
    }

    @Test
    void callSiteHasThreeShapes() {
        FindingRow none = finding(BuiltInDetector.Kind.TOOL_ERROR, "k", null, null, null);
        assertTrue(findingMd(engine(), none).contains("call site: none"));

        FindingRow unattributed =
                finding(BuiltInDetector.Kind.TOOL_ERROR, "k", BehaviorSubstrateRepository.UNATTRIBUTED, null, null);
        assertTrue(findingMd(engine(), unattributed).contains("call site: none"));

        FindingRow plain = finding(BuiltInDetector.Kind.TOOL_ERROR, "k", "cs-checkout", null, null);
        String plainMd = findingMd(engine(), plain);
        assertTrue(plainMd.contains("- call site: `cs-checkout`\n"), plainMd);

        FindingRow toolBucket = finding(
                BuiltInDetector.Kind.TOOL_ERROR, "k", "cs-checkout", "{\"bucket\": {\"kind\": \"tool\"}}", null);
        String toolMd = findingMd(engine(), toolBucket);
        assertTrue(toolMd.contains("the largest of the call sites this tool bucket spans"), toolMd);
    }

    @Test
    void windowLineIsOptional() {
        FindingRow noWindow = finding(BuiltInDetector.Kind.TOOL_ERROR, "k", null, null, null);
        assertFalse(findingMd(engine(), noWindow).contains("- window:"));

        FindingRow withWindow = finding(
                BuiltInDetector.Kind.DURATION_DRIFT,
                "k",
                null,
                "{\"window\": {\"opened_at\": \"2026-08-01T00:00:00Z\", \"closed_at\": \"2026-08-02T00:00:00Z\","
                        + " \"kind\": \"elapsed\"}}",
                null);
        String md = findingMd(engine(), withWindow);
        assertTrue(md.contains("- window: 2026-08-01T00:00:00Z to 2026-08-02T00:00:00Z\n"), md);
        // The payload's kind names the detector sort, not the finding's scope; bare, it is jargon.
        assertFalse(md.contains("elapsed"), md);
    }

    @Test
    void theUserMessageStatesTheEffectiveTurnCapAndNotTheTimeout() {
        ObserverProperties props = new ObserverProperties();
        props.getAgentic().setMaxTurns(12);
        props.getAgentic().setTimeoutMs(600_000);
        FindingRow row = finding(BuiltInDetector.Kind.TOOL_ERROR, "k", null, null, null);

        String prompt = engine(props).buildPrompt(JOB, row);

        assertTrue(prompt.contains("Rule on finding `fnd-1`."), prompt);
        assertTrue(prompt.contains("`dossier/finding.md`"), prompt);
        assertTrue(prompt.contains("`dossier/method.md`"), prompt);
        // opencode reserves two turns of the cap for a forced final answer.
        assertTrue(prompt.contains("You have 10 turns."), prompt);
        // The wall clock is an operator guard the agent cannot observe, so it is never stated.
        assertFalse(prompt.contains("minutes"), prompt);
    }

    @Test
    void theUserMessageOmitsTheMethodCardLineWhenThereIsNone() {
        FindingRow row = finding("user-authored-thing", "custom:1", null, null, null);

        String prompt = engine().buildPrompt(JOB, row);

        assertFalse(prompt.contains("`dossier/method.md`"));
        assertTrue(prompt.contains("This detector has no method card; its rule is whatever its author configured."));
    }
}
