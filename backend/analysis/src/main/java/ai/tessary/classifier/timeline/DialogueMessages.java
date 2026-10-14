// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import ai.tessary.model.ContentExtractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * Reads the role-tagged messages of a stored {@code input} or {@code output} column, and finds the messages that are
 * new in a call. The one home of the rule both the frustration thread and the session timeline use: the new messages
 * of an input are those after its last assistant message that has text or a tool call, so a call site that sends the
 * whole chat on every call gives each message once.
 */
public final class DialogueMessages {

    // Part types the structured form reads as the user-visible text of a message.
    private static final Set<String> TEXT_PART_TYPES = Set.of("", "text", "input_text", "output_text");
    // Tool parts leave no text; a tool call only marks where an assistant turn ended.
    private static final Set<String> TOOL_CALL_PART_TYPES = Set.of("tool_call", "tool_use", "function_call");
    private static final Set<String> TOOL_RESULT_PART_TYPES =
            Set.of("tool_result", "tool_call_response", "function_call_output");
    // Model reasoning is never shown to the user, so it is not part of a message's text.
    private static final Set<String> HIDDEN_PART_TYPES = Set.of("reasoning", "thinking", "redacted_thinking");
    // A redacted_thinking part carries only an encrypted blob, so it adds no reasoning text.
    private static final Set<String> REASONING_TEXT_PART_TYPES = Set.of("reasoning", "thinking");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DialogueMessages() {}

    /**
     * One message of a stored column.
     *
     * @param text text parts joined with a space, media parts as their {@link ContentExtractor#partPlaceholder}
     *     placeholders; tool parts and reasoning dropped
     * @param hasText whether a real text part (not only a media placeholder) is in {@code text}
     * @param endsInToolCall an assistant message whose last part is a tool call
     * @param dialogue the message has text or a tool call; a message with only a tool result is not dialogue
     * @param toolCalls the raw tool-call nodes, part nodes first and then OpenAI {@code tool_calls[]} entries
     * @param reasoning the texts of the reasoning parts joined with a newline, or null when there are none
     * @param source the message node as stored, or a {@code {"role","content"}} node for a non-envelope column
     */
    public record DialogueMessage(
            String role,
            String text,
            boolean hasText,
            boolean endsInToolCall,
            boolean dialogue,
            List<JsonNode> toolCalls,
            @Nullable String reasoning,
            JsonNode source) {

        public DialogueMessage {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /**
     * Every role-tagged message of a stored column, in order, whatever its role. A plain-string or non-envelope
     * column is one text message under {@code fallbackRole}, as {@link ContentExtractor#columnMessages} reads it; an
     * empty one is no message.
     */
    public static List<DialogueMessage> read(@Nullable String raw, String fallbackRole) {
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return textMessage(fallbackRole, raw.strip());
        }
        if (!ContentExtractor.isMessageEnvelope(node)) {
            return textMessage(
                    fallbackRole, ContentExtractor.flattenContentText(node).strip());
        }
        return envelope(node);
    }

    /**
     * The messages of a column that holds what a person and an agent said: a message envelope, or a plain string, which
     * is one text message under {@code fallbackRole}. Any other JSON value, such as a framework's call arguments or
     * return object, holds no message.
     */
    public static List<DialogueMessage> readDialogue(@Nullable String raw, String fallbackRole) {
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return textMessage(fallbackRole, raw.strip());
        }
        if (node != null && node.isTextual())
            return textMessage(fallbackRole, node.asText().strip());
        return ContentExtractor.isMessageEnvelope(node) ? envelope(node) : List.of();
    }

    private static List<DialogueMessage> envelope(JsonNode node) {
        List<DialogueMessage> out = new ArrayList<>();
        for (JsonNode msg : node) {
            if (!msg.isObject() || !msg.has("role")) continue;
            String role = msg.path("role").asText("");
            PartWalk walk = new PartWalk();
            JsonNode content = msg.get("content");
            if (content != null && !content.isNull()) {
                walk.node(content);
            } else if (msg.path("parts").isArray()) {
                walk.node(msg.get("parts"));
            }
            JsonNode toolCalls = msg.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                toolCalls.forEach(walk.toolCalls::add);
                walk.endsInToolCall = true; // OpenAI puts tool_calls after the message content
            }
            out.add(new DialogueMessage(
                    role,
                    walk.text.toString(),
                    walk.hasText,
                    "assistant".equals(role) && walk.endsInToolCall,
                    walk.text.length() > 0 || walk.endsInToolCall,
                    walk.toolCalls,
                    walk.reasoning.length() == 0 ? null : walk.reasoning.toString(),
                    msg));
        }
        return List.copyOf(out);
    }

    /** The messages after the last user message that has text or a tool call: what an output adds to a chat it holds. */
    public static List<DialogueMessage> afterLastUser(List<DialogueMessage> messages) {
        int afterLastUser = 0;
        for (int i = 0; i < messages.size(); i++) {
            DialogueMessage m = messages.get(i);
            if ("user".equals(m.role()) && m.dialogue()) afterLastUser = i + 1;
        }
        return messages.subList(afterLastUser, messages.size());
    }

    /** The messages after the last assistant message that has text or a tool call: what is new in this call. */
    public static List<DialogueMessage> afterLastAssistant(List<DialogueMessage> messages) {
        int afterLastReply = 0;
        for (int i = 0; i < messages.size(); i++) {
            DialogueMessage m = messages.get(i);
            if ("assistant".equals(m.role()) && m.dialogue()) afterLastReply = i + 1;
        }
        return messages.subList(afterLastReply, messages.size());
    }

    private static List<DialogueMessage> textMessage(String role, String text) {
        if (text.isEmpty()) return List.of();
        ObjectNode source = MAPPER.createObjectNode().put("role", role).put("content", text);
        return List.of(new DialogueMessage(role, text, true, false, true, List.of(), null, source));
    }

    /** Walks one message's content in order, collecting its text, tool calls and reasoning. */
    private static final class PartWalk {
        private final StringJoiner text = new StringJoiner(" ");
        private final StringJoiner reasoning = new StringJoiner("\n");
        private final List<JsonNode> toolCalls = new ArrayList<>();
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
                toolCalls.add(part);
                endsInToolCall = true;
            } else if (HIDDEN_PART_TYPES.contains(type)) {
                String thought = REASONING_TEXT_PART_TYPES.contains(type) ? ContentExtractor.partPlaceholder(part) : "";
                if (!thought.isEmpty()) reasoning.add(thought);
            } else if (!TOOL_RESULT_PART_TYPES.contains(type)) {
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
}
