// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Eligibility: the four prior messages must be exactly user, assistant, user, assistant with real text, the current one
 * has text, and neither assistant turn ended on a bare tool call; ineligible turns are not recorded. Pastes are marked
 * ({@link PasteMarker}), then each message is clipped ({@link HeadTailClip}); over budget, the oldest pair is dropped,
 * never the current message or newest pair.
 */
public final class FrustrationTurnBuilder {

    static final int PRIOR_MESSAGES = 4;

    /** In chars (a token is about four). */
    public record Caps(int currentUser, int earlierUser, int latestAssistant, int olderAssistant, int budget) {
        public static final Caps DEFAULT = new Caps(1_600, 600, 512, 128, 4_096);
    }

    public record EarlierMessage(String role, String content) {}

    @JsonPropertyOrder({"current_user_message", "earlier_messages"})
    public record TurnState(
            @JsonProperty("current_user_message") String currentUserMessage,
            @JsonProperty("earlier_messages") List<EarlierMessage> earlierMessages) {

        public TurnState {
            earlierMessages = List.copyOf(earlierMessages);
        }
    }

    /** @param userTurn the scored turn's 1-based position among its conversation's traces */
    public record EligibleTurn(TurnState state, int userTurn) {}

    private final ConversationThreadAssembler assembler;
    private final Caps caps;

    FrustrationTurnBuilder(ConversationThreadAssembler assembler) {
        this(assembler, Caps.DEFAULT);
    }

    FrustrationTurnBuilder(ConversationThreadAssembler assembler, Caps caps) {
        this.assembler = assembler;
        this.caps = caps;
    }

    /** Empty when the turn is not eligible. */
    public Optional<EligibleTurn> buildTurn(SubstrateObservation scored) {
        return assembler
                .assembleStructured(scored)
                .flatMap(thread -> format(thread, caps).map(state -> new EligibleTurn(state, thread.turn())));
    }

    /** The pure half. */
    static Optional<TurnState> format(StructuredThread thread, Caps caps) {
        List<StructuredThread.Message> earlier = thread.earlier();
        if (!thread.current().hasText() || earlier.size() < PRIOR_MESSAGES) {
            return Optional.empty();
        }
        List<StructuredThread.Message> prior = earlier.subList(earlier.size() - PRIOR_MESSAGES, earlier.size());
        for (int i = 0; i < PRIOR_MESSAGES; i++) {
            StructuredThread.Message m = prior.get(i);
            String expected = i % 2 == 0 ? "user" : "assistant";
            if (!expected.equals(m.role()) || !m.hasText() || m.endsInToolCall()) {
                return Optional.empty();
            }
        }

        String current = render(thread.current(), caps.currentUser());
        List<EarlierMessage> rendered = new ArrayList<>(List.of(
                message(prior.get(0), caps.earlierUser()),
                message(prior.get(1), caps.olderAssistant()),
                message(prior.get(2), caps.earlierUser()),
                message(prior.get(3), caps.latestAssistant())));
        int total = current.length();
        for (EarlierMessage m : rendered) {
            total += m.content().length();
        }
        if (total > caps.budget()) {
            rendered.subList(0, 2).clear();
        }
        return Optional.of(new TurnState(current, rendered));
    }

    private static EarlierMessage message(StructuredThread.Message m, int cap) {
        return new EarlierMessage(m.role(), render(m, cap));
    }

    private static String render(StructuredThread.Message m, int cap) {
        return HeadTailClip.clip(PasteMarker.mark(m.text().strip()), cap);
    }
}
