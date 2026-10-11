// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import java.util.List;

/**
 * The ordered events of one session.
 *
 * @param truncated older events of the session are not in {@code events}
 */
public record SessionTimeline(String projectId, String sessionId, List<Event> events, boolean truncated) {

    public SessionTimeline {
        events = List.copyOf(events);
    }

    /** The summed size of the events. */
    public long bytes() {
        return events.stream().mapToLong(Event::bytes).sum();
    }
}
