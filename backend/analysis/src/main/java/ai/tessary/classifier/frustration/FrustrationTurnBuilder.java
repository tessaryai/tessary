// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a user turn is sent to the frustration decision model, and builds the state it is
 * sent as: the current user message and the four messages before it, oldest first.
 *
 * <p><b>Eligibility.</b> A turn is sent only when the four messages immediately before it are exactly
 * user, assistant, user, assistant, each with real text, the current message has real text, and neither
 * assistant turn ended on a tool call with no text after it. So a conversation's opener and its second
 * user turn are never sent, and an assistant message is never sent empty. An ineligible turn is not
 * sent and nothing is recorded for it.
 *
 * <p><b>Caps.</b> Pastes are marked first ({@link PasteMarker}), then each message is cut to its cap
 * ({@link HeadTailClip}): the current user message gets the most room, the latest assistant reply
 * more than the older one. If the total still exceeds the budget, the oldest user and assistant pair is
 * dropped; the current message and the newest pair are never dropped.
 */
public final class FrustrationTurnBuilder {

    private static final int PRIOR_MESSAGES = 4;

    /**
     * Per-message caps and the total budget, in chars (a token is about four).
     *
     * @param currentUser the message being judged
     * @param earlierUser each earlier user message
     * @param latestAssistant the reply the current message answers
     * @param olderAssistant the reply before that
     * @param budget all messages together
     */
    public record Caps(int currentUser, int earlierUser, int latestAssistant, int olderAssistant, int budget) {
        public static final Caps DEFAULT = new Caps(1_600, 600, 512, 128, 4_096);
    }

    /** One earlier message, as the decision model reads it. */
    public record EarlierMessage(String role, String content) {}

    /** The state sent for one turn. */
    @JsonPropertyOrder({"current_user_message", "earlier_messages"})
    public record TurnState(
            @JsonProperty("current_user_message") String currentUserMessage,
            @JsonProperty("earlier_messages") List<EarlierMessage> earlierMessages) {

        public TurnState {
            earlierMessages = List.copyOf(earlierMessages);
        }
    }

    /**
     * An eligible turn: the state to send and which user turn of its conversation it is.
     *
     * @param userTurn the scored message's 1-based position among the conversation's user messages
     */
    public record EligibleTurn(TurnState state, int userTurn) {}

    private final ConversationThreadAssembler assembler;
    private final Caps caps;

    public FrustrationTurnBuilder(ConversationThreadAssembler assembler) {
        this(assembler, Caps.DEFAULT);
    }

    public FrustrationTurnBuilder(ConversationThreadAssembler assembler, Caps caps) {
        this.assembler = assembler;
        this.caps = caps;
    }

    /** The state to send for {@code scored}, or empty when the turn is not eligible. */
    public Optional<TurnState> build(SubstrateObservation scored) {
        return buildTurn(scored).map(EligibleTurn::state);
    }

    /** {@link #build}, with the scored message's position among the conversation's user messages. */
    public Optional<EligibleTurn> buildTurn(SubstrateObservation scored) {
        return assembler
                .assembleStructured(scored)
                .flatMap(thread -> format(thread, caps).map(state -> new EligibleTurn(state, userTurn(thread))));
    }

    private static int userTurn(StructuredThread thread) {
        int users = 1;
        for (StructuredThread.Message m : thread.earlier()) {
            if ("user".equals(m.role())) users++;
        }
        return users;
    }

    /** {@link #build}'s pure half: eligibility, pastes, caps and budget over an assembled thread. */
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
