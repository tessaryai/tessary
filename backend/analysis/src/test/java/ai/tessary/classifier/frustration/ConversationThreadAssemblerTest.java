// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.frustration.StructuredThread.Message;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The structured thread read off stored gen_ai columns in the shapes producers actually send besides the role
 * envelope: a plain string, a bare JSON value, and Gemini-style {@code parts}. The bugs are a thread built for a
 * span that is not a dialogue turn, and a user or assistant turn dropped because its column was not an envelope.
 */
class ConversationThreadAssemblerTest {

    private static ConversationThreadAssembler assembler(SubstrateObservation... priorOldestFirst) {
        SubstrateReadRepository substrate = mock(SubstrateReadRepository.class);
        when(substrate.priorTurns(anyString(), anyString(), anyInt()))
                .thenReturn(new SubstrateReadRepository.PriorTurns(List.of(priorOldestFirst), priorOldestFirst.length));
        return new ConversationThreadAssembler(substrate);
    }

    private static SubstrateObservation span(
            String id, @Nullable String kind, @Nullable String input, @Nullable String output) {
        return new SubstrateObservation(
                id, "p", "tr-" + id, "sess-1", null, "cs-1", kind, "chat", input, output, null, id, null);
    }

    /**
     * A span that is not a dialogue turn, or whose input holds no user message, has no thread: scoring one
     * would send the model a conversation with no user turn to judge.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NONE",
            value = {
                "tool  | [{\"role\":\"user\",\"content\":\"why\"}]",
                "NONE  | [{\"role\":\"user\",\"content\":\"why\"}]",
                "llm   | NONE",
                "llm   | [{\"role\":\"system\",\"content\":\"be brief\"}]",
                "agent | []",
                "llm   | \"   \""
            })
    void aSpanWithNoUserTurnHasNoThread(@Nullable String kind, @Nullable String input) {
        assertEquals(Optional.empty(), assembler().assembleStructured(span("t1", kind, input, null)));
    }

    /** A plain-string column, a bare JSON value and a {@code parts} message each read as the turn they carry. */
    @Test
    void columnsThatAreNotARoleEnvelopeStillReadAsTheirTurn() {
        SubstrateObservation first = span("t1", "llm", "the export is empty", "\"Try re-running it.\"");
        SubstrateObservation second = span(
                "t2", "llm", "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"still empty\"}]}]", null);
        SubstrateObservation scored = span("t3", "llm", "[{\"role\":\"user\",\"content\":\"this is useless\"}]", null);

        Optional<StructuredThread> thread = assembler(first, second).assembleStructured(scored);

        assertEquals(
                Optional.of(new StructuredThread(
                        List.of(
                                new Message("user", "the export is empty", true, false),
                                new Message("assistant", "Try re-running it.", true, false),
                                new Message("user", "still empty", true, false)),
                        new Message("user", "this is useless", true, false),
                        3)),
                thread);
    }
}
