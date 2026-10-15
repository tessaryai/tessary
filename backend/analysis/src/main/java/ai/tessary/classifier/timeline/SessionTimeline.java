// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import java.util.ArrayList;
import java.util.List;

/** @param truncated older events of the session are not in {@code events} */
public record SessionTimeline(String projectId, String sessionId, List<Event> events, boolean truncated) {

    public SessionTimeline {
        events = List.copyOf(events);
    }

    public long bytes() {
        return events.stream().mapToLong(Event::bytes).sum();
    }

    /**
     * The longest newest-first run within {@code maxBytes}, renumbered from 0. Truncated when this timeline was or an
     * event was cut.
     */
    public SessionTimeline tail(long maxBytes) {
        int from = events.size();
        long kept = 0;
        while (from > 0 && kept + events.get(from - 1).bytes() <= maxBytes) {
            kept += events.get(from - 1).bytes();
            from--;
        }
        List<Event> newest = new ArrayList<>(events.size() - from);
        for (int i = from; i < events.size(); i++) {
            newest.add(events.get(i).withPosition(newest.size()));
        }
        return new SessionTimeline(projectId, sessionId, newest, truncated || from > 0);
    }
}
