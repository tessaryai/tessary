// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Repository.SessionTrace;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private static SessionTrace settled(String id, long endMs) {
        return new SessionTrace(
                id, null, null, T0.toString(), T0.plusMillis(endMs).toString());
    }

    /** One complete tool span per trace, ending at the trace's end. */
    private static SpanRow toolSpan(String traceId) {
        long endMs = Long.parseLong(traceId.substring(1)) * 1000;
        return new SpanRow(
                PID,
                traceId,
                "s-" + traceId,
                null,
                null,
                SID,
                null,
                null,
                null,
                null,
                "tool",
                "lookup",
                true,
                null,
                null,
                null,
                null,
                T0.plusMillis(endMs - 10).toString(),
                T0.plusMillis(endMs).toString(),
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
