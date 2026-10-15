// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import ai.tessary.open.obs.LogContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Binds the current Micrometer/OTel span into the MDC under {@code traceId}/{@code spanId}. A {@code @Scheduled} tick's
 * observation never reaches the MDC on the scheduler thread, so each entry point opens {@link #bindCurrentTrace()} to
 * give background logs a trace id.
 */
@Component
public class TraceMdcBridge {

    private final Tracer tracer;

    public TraceMdcBridge(Tracer tracer) {
        this.tracer = tracer;
    }

    /** No-op when there is no current span. */
    public LogContext bindCurrentTrace() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) {
            return LogContext.put(Map.of());
        }
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(LogContext.TRACE_ID, span.context().traceId());
        kv.put(LogContext.SPAN_ID, span.context().spanId());
        return LogContext.put(kv);
    }
}
