// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedConversationView;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.classifier.frustration.FrustrationRateRepository.ConversationContext;
import ai.tessary.classifier.frustration.FrustrationRateRepository.FlaggedTurn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The finding page's frustration block: the rate off the finding's payload, and the conversations it cites, each
 * with the turn that fired, its score and that turn's own call site.
 *
 * <p>The conversations come from the finding's witness rows, not a fresh query, so the page lists exactly what
 * the finding cites. Each trace witness is paired with its conversation through the detection row that flagged
 * it, rather than by rank, because a conversation cleared and flagged again can cite two turns.
 */
@Service
public class FrustrationDetailService {

    /** Witness rows a finding holds at most: {@link FrustrationRateService#MAX_WITNESSES} pairs. */
    private static final int MAX_ROWS = FrustrationRateService.MAX_WITNESSES * 2;

    private final FindingEvidenceRepository evidence;
    private final FrustrationRateRepository rates;

    public FrustrationDetailService(FindingEvidenceRepository evidence, FrustrationRateRepository rates) {
        this.evidence = evidence;
        this.rates = rates;
    }

    /** The block for a {@code frustration_rate} finding, or null for any other finding. */
    public @Nullable FrustrationDetail detail(FindingRow finding) {
        if (!FindingRow.Cause.FRUSTRATION_RATE.equals(finding.causeKind())) return null;
        List<String> traces = new ArrayList<>();
        for (FindingEvidenceRow row : evidence.page(
                        finding.projectId(), finding.id(), FindingEvidenceRow.Role.WITNESS, MAX_ROWS, null)
                .rows()) {
            if (row.traceId() != null && row.spanId() == null) traces.add(row.traceId());
        }
        Map<String, FlaggedTurn> flagged = rates.flaggedTurns(finding.projectId(), finding.subjectId(), traces);
        Map<String, ConversationContext> context = rates.conversationContext(
                finding.projectId(), traces, FrustrationEvidence.CONTEXT_TURNS_BEFORE);
        List<FrustratedConversationView> conversations = new ArrayList<>();
        for (String trace : traces) {
            FlaggedTurn turn = flagged.get(trace);
            if (turn == null || turn.conversationId() == null) continue;
            ConversationContext ctx = context.get(trace);
            List<String> shown = new ArrayList<>(ctx == null ? List.of() : ctx.priorTraceIds());
            shown.add(trace);
            conversations.add(new FrustratedConversationView(
                    turn.conversationId(),
                    trace,
                    turn.score(),
                    turn.callSiteId(),
                    turn.startedAt(),
                    turn.cleared(),
                    ctx == null ? null : ctx.sessionId(),
                    shown,
                    turn.message()));
        }
        return FrustrationEvidence.detail(finding, conversations);
    }
}
