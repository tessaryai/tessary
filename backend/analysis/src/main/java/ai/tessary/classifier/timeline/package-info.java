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
 *   <li>A {@code user_message} is a message from the person: a new user message of a person-facing span of a trace
 *       that is not a detached sub-agent trace. A person-facing span is a top {@code agent} span, or an {@code llm}
 *       span that is not inside a nested agent, with only {@code llm}, {@code step}, {@code workflow} or {@code
 *       handoff} spans between it and its agent, or the root. A message an orchestrator sends to a sub-agent stays in
 *       the sub-agent's {@code llm} event, and so does the prompt of a model call that a tool, a guardrail, a
 *       retrieval or any other work span makes; an agent a tool runs is not the agent the person talks to. A message
 *       that a later span sends again is not a new event.
 *   <li>An {@code assistant_reply} is text the person sees, from the top agent of the turn. A message with only tool
 *       calls is not a reply, and neither is the text a sub-agent returns to its orchestrator. When an output holds the
 *       whole chat, only the assistant messages after its last user message are new. Two messages with the same text
 *       are two replies when the loop received the first before it wrote the second.
 * </ul>
 *
 * <p>The new messages of an input are those after its last assistant message that has text or a tool call ({@link
 * ai.tessary.classifier.timeline.DialogueMessages}), the rule the frustration thread uses too. An {@code agent} span's
 * input and output give messages only when they are a message list or text; a framework's call arguments or return
 * object give none, and the {@code llm} spans inside give the messages.
 *
 * <p>A message needs the span's place in the tree. In a trace that is not settled, a span whose parent links do not
 * reach a root is not placed yet (the root span of a turn ends, and arrives, last), so it carries no message until its
 * ancestors arrive. In a settled trace, a span whose parent is not stored is a root of its own. A root that arrives
 * after its trace settled can still change the trace's messages.
 *
 * <h2>Ids</h2>
 *
 * <p>An event id is unique in the session. A span id is unique only inside its trace, so a span event's id is {@code
 * <trace_id>/<span_id>}. A message event's id is {@code <trace_id>/<span_id>#user:<n>} or {@code
 * <trace_id>/<span_id>#reply:<n>}, {@code n} counted from 0 among the user messages or reply texts of the span that
 * first carries the message. The {@code span_id}, {@code parent_id} and {@code agent_id} fields stay bare span ids of
 * the event's trace. The {@code turn} of an event is its trace id.
 *
 * <h2>Order</h2>
 *
 * <p>Events are ordered by end time, because an event can be judged only when it is complete. On a tie the event of
 * the trace that ends first comes first, so a turn's events come before the next turn's that starts in the instant it
 * ends; inside one trace the deeper event comes first, so a parent never comes before its child. A user message is
 * placed at the start of the first span that carries it; a reply comes right after the span that wrote it.
 */
package ai.tessary.classifier.timeline;
