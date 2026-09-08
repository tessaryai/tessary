// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.substrate;

import ai.tessary.evals.model.ContentExtractor;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A substrate SPAN flattened for signal evaluation: the {@code span} row plus the correlation handles
 * it carries denormalized ({@code traceId} / {@code sessionId} / {@code projectVersionId}) and the
 * worst tool-error seen on it (for the tool-failure built-in). Read-only projection over the v2
 * substrate; the {@code signal/} slice owns this read repository, mirroring {@code risk/}'s
 * {@code VerdictSignalRepository}.
 *
 * <h2>There is no turn id any more</h2>
 *
 * <p>v1 addressed a turn with its own {@code context} row and hung the trace off it. In v2 the TURN
 * IS THE TRACE (substrate-model.md §2), so what used to be {@code turnId} is {@link #traceId()} —
 * one id where there were two that had to be kept agreeing. Callers that grouped by turn now group by
 * {@code traceId}. The field is deleted rather than aliased: an alias leaves two names for one value
 * and invites them to drift apart again.
 *
 * <p>{@code sessionId} is nullable, which is the honest shape rather than new laxity. A producer that
 * sends no session id leaves {@code trace.session_id} NULL and the trace belongs to no session;
 * nothing is synthesized to fill the hole (spec §2.1). v1 could not express that, so it invented a
 * session context for anonymous traffic and every reader believed it.
 *
 * <p>{@code input}/{@code output} are the raw payload columns read from {@code span_payload} — the
 * role-tagged {@code gen_ai} message envelope on the native OTLP path (kept raw for rendering
 * fidelity). Text detectors must NOT read them directly: the encoder classifier scores the assembled
 * conversation thread ({@link ConversationThreadAssembler}); the regex/pair/structural detectors read
 * {@link #inputText()} / {@link #outputText()} / {@link #groundingPremiseText()}, each unwrapped from
 * the envelope so a classifier never scores envelope JSON.
 */
public record SubstrateObservation(
        String observationId,
        String projectId,
        String traceId,
        @Nullable String sessionId,
        @Nullable String projectVersionId,
        @Nullable String callSiteId,
        @Nullable String kind,
        @Nullable String name,
        @Nullable String input,
        @Nullable String output,
        @Nullable String toolError,
        String createdAt) {

    /**
     * What a verdict, annotation or review row over this span puts in its NOT-NULL {@code session_id}:
     * the producer session id when the trace has one, else the producer trace id.
     *
     * <p>The column is NOT NULL and a trace need not belong to a session (spec §2.1), so the rule is
     * "give it the truest available value" — the trace's own id standing for a conversation of one.
     * Naming the rule once here is what keeps every writer of that column from picking a different
     * fallback.
     */
    public String subjectSessionId() {
        return sessionId != null ? sessionId : traceId;
    }

    /** The user-side text of {@link #input}, unwrapped from the gen_ai message envelope. */
    public String inputText() {
        return ContentExtractor.columnText(input, "user");
    }

    /**
     * The Groundedness built-in's premise source: system AND user text combined, unlike the user-only
     * {@link #inputText()}. Source content commonly rides in either — a system message ("here is the
     * document: …") or a pasted-inline user message — so both roles are kept for the premise, unlike
     * the thread view which excludes system.
     */
    public String groundingPremiseText() {
        return ContentExtractor.columnTextForRoles(input, Set.of("system", "user"));
    }

    /** The assistant-side text of {@link #output}, unwrapped from the gen_ai message envelope. */
    public String outputText() {
        return ContentExtractor.columnText(output, "assistant");
    }
}
