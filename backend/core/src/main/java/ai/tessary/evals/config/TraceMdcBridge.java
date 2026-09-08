// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import ai.tessary.evals.open.obs.LogContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bridges the calling thread's current Micrometer/OTel span (if any) into the SLF4J MDC, under
 * the same {@code traceId}/{@code spanId} keys {@code logback-spring.xml} already ships for HTTP
 * request logs (Micrometer Tracing populates those via the servlet filter's request scope).
 *
 * <p>Spring's built-in {@code @Scheduled} observability wraps every tick in an Observation —
 * visible in Tempo as e.g. {@code task signalWorker.tick} — but unlike an HTTP request thread,
 * that scope never reaches MDC on the scheduler thread, so background-worker logs carry no
 * trace_id today. Each {@code @Scheduled} entry point opens a scope with {@link #bindCurrentTrace()}
 * before doing any work, so downstream logs — and anything dispatched to an executor decorated
 * with {@code MdcTaskDecorator} — pick up the tick's trace/span id and pivot to it in Grafana.
 */
@Component
public class TraceMdcBridge {

    private final Tracer tracer;

    public TraceMdcBridge(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Open an MDC scope bound to the calling thread's current span. No-op when there is none. */
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
