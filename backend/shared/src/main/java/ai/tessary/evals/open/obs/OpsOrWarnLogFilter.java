// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.obs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import org.slf4j.Marker;

/**
 * Appender-level filter that admits an event if it is WARN+ <em>or</em> carries
 * the {@link Markers#OPS} marker, and denies everything else. Attached to the
 * OTEL appender (production profile) so safe operational INFO reaches Loki while
 * the bulk of INFO stays local.
 *
 * <p>Logback's built-in {@code MarkerFilter} is a global {@code TurboFilter},
 * not an appender filter, and {@code EvaluatorFilter} needs Janino — hence this
 * small explicit filter. Joran instantiates it reflectively from XML, which the
 * JVM resolves through the normal class loader.
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
