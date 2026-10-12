// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.timeline.DialogueMessages.DialogueMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialogueMessagesTest {

    @Test
    void aToolCallOnlyAssistantMessageIsTheBoundary() {
        String input = """
                [{"role":"user","content":"a"},
                 {"role":"assistant","content":"ok"},
                 {"role":"user","content":"b"},
                 {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",
                   "function":{"name":"lookup","arguments":"{}"}}]},
                 {"role":"tool","tool_call_id":"c1","content":"r"}]
                """;

        List<DialogueMessage> fresh = DialogueMessages.afterLastAssistant(DialogueMessages.read(input, "user"));

        assertEquals(
                List.of("tool"),
                fresh.stream().map(DialogueMessage::role).toList(),
                "a reader that skips the tool-call-only assistant message puts the boundary at \"ok\" and counts \"b\""
                        + " again in this turn");
        assertEquals(List.of("r"), fresh.stream().map(DialogueMessage::text).toList());
    }

    @Test
    void readKeepsEveryRoleInOrder() {
        String input = """
                [{"role":"system","content":"s"},{"role":"user","content":"u"},{"role":"tool","content":"t"}]
                """;

        List<DialogueMessage> messages = DialogueMessages.read(input, "user");

        assertEquals(
                List.of("system", "user", "tool"),
                messages.stream().map(DialogueMessage::role).toList(),
                "the timeline needs system and tool messages; the shared reader must not filter roles");
        assertEquals(
                List.of("s", "u", "t"),
                messages.stream().map(DialogueMessage::text).toList());
    }

    @Test
    void readDialogueGivesNoMessageForAValueThatIsNotAMessageListOrText() {
        assertEquals(
                List.of(),
                DialogueMessages.readDialogue("{\"args\":[],\"kwargs\":{\"question\":\"hi\"}}", "user"),
                "a framework's call arguments read as one message whose text is the serialized object");
        assertEquals(List.of(), DialogueMessages.readDialogue("[\"a\",\"b\"]", "user"));
        assertEquals(List.of("hi"), texts(DialogueMessages.readDialogue("hi", "user")));
        assertEquals(List.of("hi"), texts(DialogueMessages.readDialogue("\"hi\"", "user")));
        assertEquals(
                List.of("u"), texts(DialogueMessages.readDialogue("[{\"role\":\"user\",\"content\":\"u\"}]", "user")));
    }

    @Test
    void afterLastUserIsWhatAnOutputAddsToTheChatItHolds() {
        String output = """
                [{"role":"user","content":"a"},
                 {"role":"assistant","content":"x"},
                 {"role":"user","content":"b"},
                 {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"n","input":{}}]},
                 {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]},
                 {"role":"assistant","content":"y"}]
                """;

        List<DialogueMessage> fresh = DialogueMessages.afterLastUser(DialogueMessages.read(output, "assistant"));

        assertEquals(
                List.of("assistant", "user", "assistant"),
                fresh.stream().map(DialogueMessage::role).toList(),
                "a tool result sent under the user role is taken for the person's message and cuts off the loop");
        assertEquals(List.of("", "", "y"), texts(fresh));
    }

    private static List<String> texts(List<DialogueMessage> messages) {
        return messages.stream().map(DialogueMessage::text).toList();
    }
}
