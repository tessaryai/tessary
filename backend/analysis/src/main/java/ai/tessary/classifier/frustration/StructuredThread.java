// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import java.util.List;

/**
 * A scored user turn and the dialogue before it, one message per speaker turn, before caps or eligibility rules. Tool
 * spans are not messages; only {@link Message#endsInToolCall()} survives. @param earlier oldest first; consecutive
 * assistant messages already joined @param turn 1-based position among the conversation's turns
 */
record StructuredThread(List<Message> earlier, Message current, int turn) {

    public StructuredThread {
        earlier = List.copyOf(earlier);
    }

    /**
     * @param text media parts rendered as {@code [image]} / {@code [file: x]} placeholders @param hasText false for a
     * turn of placeholders alone @param endsInToolCall an assistant turn whose last part is a tool call with no text
     * after it
     */
    public record Message(String role, String text, boolean hasText, boolean endsInToolCall) {}
}
