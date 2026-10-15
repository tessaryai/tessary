// SPDX-License-Identifier: Apache-2.0
/**
 * The session timeline: one ordered event list per conversation, keyed by {@code session_id}, never {@code thread_id}.
 * Each complete span is an event; {@code user_message} events come from person-facing spans (a top agent, or an
 * {@code llm} span not inside a nested agent or work span), and {@code assistant_reply} events are text the top agent
 * shows the person. A message resent by a later span is not a new event. Ids are {@code <trace_id>/<span_id>} with
 * {@code #user:<n>} or {@code #reply:<n>} for messages, since span ids are unique only per trace. Events are ordered by
 * end time; on a tie the trace that ends first, then the deeper event, so a parent never precedes its child.
 */
package ai.tessary.classifier.timeline;
