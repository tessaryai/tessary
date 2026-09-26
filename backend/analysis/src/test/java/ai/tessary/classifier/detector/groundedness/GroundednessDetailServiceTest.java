// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.detector.GroundingEvidenceReads;
import ai.tessary.classifier.detector.GroundingEvidenceReads.SpanRef;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link GroundednessDetailService}: the rate is read off the finding's payload, each cited answer is read back
 * as it was scored so its flagged sentences slice to their text, an answer whose trace is gone says so, and the
 * witnesses page by offset. The repositories are fakes over a mocked {@link JdbcClient}.
 */
class GroundednessDetailServiceTest {

    private static final String PROJECT = "proj_1";
    private static final String CLASSIFIER = "clf_g";
    private static final String CALL_SITE = "support.answer";

    private static final String ANSWER =
            "The refund was issued on March 3. It arrives within two business days by bank transfer.";
    private static final String QUESTION = "When will my refund arrive?";
    private static final List<String> DOCUMENTS = List.of(
            "Refund 4417 was issued on March 3.", "Card refunds reach the customer within five to ten business days.");

    /** One read of the finding's cited answers, by every argument. */
    private record PageKey(@Nullable CauseRef cause, int limit, int offset) {}

    /** The finding's cited answers: each page by the arguments that read it; any other read is a test error. */
    private static final class CitedPages extends GroundednessRateRepository {
        final Map<PageKey, AnswerPage> pages = new HashMap<>();

        CitedPages() {
            super(mock(JdbcClient.class), mock(ClassifierDetectionWriteRepository.class));
        }

        CitedPages with(@Nullable CauseRef cause, int limit, int offset, AnswerPage page) {
            pages.put(new PageKey(cause, limit, offset), page);
            return this;
        }

        @Override
        public AnswerPage answerPage(
                String projectId,
                String classifierId,
                String findingId,
                @Nullable CauseRef cause,
                int limit,
                int offset) {
            assertEquals(List.of(PROJECT, CLASSIFIER, "fnd_1"), List.of(projectId, classifierId, findingId));
            AnswerPage page = pages.get(new PageKey(cause, limit, offset));
            if (page == null) throw new AssertionError("unexpected read " + new PageKey(cause, limit, offset));
            return page;
        }
    }

    /**
     * The stored spans, their call site's shape and their retrieved documents. A read by ids answers in the
     * reverse of the order asked, which the repository's contract ("in no order") allows.
     */
    private static final class StoredSpans extends SubstrateReadRepository {
        final List<SubstrateObservation> spans = new ArrayList<>();
        final Map<String, GroundingEvidenceReads.Evidence> evidence = new HashMap<>();

        StoredSpans() {
            super(mock(JdbcClient.class));
        }

        StoredSpans with(SubstrateObservation span, List<String> documents) {
            spans.add(span);
            evidence.put(span.observationId(), new GroundingEvidenceReads.Evidence(documents, true));
            return this;
        }

        @Override
        public List<SubstrateObservation> observationsByIds(String projectId, Collection<SpanRef> refs) {
            assertEquals(PROJECT, projectId);
            List<SubstrateObservation> out = new ArrayList<>();
            for (SpanRef ref : List.copyOf(refs).reversed()) {
                for (SubstrateObservation o : spans) {
                    if (o.traceId().equals(ref.traceId()) && o.observationId().equals(ref.spanId())) out.add(o);
                }
            }
            return out;
        }

        @Override
        public Map<String, String> callSiteShapes(String projectId, Set<String> callSiteIds) {
            return callSiteIds.contains(CALL_SITE) ? Map.of(CALL_SITE, "rag_answer") : Map.of();
        }

        @Override
        public Map<String, GroundingEvidenceReads.Evidence> groundingEvidence(String projectId, Set<SpanRef> refs) {
            return refs.stream()
                    .filter(r -> evidence.containsKey(r.spanId()))
                    .collect(Collectors.toMap(SpanRef::spanId, r -> evidence.get(r.spanId())));
        }
    }

    private static GroundednessDetailService service(CitedPages rates, StoredSpans substrate) {
        return new GroundednessDetailService(rates, substrate, new ObjectMapper());
    }

    @Test
    void anyOtherFindingHasNoBlock() {
        assertNull(
                service(new CitedPages(), new StoredSpans()).detail(finding("{\"cause_kind\":\"frustration_rate\"}")));
    }

    @Test
    void theRateNumbersComeFromThePayload() {
        CitedPages rates =
                new CitedPages().with(null, GroundednessDetailService.PAGE_SIZE, 0, new AnswerPage(List.of(), 0));

        GroundednessDetail block = service(rates, new StoredSpans()).detail(finding(payload()));

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

    /** The substrate answers in reverse; each answer still carries its own text, question and documents. */
    @Test
    void twoCitedAnswersEachCarryTheirOwnSpan() {
        String otherQuestion = "Can I change my delivery address?";
        String otherAnswer = "Yes, until the parcel leaves the warehouse.";
        List<String> otherDocuments = List.of("Addresses can be changed before dispatch.");
        CitedPages rates = new CitedPages()
                .with(
                        null,
                        50,
                        0,
                        new AnswerPage(
                                List.of(
                                        new CitedAnswer("tr_1", "sp_1", "sess_1", "2026-09-23T14:41:00Z", "{}", false),
                                        new CitedAnswer("tr_2", "sp_2", "sess_1", "2026-09-23T14:40:00Z", "{}", false)),
                                2));
        StoredSpans substrate = new StoredSpans()
                .with(span("tr_1", "sp_1", QUESTION, ANSWER), DOCUMENTS)
                .with(span("tr_2", "sp_2", otherQuestion, otherAnswer), otherDocuments);

        FlaggedAnswerPage page = service(rates, substrate).page(finding(payload()), null, 50, null);

        assertEquals(
                List.of(
                        new FlaggedAnswerView(
                                "tr_1",
                                "sp_1",
                                "sess_1",
                                "2026-09-23T14:41:00Z",
                                null,
                                QUESTION,
                                ANSWER,
                                List.of(),
                                List.of(
                                        new RetrievedDocumentView(null, DOCUMENTS.get(0)),
                                        new RetrievedDocumentView(null, DOCUMENTS.get(1))),
                                true,
                                true,
                                false),
                        new FlaggedAnswerView(
                                "tr_2",
                                "sp_2",
                                "sess_1",
                                "2026-09-23T14:40:00Z",
                                null,
                                otherQuestion,
                                otherAnswer,
                                List.of(),
                                List.of(new RetrievedDocumentView(null, otherDocuments.get(0))),
                                true,
                                true,
                                false)),
                page.rows());
    }

    /** An RCA cause filter reaches the read as it was asked for, so the page is that cause's share. */
    @Test
    void aCauseFilterNarrowsTheRead() {
        FindingRow finding = finding(payload());
        CauseRef cause = new CauseRef("rpt_1", 1);
        CitedPages rates = new CitedPages().with(cause, 50, 0, new AnswerPage(List.of(cited("tr_2")), 1));

        FlaggedAnswerPage page = service(rates, new StoredSpans()).page(finding, cause, 50, null);

        assertEquals(
                List.of("tr_2"),
                page.rows().stream().map(FlaggedAnswerView::traceId).toList());
        assertEquals(1, page.total());
        assertNull(page.nextCursor());
    }

    /**
     * A cursor that is not an offset starts over at the first page instead of failing the request or reading a
     * negative offset, and an answer whose stored score will not parse is still listed, unscored.
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "-4"})
    void anUnreadableCursorStartsOverAndAnUnreadableScoreIsUnscored(String cursor) {
        CitedPages rates = new CitedPages()
                .with(
                        null,
                        50,
                        0,
                        new AnswerPage(List.of(new CitedAnswer("tr_1", "sp", null, null, "{not json", false)), 1));

        FlaggedAnswerPage page = service(rates, new StoredSpans()).page(finding(payload()), null, 50, cursor);

        assertEquals(
                new FlaggedAnswerPage(
                        List.of(new FlaggedAnswerView(
                                "tr_1", "sp", null, null, null, null, null, List.of(), null, false, false, false)),
                        1,
                        null),
                page);
    }

    private static SubstrateObservation span(String traceId, String spanId, String question, String answer) {
        return new SubstrateObservation(
                spanId,
                PROJECT,
                traceId,
                "sess_1",
                null,
                CALL_SITE,
                "llm",
                "answer",
                question,
                answer,
                null,
                "2026-09-23T14:41:00Z",
                null);
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
