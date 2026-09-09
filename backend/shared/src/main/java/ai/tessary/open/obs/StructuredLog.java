// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Fluent builder for structured, event-first log lines.
 *
 * <p>Every line is a dot-separated event name ({@code domain.action.phase}) as the message, plus
 * stable, short-keyed fields attached as <b>real key-value pairs</b> — not interpolated into the
 * message text. That split is deliberate: the message stays short enough to scan in a tmux pane,
 * while Loki gets fields it can actually filter and graph on ({@code durationMs}, {@code signal},
 * …). See {@link Builder#log()} for why the earlier message-interpolating form was the worst of
 * both worlds.
 *
 * <p>Examples:
 *
 * <pre>{@code
 * StructuredLog.info(log, Markers.OPS, "signal.sweep.complete")
 *     .field("job", job.id())
 *     .field("scanned", obs.size())
 *     .field("fired", fired)
 *     .durationMs(start)
 *     .log();
 *
 * StructuredLog.warn(log, Markers.OPS, "grader-run.job.failed")
 *     .field("job", job.id())
 *     .cause(e)
 *     .log();
 * }</pre>
 */
public final class StructuredLog {

    private StructuredLog() {}

    public static Builder info(Logger log, String event) {
        return new Builder(emitInfo(log), null, event);
    }

    public static Builder info(Logger log, Marker marker, String event) {
        return new Builder(emitInfo(log), marker, event);
    }

    /**
     * Developer detail, off in production. Use it for lifecycle chatter — "a sweep started", "nothing
     * to do" — which is steady-state noise at volume and is already implied by the outcome line.
     */
    public static Builder debug(Logger log, String event) {
        return new Builder(emitDebug(log), null, event);
    }

    public static Builder warn(Logger log, String event) {
        return new Builder(emitWarn(log), null, event);
    }

    public static Builder warn(Logger log, Marker marker, String event) {
        return new Builder(emitWarn(log), marker, event);
    }

    public static Builder error(Logger log, String event) {
        return new Builder(emitError(log), null, event);
    }

    public static Builder error(Logger log, Marker marker, String event) {
        return new Builder(emitError(log), marker, event);
    }

    private static LogEmitter emitInfo(Logger log) {
        return log::atInfo;
    }

    private static LogEmitter emitDebug(Logger log) {
        return log::atDebug;
    }

    private static LogEmitter emitWarn(Logger log) {
        return log::atWarn;
    }

    private static LogEmitter emitError(Logger log) {
        return log::atError;
    }

    /**
     * Supplies a fresh SLF4J fluent builder per emission. Returns a no-op builder when the level is
     * disabled, so building a line nobody will read costs nothing.
     */
    @FunctionalInterface
    private interface LogEmitter {
        LoggingEventBuilder builder();
    }

    public static final class Builder {
        private final LogEmitter emitter;
        private final @Nullable Marker marker;
        private final String event;
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private @Nullable Throwable cause;
        private @Nullable String message;

        private Builder(LogEmitter emitter, @Nullable Marker marker, String event) {
            this.emitter = emitter;
            this.marker = marker;
            this.event = event;
        }

        /** Add a field only if the value is non-null. */
        public Builder field(String key, @Nullable Object value) {
            if (value != null) {
                fields.put(key, value);
            }
            return this;
        }

        /** Add a field only when the condition is true and the value is non-null. */
        public Builder field(String key, @Nullable Object value, boolean condition) {
            if (condition && value != null) {
                fields.put(key, value);
            }
            return this;
        }

        /** Add a {@code durationMs} field measured from the supplied instant to now. */
        public Builder durationMs(Instant start) {
            if (start != null) {
                long ms = Math.max(0, Duration.between(start, Instant.now()).toMillis());
                fields.put("durationMs", ms);
            }
            return this;
        }

        /**
         * The human-readable line: a sentence describing what actually happened, in the words someone
         * scanning a terminal or a Loki tail would want.
         *
         * <p><b>Say the state, not the identifier.</b> {@code "swept 120 observations for groundedness
         * in 41ms, 3 fired"} beats {@code "signal.sweep.complete"}. The event name still travels — as
         * an {@code event} field — so grouping and filtering are unaffected; it simply stops being the
         * thing a human has to read.
         *
         * <p>Repeating a value here AND as a field is deliberate, not duplication to be optimised away.
         * The message serves the person; the fields serve Loki. Optional: omit it and the message
         * falls back to the event name, which is how the 87 pre-existing call sites still behave.
         */
        public Builder message(String template, Object... args) {
            this.message = args.length == 0 ? template : String.format(Locale.ROOT, template, args);
            return this;
        }

        /** Attach a throwable as the last SLF4J argument so it is rendered as the log's exception. */
        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        /**
         * Emit the line, with the fields attached as real key-value pairs rather than interpolated
         * into the message.
         *
         * <p>This distinction is the whole point of the class and it is easy to lose. The earlier
         * implementation appended {@code key={}} to the message for every field, so a line arrived
         * as one long string:
         *
         * <pre>{@code "signal.sweep.start job=01K… signal=groundedness project=01K… cursor=01K…"}</pre>
         *
         * That is unreadable to a human (four opaque 26-character ULIDs crowding out the meaning)
         * <em>and</em> opaque to Loki, which could only regex the message — you could not filter on
         * {@code signal="groundedness"} or graph {@code durationMs}. Worst of both.
         *
         * <p>SLF4J's fluent builder attaches them as structured data instead. Both shipping paths
         * already understand it: {@code LogstashEncoder} (7.3+) renders key-value pairs as JSON
         * fields, and the OTel appender is configured with {@code captureKeyValuePairAttributes},
         * so they become queryable attributes in Loki. The message stays short and scannable.
         *
         * <p>The dev console renders them via {@code %kvp} in the pattern — without that, local
         * output would lose the context entirely, since it no longer lives in the message.
         */
        public void log() {
            LoggingEventBuilder eb = emitter.builder();
            if (marker != null) {
                eb = eb.addMarker(marker);
            }
            // The event name is a FIELD, not the message. It is a stable identifier for grouping and
            // filtering; the message is prose for a human. Emitting it here keeps every existing
            // dashboard/alert that groups by event working while the message becomes readable.
            eb = eb.addKeyValue("event", event);
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                eb = eb.addKeyValue(e.getKey(), e.getValue());
            }
            if (cause != null) {
                eb = eb.setCause(cause);
            }
            eb.setMessage(message == null ? event : message).log();
        }
    }
}
