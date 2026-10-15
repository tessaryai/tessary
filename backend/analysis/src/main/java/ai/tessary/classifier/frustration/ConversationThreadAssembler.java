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
 * The conversation before a scored user turn: the two prior turns of the same call site, oldest first. One
 * dialogue-bearing span per turn ({@code llm} wins over its {@code agent} twin), system messages excluded, and every
 * assistant text part between two user messages joined so an agentic turn's final answer is not lost behind its first
 * tool call.
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
     * Empty when {@code scored} is not a dialogue span, has no call site, or carries no user message. No caps or
     * eligibility rule here; see {@link StructuredThread}.
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
     * Only messages after the input's last assistant message: a call site that resends the whole chat would otherwise
     * repeat every earlier message.
     */
    private static List<StructuredThread.Message> ownUserMessages(@Nullable String input) {
        return dialogue(DialogueMessages.afterLastAssistant(DialogueMessages.read(input, "user"))).stream()
                .filter(m -> "user".equals(m.role()))
                .toList();
    }

    /**
     * Joins onto an assistant turn at the tail; user messages stay distinct, so two user messages with no reply between
     * stay two turns.
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

    /** A message left with only a tool result (sent under the user role) is not a message. */
    private static List<StructuredThread.Message> dialogue(List<DialogueMessages.DialogueMessage> messages) {
        return messages.stream()
                .filter(m -> m.dialogue() && DIALOGUE_ROLES.contains(m.role()))
                .map(m -> new StructuredThread.Message(m.role(), m.text(), m.hasText(), m.endsInToolCall()))
                .toList();
    }

    /**
     * The {@code llm} span is preferred since it carries the structured messages; an {@code agent} twin wins only when
     * a turn has no {@code llm} span.
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

    /** In v2 the turn is the trace. */
    private static String turnKey(SubstrateObservation obs) {
        return obs.traceId();
    }
}
