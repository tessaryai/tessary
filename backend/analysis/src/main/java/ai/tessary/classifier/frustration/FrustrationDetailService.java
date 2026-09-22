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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The finding page's frustration block: the rate off the finding's payload, and the frustrated sessions it cites,
 * each with the turn that fired, its score and that turn's own call site.
 *
 * <p>The sessions come from the finding's witness rows, not a fresh query, so the page lists exactly what the
 * finding cites. The witnesses are every frustrated session, so they are read a page at a time, newest flag first:
 * the block carries the first {@link #PAGE_SIZE}, and {@link #page} the rest. Each trace witness is paired with
 * its session through the detection row that flagged it, rather than by rank, because a session cleared and
 * flagged again can cite two turns.
 */
@Service
public class FrustrationDetailService {

    /** Sessions the block carries, and a page's default size. */
    public static final int PAGE_SIZE = 50;

    private final FrustrationRateRepository rates;

    public FrustrationDetailService(FrustrationRateRepository rates) {
        this.rates = rates;
    }

    /** The block for a {@code frustration_rate} finding, or null for any other finding. */
    public @Nullable FrustrationDetail detail(FindingRow finding) {
        if (!FindingRow.Cause.FRUSTRATION_RATE.equals(finding.causeKind())) return null;
        FrustratedSessionPage first = page(finding, null, PAGE_SIZE, null);
        return FrustrationEvidence.detail(finding, first.rows(), first.nextCursor());
    }

    /**
     * One page of the frustrated sessions {@code finding} cites, newest flag first. With {@code cause} set, only
     * the sessions that RCA cause names.
     *
     * @param cursor the {@code nextCursor} of the page before, or null for the first
     */
    public FrustratedSessionPage page(
            FindingRow finding, @Nullable CauseRef cause, int limit, @Nullable String cursor) {
        int offset = decode(cursor);
        WitnessPage witnesses =
                rates.witnessPage(finding.projectId(), finding.subjectId(), finding.id(), cause, limit, offset);
        List<String> ids = witnesses.traceIds();
        Map<String, FlaggedTurn> flagged = rates.flaggedTurns(finding.projectId(), finding.subjectId(), ids);
        Map<String, ConversationContext> context =
                rates.conversationContext(finding.projectId(), ids, FrustrationEvidence.CONTEXT_TURNS_BEFORE);
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

    /** The cursor is the next row's offset; anything unreadable starts over. */
    private static int decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
