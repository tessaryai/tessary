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
 * Fields are attached as real key-value pairs, not interpolated into the message, so the message stays scannable and
 * Loki can filter and graph the fields.
 */
public final class StructuredLog {

    private StructuredLog() {}

    public static Builder info(Logger log, Marker marker, String event) {
        return new Builder(emitInfo(log), marker, event);
    }

    /** Use it for lifecycle chatter, which is noise at volume and implied by the outcome line. */
    public static Builder debug(Logger log, String event) {
        return new Builder(emitDebug(log), null, event);
    }

    public static Builder debug(Logger log, Marker marker, String event) {
        return new Builder(emitDebug(log), marker, event);
    }

    public static Builder warn(Logger log, Marker marker, String event) {
        return new Builder(emitWarn(log), marker, event);
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

    /** A disabled level returns a no-op builder, so an unread line costs nothing. */
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

        public Builder field(String key, @Nullable Object value) {
            if (value != null) {
                fields.put(key, value);
            }
            return this;
        }

        public Builder field(String key, @Nullable Object value, boolean condition) {
            if (condition && value != null) {
                fields.put(key, value);
            }
            return this;
        }

        public Builder durationMs(Instant start) {
            if (start != null) {
                long ms = Math.max(0, Duration.between(start, Instant.now()).toMillis());
                fields.put("durationMs", ms);
            }
            return this;
        }

        /**
         * Say the state, not the identifier. Repeating a value here and as a field is deliberate: the message serves
         * the person, the fields serve Loki. Omitted, the message falls back to the event name.
         */
        public Builder message(String template, Object... args) {
            this.message = args.length == 0 ? template : String.format(Locale.ROOT, template, args);
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        /**
         * Interpolating fields made lines unreadable and left Loki only regexes. {@code LogstashEncoder} and the OTel
         * appender ({@code captureKeyValuePairAttributes}) both carry key-value pairs; the dev console needs
         * {@code %kvp} in its pattern.
         */
        public void log() {
            LoggingEventBuilder eb = emitter.builder();
            if (marker != null) {
                eb = eb.addMarker(marker);
            }
            // The event name is a field, so dashboards that group by event keep working.
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
