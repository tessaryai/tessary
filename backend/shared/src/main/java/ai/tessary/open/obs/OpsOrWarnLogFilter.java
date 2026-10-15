// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import org.slf4j.Marker;

/**
 * Admits WARN+ or {@link Markers#OPS} events, so operational INFO reaches Loki from the OTEL appender while bulk INFO
 * stays local. Logback's {@code MarkerFilter} is a turbo filter, not an appender filter, and {@code EvaluatorFilter}
 * needs Janino.
 */
public final class OpsOrWarnLogFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return FilterReply.ACCEPT;
        }
        List<Marker> markers = event.getMarkerList();
        if (markers != null) {
            for (Marker marker : markers) {
                if (marker != null && marker.contains(Markers.OPS.getName())) {
                    return FilterReply.ACCEPT;
                }
            }
        }
        return FilterReply.DENY;
    }
}
