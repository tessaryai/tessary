// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.model.ContentExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the conversation a scored user turn belongs to, for the frustration decision model: the two
 * turns before it ({@link SubstrateReadRepository#priorTurns}), oldest first, as separate user and
 * assistant messages. A turn is one trace. System messages are excluded. Each turn contributes its
 * dialogue once: the substrate carries both an {@code agent} and an {@code llm} span per turn, and the
 * {@code llm} span wins. Tool spans and tool parts leave no text; an assistant turn only records whether
 * it ended on a tool call. Every assistant text part between two user messages is joined into one turn,
 * reading the output of every span of the bearer's kind in the turn, so an agentic turn's final answer
 * is not lost behind its first tool call.
 */
@Component
class ConversationThreadAssembler {

    private static final Set<String> DIALOGUE_ROLES = Set.of("user", "assistant");
    private static final Set<String> USER_ROLE = Set.of("user");
    // Only dialogue-bearing spans carry a scored user turn; a tool/retrieval span has no thread.
    private static final Set<String> CONVERSATIONAL_KINDS = Set.of("llm", "agent");

    // Part types the structured form reads as the user-visible text of a message.
    private static final Set<String> TEXT_PART_TYPES = Set.of("", "text", "input_text", "output_text");
    // Tool parts leave no text in the structured form; a tool call only marks where an assistant turn ended.
    private static final Set<String> TOOL_CALL_PART_TYPES = Set.of("tool_call", "tool_use", "function_call");
    private static final Set<String> TOOL_RESULT_PART_TYPES =
            Set.of("tool_result", "tool_call_response", "function_call_output");
    // Model reasoning is never shown to the user, so it is not part of what the user reacted to.
    private static final Set<String> HIDDEN_PART_TYPES = Set.of("reasoning", "thinking", "redacted_thinking");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // The four messages before the scored one are two exchanges: two turns.
    private static final int PRIOR_TURNS = 2;

    private final SubstrateReadRepository substrate;

    ConversationThreadAssembler(SubstrateReadRepository substrate) {
        this.substrate = substrate;
    }

    /**
     * The scored user turn and the dialogue before it as separate messages, oldest first, or empty when
     * {@code scored} is not a dialogue span or carries no user message. No caps and no eligibility rule
     * are applied here; see {@link StructuredThread}.
     */
    public Optional<StructuredThread> assembleStructured(SubstrateObservation scored) {
        String kind = scored.kind();
        if (kind == null || !CONVERSATIONAL_KINDS.contains(kind)) {
            return Optional.empty();
        }
        List<StructuredThread.Message> scoredUser = new ArrayList<>();
        appendMessages(scoredUser, scored.input(), "user", USER_ROLE);
        if (scoredUser.isEmpty()) {
            return Optional.empty();
        }
        SubstrateReadRepository.PriorTurns prior =
                substrate.priorTurns(scored.projectId(), scored.traceId(), PRIOR_TURNS);
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
                appendMessages(earlier, obs.input(), "user", DIALOGUE_ROLES);
            }
            appendMessages(earlier, obs.output(), "assistant", DIALOGUE_ROLES);
        }
        return Optional.of(new StructuredThread(earlier, joined(scoredUser), prior.count() + 1));
    }

    /**
     * Append a gen_ai column's {@code roles} messages to {@code into}, joining an assistant message onto
     * an assistant turn already at the tail. User messages are kept distinct, so a user who wrote twice
     * with no reply between stays visible as two user turns.
     */
    private static void appendMessages(
            List<StructuredThread.Message> into, @Nullable String column, String fallbackRole, Set<String> roles) {
        for (StructuredThread.Message msg : columnStructuredMessages(column, fallbackRole, roles)) {
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
     * The {@code roles} messages of one stored column, part by part: text parts kept, media parts as
     * their contract-v2 placeholders ({@link ContentExtractor#partPlaceholder}), tool parts and
     * reasoning dropped. A message left with nothing but a tool result (a tool-result carrier sent under
     * the user role) is not a message. A plain-string or non-envelope column is one text message under
     * {@code fallbackRole}, as {@link ContentExtractor#columnMessages} reads it.
     */
    private static List<StructuredThread.Message> columnStructuredMessages(
            @Nullable String raw, String fallbackRole, Set<String> roles) {
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return textMessage(fallbackRole, raw.strip());
        }
        if (!ContentExtractor.isMessageEnvelope(node)) {
            // Every caller's fallback role is one of the roles it reads, so the bare payload is that turn.
            return textMessage(
                    fallbackRole, ContentExtractor.flattenContentText(node).strip());
        }
        List<StructuredThread.Message> out = new ArrayList<>();
        for (JsonNode msg : node) {
            if (!msg.isObject()) continue;
            String role = msg.path("role").asText("");
            if (!roles.contains(role)) continue;
            PartWalk walk = new PartWalk();
            JsonNode content = msg.get("content");
            if (content != null && !content.isNull()) {
                walk.node(content);
            } else if (msg.path("parts").isArray()) {
                walk.node(msg.get("parts"));
            }
            JsonNode toolCalls = msg.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                walk.endsInToolCall = true; // OpenAI puts tool_calls after the message content
            }
            if (walk.text.length() > 0 || walk.endsInToolCall) {
                out.add(new StructuredThread.Message(
                        role, walk.text.toString(), walk.hasText, "assistant".equals(role) && walk.endsInToolCall));
            }
        }
        return List.copyOf(out);
    }

    private static List<StructuredThread.Message> textMessage(String role, String text) {
        return text.isEmpty() ? List.of() : List.of(new StructuredThread.Message(role, text, true, false));
    }

    /** Walks one message's content in order, collecting its text and whether it ended on a tool call. */
    private static final class PartWalk {
        private final StringJoiner text = new StringJoiner(" ");
        private boolean hasText;
        private boolean endsInToolCall;

        void node(JsonNode node) {
            if (node.isArray()) {
                for (JsonNode part : node) part(part);
            } else {
                part(node);
            }
        }

        private void part(JsonNode part) {
            if (part.isTextual()) {
                append(part.asText().strip(), true);
                return;
            }
            if (!part.isObject()) return;
            String type = part.path("type").asText("");
            if (TOOL_CALL_PART_TYPES.contains(type)) {
                endsInToolCall = true;
            } else if (!TOOL_RESULT_PART_TYPES.contains(type) && !HIDDEN_PART_TYPES.contains(type)) {
                append(ContentExtractor.partPlaceholder(part), TEXT_PART_TYPES.contains(type));
            }
        }

        private void append(String rendered, boolean isText) {
            if (rendered.isEmpty()) return;
            text.add(rendered);
            hasText |= isText;
            endsInToolCall = false;
        }
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
