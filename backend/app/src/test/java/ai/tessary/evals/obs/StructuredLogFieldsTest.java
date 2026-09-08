// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.obs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.open.obs.StructuredLog;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Guards the contract that makes {@link StructuredLog} worth having: fields must arrive as real
 * key-value pairs and reach the JSON as their own fields — never interpolated into the message.
 *
 * <p>This test exists because the previous implementation looked structured and was not. It built
 * {@code "event key={} key={}"} into the message string, so Loki could only regex the message: you
 * could not filter on {@code signal="groundedness"} nor graph {@code durationMs}, and a human saw a
 * wall of opaque ULIDs. Nothing failed, no error was logged, and the two shipping paths were already
 * configured to accept key-value pairs — the data simply never took that route.
 *
 * <p>That is a silent-no-op class of bug the type system cannot catch, so it is asserted here
 * against the real {@link LogstashEncoder} rather than trusted.
 */
class StructuredLogFieldsTest {

    private record Captured(ILoggingEvent event, String json) {}

    /** Log through a real logger, capture the event, and render it with the production encoder. */
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
                () -> StructuredLog.info(LoggerFactory.getLogger(name), "signal.sweep.complete")
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
    void durationIsANumericFieldSoItCanBeGraphed() {
        String name = "test.structuredlog.duration";
        var captured = capture(
                () -> StructuredLog.info(LoggerFactory.getLogger(name), "redaction.apply")
                        .field("durationMs", 5500L)
                        .field("bytes", 44735)
                        .log(),
                name);

        // Quoted would make it a string in Loki — sortable/greppable but not graphable, which is
        // the entire reason for logging a duration.
        assertTrue(
                captured.json().contains("\"durationMs\":5500"),
                "durationMs must be an unquoted number, got: " + captured.json());
    }

    @Test
    void markersAndCausesStillSurvive() {
        String name = "test.structuredlog.cause";
        var captured = capture(
                () -> StructuredLog.warn(LoggerFactory.getLogger(name), "ingest.batch.failed")
                        .field("count", 3)
                        .cause(new IllegalStateException("boom"))
                        .log(),
                name);

        assertEquals("ingest.batch.failed", captured.event().getMessage());
        assertTrue(captured.json().contains("\"count\":3"));
        assertTrue(captured.json().contains("boom"), "the throwable must still be attached, got: " + captured.json());
    }
}
