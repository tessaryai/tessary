// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * {@link OpsOrWarnLogFilter}, the gate in front of the production OTEL appender: WARN and above, or
 * anything marked {@link Markers#OPS}, reaches Loki; every other line stays local.
 */
class OpsOrWarnLogFilterTest {

    static Stream<Arguments> events() {
        Marker audit = MarkerFactory.getDetachedMarker("AUDIT");
        Marker opsChild = MarkerFactory.getDetachedMarker("JOB");
        opsChild.add(Markers.OPS);
        return Stream.of(
                Arguments.of(Level.ERROR, null, FilterReply.ACCEPT),
                Arguments.of(Level.WARN, null, FilterReply.ACCEPT),
                Arguments.of(Level.INFO, null, FilterReply.DENY),
                Arguments.of(Level.INFO, Markers.OPS, FilterReply.ACCEPT),
                Arguments.of(Level.DEBUG, Markers.OPS, FilterReply.ACCEPT),
                Arguments.of(Level.INFO, audit, FilterReply.DENY),
                Arguments.of(Level.INFO, opsChild, FilterReply.ACCEPT));
    }

    /**
     * The bugs: an OPS-marked INFO line (the operational signal) never reaches Loki, or unmarked INFO
     * floods it; a marker that carries OPS as a child counts as OPS.
     */
    @ParameterizedTest
    @MethodSource("events")
    void admitsWarnOrOpsMarkedAndDeniesTheRest(Level level, @Nullable Marker marker, FilterReply expected) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        if (marker != null) {
            event.addMarker(marker);
        }

        assertEquals(expected, new OpsOrWarnLogFilter().decide(event));
    }
}
