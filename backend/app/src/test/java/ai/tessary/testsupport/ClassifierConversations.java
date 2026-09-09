// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import java.time.Instant;

/**
 * Seeds the conversational PREAMBLE a frustration fixture needs to be scoreable at all.
 *
 * <p>The frustration built-in skips a conversation's opener ({@code context_min_prior_user_turns=1}):
 * the agent has not acted yet, so whatever emotion the first message carries is what the user arrived
 * with, not something the product caused (measured: 4% of turn-0 messages are frustrated against 30%
 * at turn 2). A fixture that inserts ONE turn and expects a detection is therefore asserting against a
 * turn production would never score — and the failure reads as a cursor or grain bug rather than the
 * gate doing its job, which is exactly how it presented when the gate landed.
 *
 * <p>Call this before the turn under test, in the same session, so the fixture represents a real
 * conversation. The preamble turn is stamped EARLIER than the turns it precedes so it never disturbs a
 * test that depends on a specific timestamp ordering or an identical-timestamp group.
 *
 * <p>A turn is a TRACE now, so the preamble is one trace with one root {@code llm} span — no context
 * spine, no {@code seq}. It is written FIRST, which matters for more than tidiness: the thread window
 * orders by {@code (created_at, trace_id, id)}, and {@code created_at} is the row's own insert time.
 */
public final class ClassifierConversations {

    private ClassifierConversations() {}

    /** How far before {@code beforeTs} the preamble turn is stamped — clear of any same-timestamp group. */
    private static final int PREAMBLE_LEAD_SECONDS = 60;

    /**
     * Insert one benign user turn into {@code sessionId} ahead of {@code beforeTs}, so the next turn in
     * that session has a prior exchange and is eligible for scoring.
     *
     * @param beforeTs the timestamp of the turn under test; the preamble lands a minute earlier
     * @return the preamble span's producer identity (rarely needed; returned for assertions that count
     *     turns)
     */
    public static SpanRef seedPriorTurn(SubstrateV2Fixtures fx, String projectId, String sessionId, String beforeTs) {
        Instant at = Instant.parse(beforeTs).minusSeconds(PREAMBLE_LEAD_SECONDS);
        return fx.spanSeed(projectId)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(at)
                .payload(
                        // Deliberately neutral: the preamble must make the NEXT turn eligible without
                        // itself firing any detector, or it would inflate whatever the test counts.
                        ClassifierObservations.userInput("hello, i have a question about my account"),
                        ClassifierObservations.assistantOutput("Happy to help — what would you like to know?"))
                .writeRef();
    }
}
