// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import java.time.Instant;
import java.util.List;

/**
 * Seeds the conversational PREAMBLE a frustration fixture needs to be scoreable at all.
 *
 * <p>The frustration classifier sends a user turn only when the four messages before it are user,
 * assistant, user, assistant, each with text: a conversation's opener and its second user turn are
 * never sent. A fixture that inserts one turn and expects a detection is therefore asserting against a
 * turn production would never score, and the failure reads as a cursor or grain bug rather than the
 * eligibility rule doing its job.
 *
 * <p>Call this before the turn under test, in the same session, so the fixture represents a real
 * conversation. The two preamble turns are stamped EARLIER than the turns they precede so they never
 * disturb a test that depends on a specific timestamp ordering or an identical-timestamp group.
 *
 * <p>A turn is a TRACE, so each preamble turn is one trace with one root {@code llm} span carrying one
 * user message and one assistant reply. They are written FIRST, which matters for more than tidiness:
 * the thread window orders by {@code (created_at, trace_id, id)}, and {@code created_at} is the row's
 * own insert time.
 */
public final class ClassifierConversations {

    private ClassifierConversations() {}

    /** How far before {@code beforeTs} the first preamble turn is stamped, clear of any same-timestamp group. */
    private static final int PREAMBLE_LEAD_SECONDS = 120;

    /**
     * Insert two benign exchanges into {@code sessionId} ahead of {@code beforeTs}, so the next turn in
     * that session has the user, assistant, user, assistant prefix it needs to be sent.
     *
     * @param beforeTs the timestamp of the turn under test; the preamble lands two and one minutes earlier
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

    // Deliberately neutral: the preamble must make the NEXT turn eligible without itself firing any
    // detector, or it would inflate whatever the test counts.
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
