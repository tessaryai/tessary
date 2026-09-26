// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import java.time.Instant;
import java.util.List;

/**
 * Seeds the preamble a frustration fixture needs to be scoreable: a user turn is sent only when the four messages
 * before it are user, assistant, user, assistant, each with text. Without it a fixture asserts on a turn production
 * never scores, and the failure reads as a cursor or grain bug.
 *
 * <p>Call before the turn under test, in the same session. Each preamble turn is one trace with a root {@code llm}
 * span, stamped earlier and written first, since the thread window orders by {@code (created_at, trace_id, id)}.
 */
public final class ClassifierConversations {

    private ClassifierConversations() {}

    /** How far before {@code beforeTs} the first preamble turn is stamped, clear of any same-timestamp group. */
    private static final int PREAMBLE_LEAD_SECONDS = 120;

    /**
     * Two benign exchanges in {@code sessionId} ahead of {@code beforeTs}.
     *
     * @param beforeTs the turn under test's timestamp; the preamble lands two and one minutes earlier
     * @return the two preamble spans, oldest first
     */
    public static List<SpanRef> seedPreamble(
            SubstrateV2Fixtures fx, String projectId, String sessionId, String beforeTs) {
        Instant first = Instant.parse(beforeTs).minusSeconds(PREAMBLE_LEAD_SECONDS);
        return List.of(
                exchange(
                        fx,
                        projectId,
                        sessionId,
                        first,
                        "hello, i have a question about my account",
                        "Happy to help. What would you like to know?"),
                exchange(
                        fx,
                        projectId,
                        sessionId,
                        first.plusSeconds(PREAMBLE_LEAD_SECONDS / 2),
                        "what plan am i on?",
                        "You are on the standard plan."));
    }

    // Neutral, so the preamble fires no detector and inflates no count.
    private static SpanRef exchange(
            SubstrateV2Fixtures fx, String projectId, String sessionId, Instant at, String user, String assistant) {
        return fx.spanSeed(projectId)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(at)
                .payload(ClassifierObservations.userInput(user), ClassifierObservations.assistantOutput(assistant))
                .writeRef();
    }
}
