// SPDX-License-Identifier: Apache-2.0
/**
 * The session timeline: one ordered list of events per conversation, built from the substrate. The conversation key
 * is {@code session_id}, never {@code thread_id}.
 *
 * <h2>Events</h2>
 *
 * <p>Each complete span (one with an end) is one event of its canonical kind; {@code mcp} is a {@code tool} event with
 * {@code data.mcp = true}. The messages inside the spans are events too:
 *
 * <ul>
 *   <li>A {@code user_message} is a message from the person: a new user message of a person-facing span (a top
 *       {@code agent} span, or an {@code llm} span that is not inside a nested agent) of a trace that is not a
 *       detached sub-agent trace. A message an orchestrator sends to a sub-agent stays in the sub-agent's {@code llm}
 *       event. A message that a later span sends again is not a new event.
 *   <li>An {@code assistant_reply} is text the person sees, from the top agent of the turn. A message with only tool
 *       calls is not a reply, and neither is the text a sub-agent returns to its orchestrator.
 * </ul>
 *
 * <p>The new messages of an input are those after its last assistant message that has text or a tool call ({@link
 * ai.tessary.classifier.timeline.DialogueMessages}), the rule the frustration thread uses too.
 *
 * <h2>Ids</h2>
 *
 * <p>A span event's id is its span id. A message event's id is {@code <span_id>#user:<n>} or {@code
 * <span_id>#reply:<n>}, {@code n} counted from 0 among the user messages or reply texts of the span that first carries
 * the message. The {@code turn} of an event is its trace id.
 *
 * <h2>Order</h2>
 *
 * <p>Events are ordered by end time, because an event can be judged only when it is complete. On a tie the deeper event
 * comes first, so a parent never comes before its child. A user message is placed at the start of the first span that
 * carries it; a reply comes right after the span that wrote it.
 */
package ai.tessary.classifier.timeline;
