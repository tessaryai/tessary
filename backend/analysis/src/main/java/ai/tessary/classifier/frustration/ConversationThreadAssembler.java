// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.timeline.DialogueMessages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the conversation a scored user turn belongs to, for the frustration decision model: the two
 * turns of the same call site before it ({@link SubstrateReadRepository#priorTurns}), oldest first, as
 * separate user and assistant messages. A turn is one trace; a router or memory call in the same trace
 * is another call site and adds nothing. System messages are excluded. A turn's user messages are those
 * after its input's last assistant message, so a call site that sends the whole chat on every call
 * gives each turn once. Each turn contributes its dialogue once: the substrate can carry both an {@code
 * agent} and an {@code llm} span per turn, and the {@code llm} span wins. Tool spans and tool parts leave no text; an assistant turn only records whether
 * it ended on a tool call. Every assistant text part between two user messages is joined into one turn,
 * reading the output of every span of the bearer's kind in the turn, so an agentic turn's final answer
 * is not lost behind its first tool call.
 */
@Component
class ConversationThreadAssembler {

    private static final Set<String> DIALOGUE_ROLES = Set.of("user", "assistant");
    // Only dialogue-bearing spans carry a scored user turn; a tool/retrieval span has no thread.
    private static final Set<String> CONVERSATIONAL_KINDS = Set.of("llm", "agent");

    // The four messages before the scored one are two exchanges: two turns.
    private static final int PRIOR_TURNS = 2;

    private final SubstrateReadRepository substrate;

    ConversationThreadAssembler(SubstrateReadRepository substrate) {
        this.substrate = substrate;
    }

    /**
     * The scored user turn and the dialogue before it as separate messages, oldest first, or empty when
     * {@code scored} is not a dialogue span, has no call site, or carries no user message. No caps and no eligibility rule
     * are applied here; see {@link StructuredThread}.
     */
    public Optional<StructuredThread> assembleStructured(SubstrateObservation scored) {
        String kind = scored.kind();
        if (kind == null || !CONVERSATIONAL_KINDS.contains(kind)) {
            return Optional.empty();
        }
        String callSite = scored.callSiteId();
        List<StructuredThread.Message> scoredUser = ownUserMessages(scored.input());
        if (callSite == null || scoredUser.isEmpty()) {
            return Optional.empty();
        }
        SubstrateReadRepository.PriorTurns prior =
                substrate.priorTurns(scored.projectId(), scored.traceId(), callSite, PRIOR_TURNS);
        List<SubstrateObservation> chronological = prior.spans();

        Map<String, String> dialogueBearer = chooseDialogueBearers(chronological);
        Map<String, String> bearerKind = new HashMap<>();
        for (SubstrateObservation obs : chronological) {
            if (obs.observationId().equals(dialogueBearer.get(turnKey(obs)))) {
                bearerKind.put(turnKey(obs), obs.kind());
            }
        }

        List<StructuredThread.Message> earlier = new ArrayList<>();
        for (SubstrateObservation obs : chronological) {
            String turnKind = bearerKind.get(turnKey(obs));
            if (turnKind == null || !turnKind.equals(obs.kind())) {
                continue; // the agent twin of a turn that has an llm span
            }
            if (obs.observationId().equals(dialogueBearer.get(turnKey(obs)))) {
                appendMessages(earlier, ownUserMessages(obs.input()));
            }
            appendMessages(earlier, dialogue(DialogueMessages.read(obs.output(), "assistant")));
        }
        return Optional.of(new StructuredThread(earlier, joined(scoredUser), prior.count() + 1));
    }

    /**
     * A turn's own user messages: those after the input's last assistant message. A call site that sends the
     * whole chat on every call repeats every earlier message, and any context block it sends under the user
     * role, in each turn's input; only what follows the last reply is new in that turn.
     */
    private static List<StructuredThread.Message> ownUserMessages(@Nullable String input) {
        return dialogue(DialogueMessages.afterLastAssistant(DialogueMessages.read(input, "user"))).stream()
                .filter(m -> "user".equals(m.role()))
                .toList();
    }

    /**
     * Append {@code messages} to {@code into}, joining an assistant message onto an assistant turn already
     * at the tail. User messages are kept distinct, so a user who wrote twice with no reply between stays
     * visible as two user turns.
     */
    private static void appendMessages(List<StructuredThread.Message> into, List<StructuredThread.Message> messages) {
        for (StructuredThread.Message msg : messages) {
            int last = into.size() - 1;
            if ("assistant".equals(msg.role())
                    && last >= 0
                    && "assistant".equals(into.get(last).role())) {
                into.set(last, joined(List.of(into.get(last), msg)));
            } else {
                into.add(msg);
            }
        }
    }

    /** Several messages of one speaker as one turn: texts newline-joined, the last message's tool ending. */
    private static StructuredThread.Message joined(List<StructuredThread.Message> msgs) {
        StringBuilder text = new StringBuilder();
        boolean hasText = false;
        for (StructuredThread.Message m : msgs) {
            if (!m.text().isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(m.text());
            }
            hasText |= m.hasText();
        }
        StructuredThread.Message lastMsg = msgs.get(msgs.size() - 1);
        return new StructuredThread.Message(lastMsg.role(), text.toString(), hasText, lastMsg.endsInToolCall());
    }

    /**
     * The user and assistant dialogue messages of one stored column ({@link DialogueMessages#read}). A message left
     * with nothing but a tool result (a tool-result carrier sent under the user role) is not a message.
     */
    private static List<StructuredThread.Message> dialogue(List<DialogueMessages.DialogueMessage> messages) {
        return messages.stream()
                .filter(m -> m.dialogue() && DIALOGUE_ROLES.contains(m.role()))
                .map(m -> new StructuredThread.Message(m.role(), m.text(), m.hasText(), m.endsInToolCall()))
                .toList();
    }

    /**
     * The one dialogue-bearing observation per turn: {@code turnKey → observationId}. Each turn emits its
     * user↔assistant delta ONCE even though the substrate carries both an {@code agent} and an {@code
     * llm} span for the same turn (same delta message). The {@code llm} span is preferred — it carries the
     * structured {@code gen_ai.input/output.messages} — so an {@code agent} twin only wins when a turn has
     * no {@code llm} span.
     */
    private static Map<String, String> chooseDialogueBearers(List<SubstrateObservation> chronological) {
        Map<String, String> bearer = new HashMap<>();
        Map<String, Boolean> bearerIsLlm = new HashMap<>();
        for (SubstrateObservation obs : chronological) {
            String k = obs.kind();
            if (k == null || !CONVERSATIONAL_KINDS.contains(k)) {
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
}
