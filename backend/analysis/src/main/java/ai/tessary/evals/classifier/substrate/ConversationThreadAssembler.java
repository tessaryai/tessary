// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.substrate;

import ai.tessary.evals.classifier.ClassifierField;
import ai.tessary.evals.classifier.detector.EncoderDetector;
import ai.tessary.evals.classifier.substrate.ConversationThreadRenderer.Turn;
import ai.tessary.evals.config.ClassifierProperties;
import ai.tessary.evals.model.ContentExtractor;
import ai.tessary.evals.model.ContentExtractor.RoleMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Assembles the input the encoder classifier ({@link EncoderDetector}) scores: the conversation
 * thread at the scored observation's CONVERSATION grain — the turns sharing the scored turn's parent
 * context node (the conversation tier, or the session when a turn hangs directly off the session) — in
 * chronological ({@code context.seq}) order up to and including the scored turn, reduced per the context
 * contract ({@link ConversationThreadRenderer}) to a compact, trajectory-preserving form. A semantic
 * head never scores one row's text in isolation — it always sees the whole interaction, including the
 * agent's tool/failure history. Grouping at the session root would merge sibling conversations; grouping
 * at the conversation keeps a thread to the exchange the scored turn belongs to.
 *
 * <p><b>One contribution per turn.</b> The substrate carries both an {@code agent} and an {@code llm}
 * span for each turn, both bearing that turn's delta message. The walk emits the dialogue ONCE per turn
 * (preferring the {@code llm} span, which carries the structured messages) so the user↔assistant text is
 * never double-counted; a turn's {@code tool}/{@code mcp}/{@code retrieval} spans still surface as terse
 * outcome markers, which are evidence, not duplicates.
 *
 * <p><b>System messages are excluded by design.</b> A classifier here assesses the <em>interaction</em>
 * (what the user and the agent said and did), not the programmed agent's system prompt, so only
 * {@code {user, assistant}} dialogue roles are rendered — enforced by {@link
 * ContentExtractor#columnMessages}'s strict role filter (no fall-back to unlisted roles).
 *
 * <p><b>Tool outcomes are surfaced, not dropped.</b> The session walk includes tool/retrieval spans
 * ({@link #TOOL_KINDS}) alongside the {@code llm}/{@code agent} dialogue, rendering each as a terse
 * outcome marker ({@code [tool:<name> ok]} / {@code [tool:<name> error: <snippet>]}) rather than a raw
 * payload. The agent's <em>failure history</em> is the antecedent that makes quiet user frustration
 * ("nevermind, I'll do it myself") legible, so it must be visible without swamping the encoder window.
 *
 * <p><b>Which side is scored.</b> The {@link ClassifierField} selects the trailing scored turn while
 * the context defaults to the full thread: {@code INPUT} ends the thread at the scored user message
 * (frustration), {@code OUTPUT}/{@code BOTH} carry the scored user turn as context and end at the
 * scored assistant message. Only {@link #CONVERSATIONAL_KINDS} observations are ever scored.
 *
 * <p><b>How much context.</b> A head may narrow that thread via {@link ContextPolicy} — a window of
 * recent exchanges, assistant prose stubbed, or both — because a pooled single-utterance encoder is
 * DILUTED by context it has no way to weight. Narrowing is opt-in per classifier; {@link
 * ContextPolicy#FULL} (the default) leaves the thread exactly as described above.
 *
 * <p><b>Budget.</b> The rendered thread is reduced to {@link ClassifierProperties#getThreadCharBudget()}
 * characters by {@link ConversationThreadRenderer#reduceThread}: it keeps the baseline head (earliest
 * turn + earliest failure marker) and the most-recent {@link ClassifierProperties#getThreadRecentTurns()}
 * turns, thins the redundant middle, marks any drop with {@code [… N earlier turns elided …]}, and never
 * drops the scored final message.
 */
@Component
public class ConversationThreadAssembler {

    private static final Set<String> DIALOGUE_ROLES = Set.of("user", "assistant");
    private static final Set<String> USER_ROLE = Set.of("user");
    private static final Set<String> ASSISTANT_ROLE = Set.of("assistant");
    // A conversation thread is only SCORED for dialogue-bearing observations. A tool/retrieval span is
    // not a user↔assistant turn, so scoring an encoder head over it is meaningless — its thread is empty
    // and the head skips it. (Such spans still appear as CONTEXT markers on a scored dialogue obs.)
    private static final Set<String> CONVERSATIONAL_KINDS = Set.of("llm", "agent");
    // Non-dialogue spans whose OUTCOME is trajectory signal: surfaced as terse markers in the context,
    // never as raw payloads. Kept in sync with the SQL kind filter in
    // SubstrateReadRepository.conversationObservationsUpTo.
    private static final Set<String> TOOL_KINDS = Set.of("tool", "mcp", "retrieval", "embedding", "reranker");

    private final SubstrateReadRepository substrate;
    private final ClassifierProperties props;

    public ConversationThreadAssembler(SubstrateReadRepository substrate, ClassifierProperties props) {
        this.substrate = substrate;
        this.props = props;
    }

    /**
     * How much of the prior thread a head wants, and in what form — see {@link
     * ConversationThreadRenderer#windowByUserTurns} / {@link ConversationThreadRenderer#stubAssistants}
     * for why a pooled single-utterance head wants less than everything.
     *
     * @param userTurnWindow prior exchanges to keep, counted in USER turns; negative = the whole thread
     * @param stubAssistant replace assistant prose with {@code [reply]}, keeping the alternation
     * @param minPriorUserTurns skip scoring entirely until the conversation has at least this many
     *     EARLIER user turns; {@code 0} scores every turn including the opener.
     *     <p>A conversation's OPENER cannot carry frustration the agent caused — the agent has not
     *     acted yet — so whatever a head reads there is emotion the user arrived with. Measured on 300
     *     human-labelled production turns: 4% of turn-0 messages are frustrated, 9% of turn-1, then
     *     30% at turn 2. Skipping turn 0 costs 3 of 57 true positives and lifts precision 0.47 -> 0.58.
     *     This is a SCORING gate, not a rendering transform: the turn is passed over entirely, so the
     *     head is never called for it and no verdict is written either way.
     */
    public record ContextPolicy(int userTurnWindow, boolean stubAssistant, int minPriorUserTurns) {

        /** The whole thread, assistant prose intact — what every head got before this was configurable. */
        public static final ContextPolicy FULL = new ContextPolicy(-1, false, 0);

        public ContextPolicy(int userTurnWindow, boolean stubAssistant) {
            this(userTurnWindow, stubAssistant, 0);
        }
    }

    /** The reduced contract-v2 thread for {@code scored}, ending on the {@code field}-selected side. */
    public String assemble(SubstrateObservation scored, ClassifierField field) {
        return assemble(scored, field, ContextPolicy.FULL);
    }

    /** As {@link #assemble(SubstrateObservation, ClassifierField)}, narrowed by {@code policy}. */
    public String assemble(SubstrateObservation scored, ClassifierField field, ContextPolicy policy) {
        String kind = scored.kind();
        if (kind == null || !CONVERSATIONAL_KINDS.contains(kind)) {
            return ""; // a non-dialogue span has no conversation thread; the head skips it
        }
        // The scored span's full producer identity: the trace names the conversation it belongs to AND
        // is half the keyset position the window is bounded at. Passing the span id twice, as this did
        // when a bare id was an address, would now bound the page at the wrong row.
        List<SubstrateObservation> recentFirst = substrate.conversationObservationsUpTo(
                scored.projectId(),
                scored.traceId(),
                scored.observationId(),
                scored.createdAt(),
                props.getThreadMaxObservations());
        List<SubstrateObservation> chronological = new ArrayList<>(recentFirst);
        Collections.reverse(chronological);

        Map<String, String> dialogueBearer = chooseDialogueBearers(chronological, scored);

        List<Turn> context = new ArrayList<>();
        for (SubstrateObservation obs : chronological) {
            if (isScoredTurn(obs, scored)) {
                continue; // the scored turn supplies the trailing turn(s), never prior context
            }
            String k = obs.kind();
            if (k != null && TOOL_KINDS.contains(k)) {
                context.add(Turn.tool(obs.name(), obs.toolError()));
            } else if (obs.observationId().equals(dialogueBearer.get(turnKey(obs)))) {
                addDialogue(context, obs); // one contribution per turn — the agent/llm twin is skipped
            }
        }

        String scoredUser = sideText(scored.input(), USER_ROLE, "user");
        // Captured BEFORE the field branch: OUTPUT/BOTH append the scored user turn to `context`, which
        // would make the count prior+1 for those fields and quietly mean something different from what
        // the javadoc promises. Unreachable while every non-INPUT head is FULL, but the knob is public.
        int priorUserTurns = priorUserTurns(context);
        Turn finalTurn;
        if (field == ClassifierField.INPUT) {
            // Frustration ({@code INPUT}) scores the USER's message. If the scored turn carries no user
            // text — the trace's trailing message is the assistant's (a continuation / assistant-authored
            // turn awaiting the user) — there is nothing to judge: return "" so the head skips it, the same
            // contract as a non-dialogue span above. This gate is field-scoped on purpose: OUTPUT/BOTH
            // heads (e.g. groundedness) legitimately end on an assistant message and must NOT be gated here.
            if (scoredUser.isBlank()) {
                return "";
            }
            finalTurn = Turn.user(scoredUser);
        } else {
            if (!scoredUser.isBlank()) context.add(Turn.user(scoredUser));
            finalTurn = Turn.assistant(sideText(scored.output(), ASSISTANT_ROLE, "assistant"));
        }
        // Too early in the conversation to be agent-caused? Skip the turn entirely. Counted on the
        // UNNARROWED context, so the window a head reads never changes what it is eligible to score —
        // otherwise `context_user_turns` would silently move this gate too. Blank is the established
        // "head skips this row" contract (see the scoredUser gate above); the sweep's cursor still
        // advances over it, so a skipped turn is never re-offered on a later tick.
        if (policy.minPriorUserTurns() > 0 && priorUserTurns < policy.minPriorUserTurns()) {
            return "";
        }
        // Narrow BEFORE reduction: the budget-driven eviction in reduceThread keeps a baseline head
        // (earliest turn + earliest failure) that a windowed head explicitly does not want, and
        // stubbing first means the char budget is spent on turns the head actually scores.
        List<Turn> shaped = policy.userTurnWindow() < 0
                ? context
                : ConversationThreadRenderer.windowByUserTurns(context, policy.userTurnWindow());
        if (policy.stubAssistant()) {
            shaped = ConversationThreadRenderer.stubAssistants(shaped);
        }
        return ConversationThreadRenderer.reduceThread(
                shaped, finalTurn, props.getThreadCharBudget(), props.getThreadRecentTurns());
    }

    /** How many USER turns the prior context holds — the scored turn's 0-based position in its thread. */
    private static int priorUserTurns(List<Turn> context) {
        int n = 0;
        for (Turn t : context) {
            if ("user".equals(t.speaker())) n++;
        }
        return n;
    }

    /**
     * The one dialogue-bearing observation per turn: {@code turnKey → observationId}. Each turn emits its
     * user↔assistant delta ONCE even though the substrate carries both an {@code agent} and an {@code
     * llm} span for the same turn (same delta message). The {@code llm} span is preferred — it carries the
     * structured {@code gen_ai.input/output.messages} — so an {@code agent} twin only wins when a turn has
     * no {@code llm} span. The scored turn is excluded (it supplies the trailing turn, not prior context).
     */
    private static Map<String, String> chooseDialogueBearers(
            List<SubstrateObservation> chronological, SubstrateObservation scored) {
        Map<String, String> bearer = new HashMap<>();
        Map<String, Boolean> bearerIsLlm = new HashMap<>();
        for (SubstrateObservation obs : chronological) {
            String k = obs.kind();
            if (k == null || !CONVERSATIONAL_KINDS.contains(k) || isScoredTurn(obs, scored)) {
                continue;
            }
            String key = turnKey(obs);
            boolean isLlm = "llm".equals(k);
            if (!bearer.containsKey(key) || (Boolean.FALSE.equals(bearerIsLlm.get(key)) && isLlm)) {
                bearer.put(key, obs.observationId());
                bearerIsLlm.put(key, isLlm);
            }
        }
        return bearer;
    }

    /**
     * The turn-grouping key: the TRACE id. In v2 the turn is the trace (spec §2), so this is a plain
     * field read rather than the old "turn context node, falling back to the observation id when the
     * spine had no turn tier" — a fallback that grouped every spine-less span into a bucket of one.
     */
    private static String turnKey(SubstrateObservation obs) {
        return obs.traceId();
    }

    /** True when {@code obs} belongs to the scored turn — the same trace, or the scored span itself. */
    private static boolean isScoredTurn(SubstrateObservation obs, SubstrateObservation scored) {
        return obs.observationId().equals(scored.observationId())
                || scored.traceId().equals(obs.traceId());
    }

    /**
     * Append a dialogue observation's user (from input) then assistant (from output) turns. System
     * messages are excluded throughout. Tool outcomes are surfaced separately, as terse markers.
     */
    private static void addDialogue(List<Turn> turns, SubstrateObservation obs) {
        for (RoleMessage msg : ContentExtractor.columnMessages(obs.input(), DIALOGUE_ROLES, "user")) {
            turns.add(new Turn(msg.role(), msg.text()));
        }
        for (RoleMessage msg : ContentExtractor.columnMessages(obs.output(), DIALOGUE_ROLES, "assistant")) {
            turns.add(new Turn(msg.role(), msg.text()));
        }
    }

    /** The scored observation's text for one side (system-excluded, multimodal), newline-joined. */
    private static String sideText(@org.jspecify.annotations.Nullable String column, Set<String> roles, String fb) {
        StringBuilder sb = new StringBuilder();
        for (RoleMessage msg : ContentExtractor.columnMessages(column, roles, fb)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(msg.text());
        }
        return sb.toString();
    }
}
