// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.open.obs.Markers;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Repository.SessionTrace;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class SessionTimelineServiceTest {

    private static final String PID = "p1";
    private static final String SID = "sess-1";
    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");

    @Mock
    TraceV2Repository traces;

    @Mock
    SpanRepository spans;

    @Mock
    SpanPayloadRepository payloads;

    @Mock
    ToolCallRepository toolCalls;

    @Test
    void loadTailReadsNoTraceItThenLeavesOut() {
        when(traces.listSessionTraces(PID, SID))
                .thenReturn(List.of(settled("t3", 3000), settled("t2", 2000), settled("t1", 1000)));
        when(spans.listByTraceIds(eq(PID), anyCollection(), eq(Integer.MAX_VALUE)))
                .thenAnswer(call -> {
                    Collection<String> ids = call.getArgument(1);
                    return ids.stream()
                            .map(SessionTimelineServiceTest::toolSpan)
                            .toList();
                });
        when(payloads.listByTraceIds(eq(PID), anyCollection())).thenReturn(List.of());
        when(toolCalls.listByTraceIds(eq(PID), anyCollection())).thenReturn(List.of());
        SessionTimelineService service = new SessionTimelineService(traces, spans, payloads, toolCalls);

        SessionTimeline tail = service.loadTail(PID, SID, 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> read = ArgumentCaptor.forClass(Collection.class);
        verify(spans).listByTraceIds(eq(PID), read.capture(), eq(Integer.MAX_VALUE));
        assertEquals(
                List.of(List.of("t3")),
                read.getAllValues().stream().map(List::copyOf).toList(),
                "a tail that one trace fills reads the spans and payloads of a whole page of traces");
        assertEquals(List.of(), tail.events(), "no event of 1 byte or less exists");
        assertEquals(true, tail.truncated());
    }

    @Test
    void theLoadLineOfATailCountsTheEventsTheTailReturns() {
        when(traces.listSessionTraces(PID, SID)).thenReturn(List.of(settled("t2", 2000), settled("t1", 1000)));
        when(spans.listByTraceIds(eq(PID), anyCollection(), eq(Integer.MAX_VALUE)))
                .thenAnswer(call -> {
                    Collection<String> ids = call.getArgument(1);
                    return ids.stream()
                            .map(SessionTimelineServiceTest::toolSpan)
                            .toList();
                });
        when(payloads.listByTraceIds(eq(PID), anyCollection())).thenReturn(List.of());
        when(toolCalls.listByTraceIds(eq(PID), anyCollection())).thenReturn(List.of());
        SessionTimelineService service = new SessionTimelineService(traces, spans, payloads, toolCalls);
        long newestEvent = service.load(PID, SID).events().get(1).bytes();
        Logger logger = (Logger) LoggerFactory.getLogger(SessionTimelineService.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        SessionTimeline tail;
        try {
            tail = service.loadTail(PID, SID, newestEvent);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }

        assertEquals(List.of("t2/s-t2"), tail.events().stream().map(Event::id).toList());
        assertEquals(true, tail.truncated());
        ILoggingEvent line = appender.list.get(0);
        assertEquals(List.of(Markers.OPS), line.getMarkerList());
        Map<String, Object> fields = line.getKeyValuePairs().stream()
                .filter(kv -> !"durationMs".equals(kv.key))
                .collect(Collectors.toMap(kv -> kv.key, kv -> kv.value));
        assertEquals(
                Map.of(
                        "event",
                        "timeline.load",
                        "traces",
                        2,
                        "spans",
                        2,
                        "events",
                        1,
                        "bytes",
                        newestEvent,
                        "truncated",
                        true),
                fields,
                "the line counts the timeline read before the tail cut it, so it reports 2 events and not"
                        + " truncated");
    }

    @Test
    void aSettledTraceWithNoEndedSpanStillPlacesAnOrphanSpansMessage() {
        when(traces.listSessionTraces(PID, SID))
                .thenReturn(List.of(new SessionTrace("t1", null, null, T0.toString(), true, null)));
        when(spans.listByTraceIds(eq(PID), anyCollection(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(spanRow("t1", "l1", "missing-parent", "llm", T0.toString(), null)));
        when(payloads.listByTraceIds(eq(PID), anyCollection()))
                .thenReturn(List.of(new SpanPayloadRow(
                        PID,
                        "t1",
                        "l1",
                        "[{\"role\":\"user\",\"content\":\"hello\"}]",
                        null,
                        null,
                        null,
                        T0.toString(),
                        null)));
        when(toolCalls.listByTraceIds(eq(PID), anyCollection())).thenReturn(List.of());
        SessionTimelineService service = new SessionTimelineService(traces, spans, payloads, toolCalls);

        SessionTimeline timeline = service.load(PID, SID);

        assertEquals(
                List.of("t1/l1#user:0"),
                timeline.events().stream().map(Event::id).toList(),
                "a settled trace none of whose spans ended is read as a trace still receiving spans, so the span whose"
                        + " parent never arrived carries no message");
    }

    private static SessionTrace settled(String id, long endMs) {
        return new SessionTrace(
                id, null, null, T0.toString(), true, T0.plusMillis(endMs).toString());
    }

    /** One complete tool span per trace, ending at the trace's end. */
    private static SpanRow toolSpan(String traceId) {
        long endMs = Long.parseLong(traceId.substring(1)) * 1000;
        return spanRow(
                traceId,
                "s-" + traceId,
                null,
                "tool",
                T0.plusMillis(endMs - 10).toString(),
                T0.plusMillis(endMs).toString());
    }

    private static SpanRow spanRow(
            String traceId,
            String spanId,
            @Nullable String parentSpanId,
            String kind,
            String startedAt,
            @Nullable String endedAt) {
        return new SpanRow(
                PID,
                traceId,
                spanId,
                parentSpanId,
                null,
                SID,
                null,
                null,
                null,
                null,
                kind,
                "lookup",
                true,
                null,
                null,
                null,
                null,
                startedAt,
                endedAt,
                10L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SpanRow.CostSource.UNPRICED,
                null,
                null,
                null,
                SpanRow.ResolverState.DONE,
                SpanRow.ResolverState.DONE,
                T0.toString(),
                false,
                null,
                null,
                null,
                null);
    }
}
