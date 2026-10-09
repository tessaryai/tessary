// SPDX-License-Identifier: Apache-2.0
package ai.tessary.obs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.stream.Collectors;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link StructuredLog} fields reach the JSON as their own key-value pairs, never interpolated into the message. The
 * previous implementation built {@code "event key={}"} into the message, so Loki could neither filter on {@code
 * signal} nor graph {@code durationMs}, and nothing failed. Asserted against the real {@link LogstashEncoder}.
 */
class StructuredLogFieldsTest {

    private record Captured(ILoggingEvent event, String json) {}

    /** Log through a real logger and render with the production encoder. */
    private static Captured capture(Runnable emit, String loggerName) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = ctx.getLogger(loggerName);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
        try {
            emit.run();
            assertEquals(1, appender.list.size(), "exactly one event should have been emitted");
            ILoggingEvent event = appender.list.get(0);
            LogstashEncoder encoder = new LogstashEncoder();
            encoder.setContext(ctx);
            encoder.start();
            try {
                return new Captured(event, new String(encoder.encode(event), UTF_8));
            } finally {
                encoder.stop();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void fieldsBecomeJsonFieldsAndTheMessageStaysJustTheEventName() {
        String name = "test.structuredlog.fields";
        var captured = capture(
                () -> StructuredLog.info(LoggerFactory.getLogger(name), Markers.OPS, "signal.sweep.complete")
                        .field("signal", "groundedness")
                        .field("scanned", 42)
                        .log(),
                name);

        assertEquals(
                "signal.sweep.complete",
                captured.event().getMessage(),
                "the message is the bare event name — fields must not be interpolated into it");

        String json = captured.json();
        assertTrue(json.contains("\"signal\":\"groundedness\""), "signal must be its own JSON field, got: " + json);
        assertTrue(json.contains("\"scanned\":42"), "numeric fields must stay numeric (graphable), got: " + json);
        assertFalse(
                json.contains("signal.sweep.complete signal="),
                "the old message-interpolating form is back — Loki cannot filter on those, got: " + json);
    }

    @Test
    void markersAndCausesStillSurvive() {
        String name = "test.structuredlog.cause";
        var captured = capture(
                () -> StructuredLog.warn(LoggerFactory.getLogger(name), Markers.OPS, "ingest.batch.failed")
                        .field("count", 3)
                        .cause(new IllegalStateException("boom"))
                        .log(),
                name);

        assertEquals("ingest.batch.failed", captured.event().getMessage());
        assertTrue(captured.json().contains("\"count\":3"));
        assertTrue(captured.json().contains("boom"), "the throwable must still be attached, got: " + captured.json());
    }

    /**
     * The conditional {@code field(key, value, condition)} must honour its condition; a null value is dropped either
     * way.
     */
    @Test
    void aConditionalFieldIsAttachedOnlyWhenItsConditionHoldsAndItHasAValue() {
        String name = "test.structuredlog.conditional";
        var captured = capture(
                () -> StructuredLog.warn(LoggerFactory.getLogger(name), Markers.OPS, "spend.reported")
                        .field("warnThresholdUsd", 50, true)
                        .field("quietThresholdUsd", 10, false)
                        .field("absent", null, true)
                        .log(),
                name);

        assertEquals(
                Map.of("event", "spend.reported", "warnThresholdUsd", 50),
                captured.event().getKeyValuePairs().stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value)));
    }
}
