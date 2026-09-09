// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One trace reduced to its action skeleton — the unit the behaviour-drift classifier scores. Where a
 * {@link SubstrateObservation} is one span with its text, a {@code Trajectory} is a whole trace with
 * its text discarded and only the {@link ActionSymbol} sequence kept.
 *
 * @param contextId the trace's turn context — the subject ancestry a trace-grain verdict is written on.
 * @param sessionId the session (path root); the graduation bars count DISTINCT sessions, so one
 *     runaway conversation cannot teach the model that a broken tool is standard.
 * @param symbols the padded sequence {@code ^ … $}. Padding is what makes "conversations now start
 *     differently" and "the confirmation step disappeared" expressible as n-grams.
 * @param createdAt when the row LANDED — ingest order, which is what the sweep cursor advances on.
 * @param eventAt when the trace actually RAN, as an ISO-8601 instant, falling back to ingest time when
 *     the producer omitted a start. The two clocks diverge by design on any backfill: measured on one
 *     corpus, 43 days of real traffic ingested over 15. Every rule about a span of time — graduation,
 *     decay — is a question about the agent's timeline and must read this; only the cursor reads
 *     {@code createdAt}, because a backfilled trace has to land AFTER the watermark to be swept at all.
 */
public record Trajectory(
        String traceId,
        /**
         * The NOT-NULL {@code session_id} a verdict over this trace carries: the producer session, or
         * the trace's own id when the producer sent none. See {@code TraceHead#subjectSessionId()}.
         */
        String contextId,
        /**
         * The producer session, or null for anonymous traffic. v2 synthesizes no session to fill that
         * hole (spec §2.1), so this is genuinely absent rather than a placeholder — {@link #contextId}
         * is what a writer uses when it needs a value.
         */
        @Nullable String sessionId,
        @Nullable String projectVersionId,
        List<String> symbols,
        /**
         * The sweep watermark. In v2 this is the trace's {@code started_at}, the same value as
         * {@link #eventAt}: {@code trace} carries no ingest clock, so the two columns that could once
         * disagree are one.
         */
        String createdAt,
        String eventAt) {

    public Trajectory {
        symbols = List.copyOf(symbols);
    }

    /** True when the trace carried no action at all (padding only) — nothing to score. */
    public boolean isEmpty() {
        return symbols.size() <= 2;
    }
}
