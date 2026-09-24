// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.GroundednessDetail;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.RetrievedDocumentView;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.AnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.CauseRef;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.CitedAnswer;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * {@link GroundednessDetailService}: the rate is read off the finding's payload, each cited answer is read back
 * as it was scored so its flagged sentences slice to their text, an answer whose trace is gone says so, and the
 * witnesses page by offset.
 */
class GroundednessDetailServiceTest {

    private static final String PROJECT = "proj_1";
    private static final String CLASSIFIER = "clf_g";
    private static final String CALL_SITE = "support.answer";

    private static final String ANSWER =
            "The refund was issued on March 3. It arrives within two business days by bank transfer.";
    private static final String FIRST = "The refund was issued on March 3.";
    private static final String SECOND = "It arrives within two business days by bank transfer.";

    private final GroundednessRateRepository rates = mock(GroundednessRateRepository.class);
    private final SubstrateReadRepository substrate = mock(SubstrateReadRepository.class);
    private final GroundednessDetailService service =
            new GroundednessDetailService(rates, substrate, new ObjectMapper());

    @Test
    void anyOtherFindingHasNoBlock() {
        assertNull(service.detail(finding("{\"cause_kind\":\"frustration_rate\"}")));
    }

    @Test
    void theRateNumbersComeFromThePayload() {
        when(rates.answerPage(PROJECT, CLASSIFIER, "fnd_1", null, GroundednessDetailService.PAGE_SIZE, 0))
                .thenReturn(new AnswerPage(List.of(), 0));

        GroundednessDetail block = service.detail(finding(payload()));

        assertNotNull(block);
        assertEquals(0.021, block.rate().refRate());
        assertEquals(0.064, block.rate().curRate());
        assertEquals(4.3, block.rate().deltaPp());
        assertEquals(640, block.rate().nRef());
        assertEquals(500, block.rate().nCur());
        assertEquals(32, block.rate().failuresCur());
        assertEquals(CALL_SITE, block.rate().bucketKey());
        assertEquals(0.975, block.flagThreshold());
        assertEquals(640, block.baselineTraces());
        assertEquals(1000, block.learningUntil());
        assertEquals(50_000, block.arlTarget());
        assertTrue(block.answers().isEmpty());
        assertNull(block.answersNextCursor());
        assertTrue(block.withoutIds().answers().isEmpty());
    }

    @Test
    void aStoredAnswersFlaggedSentencesSliceToTheirText() {
        int secondStart = ANSWER.indexOf(SECOND);
        String evidence = "{\"unsupported\":0.991,\"flagged_sentences\":["
                + "{\"start\":0,\"end\":" + FIRST.length() + ",\"unsupported\":0.981},"
                + "{\"start\":" + secondStart + ",\"end\":" + ANSWER.length() + ",\"unsupported\":0.991}]}";
        when(rates.answerPage(eq(PROJECT), eq(CLASSIFIER), eq("fnd_1"), isNull(), anyInt(), eq(0)))
                .thenReturn(new AnswerPage(
                        List.of(new CitedAnswer("tr_1", "sp_1", "sess_1", "2026-09-23T14:41:00Z", evidence, false)),
                        1));
        storedAnswer("tr_1", "sp_1");

        FlaggedAnswerPage page = service.page(finding(payload()), null, 50, null);

        assertEquals(1, page.total());
        assertNull(page.nextCursor());
        FlaggedAnswerView a = page.rows().getFirst();
        assertTrue(a.stored());
        assertFalse(a.cleared());
        assertEquals(ANSWER, a.answer());
        assertEquals("When will my refund arrive?", a.question());
        assertEquals("sess_1", a.sessionId());
        assertEquals("2026-09-23T14:41:00Z", a.flaggedAt());
        assertEquals(0.991, a.score(), "the highest flagged sentence");
        assertEquals(2, a.flaggedSentences().size());
        String answer = Objects.requireNonNull(a.answer());
        assertEquals(
                FIRST,
                answer.substring(
                        a.flaggedSentences().get(0).start(),
                        a.flaggedSentences().get(0).end()));
        assertEquals(
                SECOND,
                answer.substring(
                        a.flaggedSentences().get(1).start(),
                        a.flaggedSentences().get(1).end()));
        assertTrue(a.premiseHadEvidence());
        List<RetrievedDocumentView> documents = Objects.requireNonNull(a.documents());
        assertEquals(2, documents.size());
        assertNull(documents.getFirst().title(), "the evidence read carries no document name");
        assertEquals("Refund 4417 was issued on March 3.", documents.getFirst().text());
    }

    @Test
    void aTraceThatIsGoneIsNotStored() {
        when(rates.answerPage(eq(PROJECT), eq(CLASSIFIER), eq("fnd_1"), isNull(), anyInt(), eq(0)))
                .thenReturn(new AnswerPage(
                        List.of(new CitedAnswer(
                                "tr_gone",
                                "sp_1",
                                null,
                                null,
                                "{\"unsupported\":0.98,\"flagged_sentences\":"
                                        + "[{\"start\":0,\"end\":5,\"unsupported\":0.98}]}",
                                true)),
                        1));
        when(substrate.observationsByIds(eq(PROJECT), any())).thenReturn(List.of());

        FlaggedAnswerView a =
                service.page(finding(payload()), null, 50, null).rows().getFirst();

        assertFalse(a.stored());
        assertTrue(a.cleared());
        assertNull(a.answer());
        assertNull(a.question());
        assertNull(a.documents());
        assertEquals(0.98, a.score());
        assertEquals(1, a.flaggedSentences().size(), "the offsets survive the payload");
    }

    @Test
    void witnessesPageByOffset() {
        FindingRow finding = finding(payload());
        when(rates.answerPage(PROJECT, CLASSIFIER, "fnd_1", null, 2, 0))
                .thenReturn(new AnswerPage(List.of(cited("tr_3"), cited("tr_2")), 3));
        when(rates.answerPage(PROJECT, CLASSIFIER, "fnd_1", null, 2, 2))
                .thenReturn(new AnswerPage(List.of(cited("tr_1")), 3));
        when(substrate.observationsByIds(eq(PROJECT), any())).thenReturn(List.of());

        FlaggedAnswerPage first = service.page(finding, null, 2, null);
        FlaggedAnswerPage rest = service.page(finding, null, 2, first.nextCursor());

        assertEquals(
                List.of("tr_3", "tr_2"),
                first.rows().stream().map(FlaggedAnswerView::traceId).toList());
        assertEquals("2", first.nextCursor());
        assertEquals(
                List.of("tr_1"),
                rest.rows().stream().map(FlaggedAnswerView::traceId).toList());
        assertNull(rest.nextCursor(), "the last page");
        assertEquals(3, rest.total());
    }

    /** An RCA cause filter reaches the read as it was asked for, so the page is that cause's share. */
    @Test
    void aCauseFilterNarrowsTheRead() {
        FindingRow finding = finding(payload());
        CauseRef cause = new CauseRef("rpt_1", 1);
        when(rates.answerPage(PROJECT, CLASSIFIER, "fnd_1", cause, 50, 0))
                .thenReturn(new AnswerPage(List.of(cited("tr_2")), 1));
        when(substrate.observationsByIds(eq(PROJECT), any())).thenReturn(List.of());

        FlaggedAnswerPage page = service.page(finding, cause, 50, null);

        assertEquals(
                List.of("tr_2"),
                page.rows().stream().map(FlaggedAnswerView::traceId).toList());
        assertEquals(1, page.total());
        assertNull(page.nextCursor());
    }

    private void storedAnswer(String traceId, String spanId) {
        SubstrateObservation o = new SubstrateObservation(
                spanId,
                PROJECT,
                traceId,
                "sess_1",
                null,
                CALL_SITE,
                "llm",
                "answer",
                "When will my refund arrive?",
                ANSWER,
                null,
                "2026-09-23T14:41:00Z");
        when(substrate.observationsByIds(eq(PROJECT), any())).thenReturn(List.of(o));
        when(substrate.callSiteShapes(eq(PROJECT), any())).thenReturn(Map.of(CALL_SITE, "rag_answer"));
        when(substrate.groundingEvidence(eq(PROJECT), any()))
                .thenReturn(Map.of(
                        spanId,
                        new GroundingEvidenceReads.Evidence(
                                List.of(
                                        "Refund 4417 was issued on March 3.",
                                        "Card refunds reach the customer within five to ten business days."),
                                true)));
    }

    private static CitedAnswer cited(String traceId) {
        return new CitedAnswer(traceId, "sp", null, null, "{\"unsupported\":0.98}", false);
    }

    private static String payload() {
        return "{\"cause_kind\":\"groundedness_rate\",\"call_site_id\":\"" + CALL_SITE + "\",\"direction\":\"up\","
                + "\"baseline_traces\":640,\"baseline_flagged\":13,\"baseline_rate\":0.021,\"current_rate\":0.064,"
                + "\"traces_since_onset\":500,\"flagged_since_onset\":32,\"delta_pp\":4.3,\"effect_size\":0.2,"
                + "\"statistic\":14.1,\"threshold\":11.2,\"criticality\":1.26,\"arl_target\":50000,"
                + "\"min_decision_interval\":4,\"flag_threshold\":0.975,\"learning_until\":1000}";
    }

    private static FindingRow finding(String payload) {
        return new FindingRow(
                "fnd_1",
                PROJECT,
                "groundedness",
                "groundedness:" + CLASSIFIER + ":" + CALL_SITE,
                FindingRow.SubjectKind.CLASSIFIER,
                CLASSIFIER,
                null,
                CALL_SITE,
                FindingRow.Status.OPEN,
                "2026-09-20T00:00:00Z",
                "2026-09-23T14:00:00Z",
                null,
                null,
                null,
                32,
                payload,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-09-20T00:00:00Z",
                "2026-09-23T14:00:00Z");
    }
}
