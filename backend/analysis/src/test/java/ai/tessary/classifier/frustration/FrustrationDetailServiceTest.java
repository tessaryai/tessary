// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedConversationView;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedSessionPage;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.classifier.frustration.FrustrationRateRepository.CauseRef;
import ai.tessary.classifier.frustration.FrustrationRateRepository.ConversationContext;
import ai.tessary.classifier.frustration.FrustrationRateRepository.FlaggedTurn;
import ai.tessary.classifier.frustration.FrustrationRateRepository.WitnessPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link FrustrationDetailService}: the finding's frustrated sessions a page at a time, each paired with the
 * turn that flagged it, and the agent-door copy of the block with every id taken out. The repository is a fake
 * over a mocked {@link JdbcClient}.
 */
class FrustrationDetailServiceTest {

    private static final String PROJECT = "proj_1";
    private static final String CLASSIFIER = "clf_f";

    /** The finding's witnesses, flagged turns and conversation context, as the repository would read them. */
    private static final class Witnesses extends FrustrationRateRepository {
        final List<String> traces;
        final Map<String, FlaggedTurn> turns = new HashMap<>();
        final List<Integer> offsetsRead = new ArrayList<>();

        Witnesses(String... traces) {
            super(mock(JdbcClient.class), mock(ClassifierDetectionWriteRepository.class));
            this.traces = List.of(traces);
        }

        Witnesses flagged(String trace, @Nullable String conversation) {
            turns.put(trace, new FlaggedTurn(trace, conversation, 0.9, "cs", "2026-09-23T14:00:00Z", false, "ugh"));
            return this;
        }

        @Override
        public WitnessPage witnessPage(
                String projectId,
                String classifierId,
                String findingId,
                @Nullable CauseRef cause,
                int limit,
                int offset) {
            assertEquals(List.of(PROJECT, CLASSIFIER, "fnd_1"), List.of(projectId, classifierId, findingId));
            offsetsRead.add(offset);
            int end = Math.min(traces.size(), offset + limit);
            return new WitnessPage(offset >= end ? List.of() : traces.subList(offset, end), traces.size());
        }

        @Override
        public Map<String, FlaggedTurn> flaggedTurns(String projectId, String classifierId, List<String> traceIds) {
            Map<String, FlaggedTurn> out = new HashMap<>();
            for (String t : traceIds) if (turns.containsKey(t)) out.put(t, turns.get(t));
            return out;
        }

        @Override
        public Map<String, ConversationContext> conversationContext(
                String projectId, List<String> traceIds, int before) {
            return traceIds.isEmpty()
                    ? Map.of()
                    : Map.of(traceIds.get(0), new ConversationContext("sess_1", List.of("tr_0")));
        }
    }

    /**
     * The agent door gets the rate and the tuning, never the session or trace ids: the copy keeps every number
     * and drops the sessions and the cursor that would page through them.
     */
    @Test
    void theAgentDoorCopyKeepsTheNumbersAndDropsEveryId() {
        FrustrationDetailService service =
                new FrustrationDetailService(new Witnesses("tr_1").flagged("tr_1", "conv_1"));

        FrustrationDetail block = service.detail(finding(FindingRow.Cause.FRUSTRATION_RATE));

        assertNotNull(block);
        assertEquals(
                List.of(new FrustratedConversationView(
                        "conv_1",
                        "tr_1",
                        0.9,
                        "cs",
                        "2026-09-23T14:00:00Z",
                        false,
                        "sess_1",
                        List.of("tr_0", "tr_1"),
                        "ugh")),
                block.conversations());
        assertEquals(
                new FrustrationDetail(block.rate(), 7, "jev-v1", 0.4, 10_000, 4.0, List.of(), null),
                block.withoutIds());
        assertNull(service.detail(finding("tool_error_rate")), "another cause has no frustration block");
    }

    /**
     * A cursor that is not an offset starts over at the first page rather than failing the request or reading
     * from a negative offset; a witness whose flagging turn is gone, or has no conversation, is left out.
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "-3"})
    void anUnreadableCursorStartsOverAtTheFirstPage(String cursor) {
        Witnesses witnesses =
                new Witnesses("tr_1", "tr_2", "tr_3").flagged("tr_1", "conv_1").flagged("tr_2", null);

        FrustratedSessionPage page = new FrustrationDetailService(witnesses)
                .page(finding(FindingRow.Cause.FRUSTRATION_RATE), null, 2, cursor);

        assertEquals(List.of(0), witnesses.offsetsRead);
        assertEquals(
                List.of("tr_1"),
                page.rows().stream().map(FrustratedConversationView::traceId).toList());
        assertEquals(3, page.total());
        assertEquals("2", page.nextCursor());
    }

    private static FindingRow finding(String causeKind) {
        String payload = "{\"cause_kind\":\"" + causeKind + "\",\"baseline_frustrated\":7,"
                + "\"scorer_version\":\"jev-v1\",\"jev_threshold\":0.4,\"arl_target\":10000,"
                + "\"min_decision_interval\":4}";
        return new FindingRow(
                "fnd_1",
                PROJECT,
                "frustration",
                "frustration:" + CLASSIFIER + ":cs",
                FindingRow.SubjectKind.CLASSIFIER,
                CLASSIFIER,
                null,
                "cs",
                FindingRow.Status.OPEN,
                "2026-09-20T00:00:00Z",
                "2026-09-23T14:00:00Z",
                null,
                null,
                null,
                1,
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
