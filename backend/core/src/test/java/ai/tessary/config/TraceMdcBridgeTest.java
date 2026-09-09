// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import ai.tessary.open.obs.LogContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class TraceMdcBridgeTest {

    @Mock
    Tracer tracer;

    @Mock
    Span span;

    @Mock
    TraceContext traceContext;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void bindsTraceAndSpanIdFromTheCurrentSpan() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(false);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("trace-abc");
        when(traceContext.spanId()).thenReturn("span-123");

        try (LogContext ignored = new TraceMdcBridge(tracer).bindCurrentTrace()) {
            assertEquals("trace-abc", MDC.get(LogContext.TRACE_ID));
            assertEquals("span-123", MDC.get(LogContext.SPAN_ID));
        }

        assertNull(MDC.get(LogContext.TRACE_ID), "closing the scope restores the prior (absent) MDC state");
        assertNull(MDC.get(LogContext.SPAN_ID));
    }

    @Test
    void isANoopWhenThereIsNoCurrentSpan() {
        when(tracer.currentSpan()).thenReturn(null);

        try (LogContext ignored = new TraceMdcBridge(tracer).bindCurrentTrace()) {
            assertNull(MDC.get(LogContext.TRACE_ID));
            assertNull(MDC.get(LogContext.SPAN_ID));
        }
    }

    @Test
    void isANoopForTheNoopSpan() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(true);

        try (LogContext ignored = new TraceMdcBridge(tracer).bindCurrentTrace()) {
            assertNull(MDC.get(LogContext.TRACE_ID));
            assertNull(MDC.get(LogContext.SPAN_ID));
        }
    }

    @Test
    void restoresAPreviouslyBoundOuterTraceOnClose() {
        MDC.put(LogContext.TRACE_ID, "outer-trace");
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(false);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("inner-trace");
        when(traceContext.spanId()).thenReturn("inner-span");

        try (LogContext ignored = new TraceMdcBridge(tracer).bindCurrentTrace()) {
            assertEquals("inner-trace", MDC.get(LogContext.TRACE_ID));
        }

        assertEquals("outer-trace", MDC.get(LogContext.TRACE_ID), "nested scopes restore, not clear");
    }
}
