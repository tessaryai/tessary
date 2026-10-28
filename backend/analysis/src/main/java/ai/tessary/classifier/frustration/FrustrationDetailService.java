// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedConversationView;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustratedSessionPage;
import ai.tessary.classifier.frustration.FrustrationEvidence.FrustrationDetail;
import ai.tessary.classifier.frustration.FrustrationRateRepository.CauseRef;
import ai.tessary.classifier.frustration.FrustrationRateRepository.ConversationContext;
import ai.tessary.classifier.frustration.FrustrationRateRepository.FlaggedTurn;
import ai.tessary.classifier.frustration.FrustrationRateRepository.WitnessPage;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.EarlierMessage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The finding page's frustration block. Sessions come from the finding's witness rows, so the page lists exactly what
 * the finding cites; they are paged newest flag first. A trace witness is paired with its session through the detection
 * row that flagged it, not by rank, because a session cleared and flagged again can cite two turns.
 */
@Service
public class FrustrationDetailService {

    public static final int PAGE_SIZE = 50;

    private final FrustrationRateRepository rates;

    public FrustrationDetailService(FrustrationRateRepository rates) {
        this.rates = rates;
    }

    /** Null for any finding other than {@code frustration_rate}. */
    public @Nullable FrustrationDetail detail(FindingRow finding) {
        if (!FindingRow.Cause.FRUSTRATION_RATE.equals(finding.causeKind())) return null;
        FrustratedSessionPage first = page(finding, null, PAGE_SIZE, null);
        return FrustrationEvidence.detail(finding, first.rows(), first.nextCursor());
    }

    /**
     * With {@code cause} set, only the sessions that RCA cause names. @param cursor the previous page's
     * {@code nextCursor}, or null for the first
     */
    public FrustratedSessionPage page(
            FindingRow finding, @Nullable CauseRef cause, int limit, @Nullable String cursor) {
        int offset = decode(cursor);
        String callSite = finding.nativeCauseKey();
        WitnessPage witnesses = rates.witnessPage(
                finding.projectId(), finding.subjectId(), callSite, finding.id(), cause, limit, offset);
        List<String> ids = witnesses.traceIds();
        Map<String, FlaggedTurn> flagged = rates.flaggedTurns(finding.projectId(), finding.subjectId(), callSite, ids);
        Map<String, ConversationContext> context =
                rates.conversationContext(finding.projectId(), callSite, ids, FrustrationEvidence.CONTEXT_TURNS_BEFORE);
        List<FrustratedConversationView> rows = new ArrayList<>(ids.size());
        for (String trace : ids) {
            FlaggedTurn turn = flagged.get(trace);
            if (turn == null || turn.conversationId() == null) continue;
            ConversationContext ctx = context.get(trace);
            List<String> shown = new ArrayList<>(ctx == null ? List.of() : ctx.priorTraceIds());
            shown.add(trace);
            rows.add(new FrustratedConversationView(
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
        int next = offset + ids.size();
        return new FrustratedSessionPage(
                rows, witnesses.total(), next < witnesses.total() ? Integer.toString(next) : null);
    }

    /**
     * The last messages before each flagged turn, as the detector judged it, keyed by witness trace id and session id.
     * Empty for any finding other than {@code frustration_rate}.
     */
    public Map<String, List<EarlierMessage>> priorMessages(
            FindingRow finding, Collection<String> traceIds, Collection<String> sessionIds) {
        if (!FindingRow.Cause.FRUSTRATION_RATE.equals(finding.causeKind())) return Map.of();
        Map<String, List<EarlierMessage>> out = new HashMap<>();
        rates.earlierMessages(finding.projectId(), finding.subjectId(), finding.nativeCauseKey(), traceIds, sessionIds)
                .forEach((key, messages) -> out.put(
                        key,
                        List.copyOf(messages.subList(
                                Math.max(0, messages.size() - FrustrationTurnBuilder.PRIOR_MESSAGES),
                                messages.size()))));
        return out;
    }

    /** The next row's offset; anything unreadable starts over. */
    private static int decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
