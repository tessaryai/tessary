// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the sandbox agents' telemetry puts on a span, read back through a real OTel SDK. The two things
 * that break here are silent: a turn that arrives in fragments rendered as several half-spans (or its
 * fragments' tokens dropped), and a telemetry failure escaping into the sandbox call it decorates.
 */
class AgentSpanTelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant PARENT_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long RUN_START_MS = 1_000L;

    private final List<SpanData> exported = new ArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    exported.addAll(spans);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            }))
            .build();
    private final Tracer tracer = provider.get("test");

    @AfterEach
    void close() {
        provider.close();
    }

    /**
     * Fragments with a blank {@code input} continue the turn in progress: their text, tool calls, tool
     * results, tools and tokens fold into it and its end moves to their timestamp. A later offset that
     * runs backwards (microVM clock skew) or a turn with no timestamp is clamped, never placed before the
     * turn that preceded it.
     */
    @Test
    void recordTurns_foldsFragmentsIntoOneSpanPerTurnAndKeepsSpansMonotonic() throws Exception {
        JsonNode turns = JSON.readTree("""
                [
                  {"ts": 1100, "input": "fix the bug", "text": "Looking",
                   "usage": {"input_tokens": 10, "output_tokens": 2}, "tools": ["Read"]},
                  {"ts": 1200, "input": "", "model": "claude-sonnet-5", "text": "at it",
                   "tool_calls": [{"id": "tc1", "name": "Read", "input": "a.txt"}],
                   "usage": {"input_tokens": 5, "cache_read_input_tokens": 7}, "tools": ["Edit"]},
                  {"ts": 1300, "input": "", "tool_results": [{"id": "tc1", "content": "file body"}]},
                  "not a turn",
                  {"ts": 1500, "input": "file body", "model": "gpt-5",
                   "tool_calls": [{"name": "Bash", "input": "ls"}]},
                  {"ts": 1600, "input": "", "text": "done", "model": "other-model",
                   "usage": {"output_tokens": 3}},
                  {"ts": 1200, "input": "again", "tool_results": [{"content": "r"}]},
                  {"input": "late"}
                ]
                """);

        AgentSpanTelemetry.recordTurns(tracer, turns, RUN_START_MS, PARENT_START);

        assertEquals(4, exported.size(), "one span per logical turn, fragments folded in");

        SpanData first = exported.get(0);
        assertEquals("agent.llm_request", first.getName());
        assertEquals(SpanKind.CLIENT, first.getKind());
        assertEquals(millis(0), first.getStartEpochNanos());
        assertEquals(millis(300), first.getEndEpochNanos(), "the last fragment's ts ends the merged turn");
        assertEquals(
                attrs(
                        "gen_ai.request.model", "claude-sonnet-5",
                        "gen_ai.operation.name", "chat",
                        "gen_ai.provider.name", "anthropic",
                        "gen_ai.usage.input_tokens", 15L,
                        "gen_ai.usage.output_tokens", 2L,
                        "langfuse.observation.usage_details",
                                "{\"input\":15,\"output\":2,\"cache_read\":7,\"cache_creation\":0}",
                        "tessary.agent.tools", "[\"Read\",\"Edit\"]",
                        "gen_ai.input.messages",
                                "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"fix the bug\"}]}]"),
                withoutOutput(first));
        assertEquals(
                JSON.readTree("""
                        [{"role": "assistant", "parts": [
                            {"type": "text", "content": "Looking\\nat it"},
                            {"type": "tool_call", "id": "tc1", "name": "Read", "arguments": "a.txt"}]},
                         {"role": "tool", "parts": [
                            {"type": "tool_call_response", "id": "tc1", "response": "file body"}]}]
                        """), output(first), "a complete request, response and tool-result triple on one span");

        SpanData second = exported.get(1);
        assertEquals(millis(300), second.getStartEpochNanos(), "starts where the previous turn ended");
        assertEquals(millis(600), second.getEndEpochNanos());
        assertEquals(
                attrs(
                        "gen_ai.request.model", "gpt-5",
                        "gen_ai.operation.name", "chat",
                        "gen_ai.provider.name", "openai",
                        "gen_ai.usage.input_tokens", 0L,
                        "gen_ai.usage.output_tokens", 3L,
                        "langfuse.observation.usage_details",
                                "{\"input\":0,\"output\":3,\"cache_read\":0,\"cache_creation\":0}",
                        "gen_ai.input.messages",
                                "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"file body\"}]}]"),
                withoutOutput(second),
                "a continuation's model never overrides the turn's own");
        assertEquals(JSON.readTree("""
                        [{"role": "assistant", "parts": [
                            {"type": "text", "content": "done"},
                            {"type": "tool_call", "name": "Bash", "arguments": "ls"}]}]
                        """), output(second), "a tool call with no id carries no id part");

        SpanData skewed = exported.get(2);
        assertEquals(millis(600), skewed.getStartEpochNanos());
        assertEquals(millis(600), skewed.getEndEpochNanos(), "a backwards offset is clamped, never negative");
        assertEquals(JSON.readTree("""
                        [{"role": "tool", "parts": [{"type": "tool_call_response", "response": "r"}]}]
                        """), output(skewed), "a results-only turn renders the tool message alone");
        assertNull(
                skewed.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")),
                "no model, no provider guess");

        SpanData untimed = exported.get(3);
        assertEquals(millis(600), untimed.getEndEpochNanos(), "a turn with no ts sits at the previous offset");
        assertNull(output(untimed), "a turn with nothing said sets no output");
    }

    @Test
    void recordTurns_ignoresARunStartItCannotPlaceTurnsAgainst() throws Exception {
        JsonNode turns = JSON.readTree("[{\"ts\": 5000, \"input\": \"hi\", \"text\": \"hello\"}]");

        AgentSpanTelemetry.recordTurns(tracer, turns, 0L, PARENT_START);

        assertEquals(millis(0), exported.get(0).getEndEpochNanos(), "no run start, no offset to trust");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{}", "[]"})
    void recordTurns_writesNothingWithoutATurnArray(@Nullable String turnsJson) throws Exception {
        JsonNode turns = turnsJson == null ? null : JSON.readTree(turnsJson);

        AgentSpanTelemetry.recordTurns(tracer, turns, RUN_START_MS, PARENT_START);

        assertEquals(List.of(), exported);
    }

    @Test
    void recordTurns_neverLetsATracerFailureReachTheSandboxCall() throws Exception {
        Tracer broken = name -> {
            throw new IllegalStateException("exporter down");
        };
        JsonNode turns = JSON.readTree("[{\"ts\": 1100, \"input\": \"hi\"}]");

        assertDoesNotThrow(() -> AgentSpanTelemetry.recordTurns(broken, turns, RUN_START_MS, PARENT_START));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "{not json"})
    void recordUsage_stampsNothingFromAnUnreadableEnvelope(@Nullable String envelope) {
        Span span = tracer.spanBuilder("run").startSpan();

        assertDoesNotThrow(() -> AgentSpanTelemetry.recordUsage(span, JSON, envelope));
        span.end();

        assertTrue(exported.get(0).getAttributes().isEmpty(), "a bad envelope is skipped, not half-stamped");
    }

    @Test
    void recordSpanIo_neverLetsASpanFailureReachTheSandboxCall() {
        assertDoesNotThrow(() -> AgentSpanTelemetry.recordSpanIo(new ThrowingSpan(), "prompt", "result"));
    }

    private static long millis(long offset) {
        return TimeUnit.MILLISECONDS.toNanos(PARENT_START.plusMillis(offset).toEpochMilli());
    }

    private static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> withoutOutput(SpanData span) {
        Map<String, Object> m = new LinkedHashMap<>();
        span.getAttributes().forEach((k, v) -> m.put(k.getKey(), v));
        m.remove("gen_ai.output.messages");
        return m;
    }

    private static @Nullable JsonNode output(SpanData span) throws Exception {
        String out = span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages"));
        return out == null ? null : JSON.readTree(out);
    }

    /** A span whose exporter has failed: every attribute write throws. */
    private static final class ThrowingSpan implements Span {
        @Override
        public <T> Span setAttribute(AttributeKey<T> key, T value) {
            throw new IllegalStateException("span closed");
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
            return this;
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            return this;
        }

        @Override
        public Span updateName(String name) {
            return this;
        }

        @Override
        public void end() {}

        @Override
        public void end(long timestamp, TimeUnit unit) {}

        @Override
        public SpanContext getSpanContext() {
            return SpanContext.getInvalid();
        }

        @Override
        public boolean isRecording() {
            return true;
        }
    }
}
