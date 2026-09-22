// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.List;

/**
 * A scored user turn and the dialogue before it, one message per speaker turn, oldest first: what
 * {@link ConversationThreadAssembler#assembleStructured} reads off the substrate before any caps or
 * eligibility rule is applied. Tool spans and tool parts are not messages here; only whether an
 * assistant turn ended on a tool call survives, as {@link Message#endsInToolCall()}.
 *
 * @param earlier the messages before the scored turn, oldest first; consecutive assistant messages are
 *     already joined into one turn
 * @param current the scored turn's user message
 */
public record StructuredThread(List<Message> earlier, Message current) {

    public StructuredThread {
        earlier = List.copyOf(earlier);
    }

    /**
     * One speaker turn.
     *
     * @param role {@code user} or {@code assistant}
     * @param text the rendered text, media parts as their {@code [image]} / {@code [file: x]} placeholders
     * @param hasText whether a real text part is present; a turn of placeholders alone has none
     * @param endsInToolCall an assistant turn whose last part is a tool call with no text after it
     */
    public record Message(String role, String text, boolean hasText, boolean endsInToolCall) {}
}
