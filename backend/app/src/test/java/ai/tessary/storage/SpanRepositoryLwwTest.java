// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The last-write-wins upsert (substrate-model.md §6.2), why a span's key is its natural key. A streaming span can
 * flush partial first and batches redeliver in any order: the completed version replaces the partial, never the
 * reverse.
 *
 * <p>The guard is {@code >=}, not {@code >}: second-granularity SDK clocks make equal {@code event_ts} common, and a
 * strict guard drops those finals. The SET list carries producer columns only, so a replay never discards resolved
 * ancestry and requeues it for the fixpoint.
 */
@SpringBootTest
class SpanRepositoryLwwTest {

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;
    private String pid;
    private String traceId;
    private String spanId;
    private Instant t0;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
        pid = TenantFixture.bootstrap(tenants, "span-lww").project().id();
        t0 = Instant.parse("2026-08-12T10:00:00Z");
        traceId = SubstrateV2Fixtures.traceId();
        spanId = SubstrateV2Fixtures.spanId();
        fx.trace(pid, traceId, t0);
    }

    @Test
    @DisplayName("a partial version redelivered after the final one changes nothing")
    void olderEventTsLoses() {
        write(version("chat", 42L, t0.plusSeconds(3)));
        write(version("streaming", null, t0));

        SpanRow read = spans.findById(pid, traceId, spanId).orElseThrow();
        assertEquals("chat", read.name(), "the guard rejects the write outright rather than half-applying it");
        assertEquals(42L, read.totalTokens(), "a replayed partial must not blank out real usage");
    }

    @Test
    @DisplayName("on an equal event_ts the latest arrival wins")
    void equalEventTsGoesToTheLatestArrival() {
        write(version("first", 1L, t0));
        write(version("second", 2L, t0));

        SpanRow read = spans.findById(pid, traceId, spanId).orElseThrow();
        assertEquals("second", read.name(), "second-granularity clocks make ties the common case, not the edge");
        assertEquals(2L, read.totalTokens());
    }

    @Test
    @DisplayName("a newer version replaces what the producer said, never what the platform derived")
    void setListExcludesPlatformDerivedColumns() {
        write(version("streaming", null, t0));
        jdbc.sql("UPDATE span SET path = :p::ltree, path_state = 'resolved', correlation_state = 'done'"
                        + " WHERE project_id = :pid AND trace_id = :tid AND id = :id")
                .param("p", spanId)
                .param("pid", pid)
                .param("tid", traceId)
                .param("id", spanId)
                .update();
        String createdAtBefore =
                spans.findById(pid, traceId, spanId).orElseThrow().createdAt();

        // The completed version arrives with no path (arrivals never carry one).
        write(version("chat", 42L, t0.plusSeconds(3)));

        SpanRow read = spans.findById(pid, traceId, spanId).orElseThrow();
        assertEquals("chat", read.name(), "the producer's columns did move");
        assertEquals(spanId, read.path(), "the resolved ancestry did not");
        assertEquals(0, read.depth(), "and neither did the column generated from it");
        assertEquals("resolved", read.pathState());
        assertEquals("done", read.correlationState());
        assertEquals(createdAtBefore, read.createdAt(), "created_at records when WE first saw the span");
    }

    @Test
    @DisplayName("parent_span_id is producer-sourced and does move with a newer version")
    void setListIncludesParentSpanId() {
        SpanRow parent = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", t0, null);
        write(version("streaming", null, t0));
        assertNull(spans.findById(pid, traceId, spanId).orElseThrow().parentSpanId());

        SpanRow reparented = withParent(version("chat", 42L, t0.plusSeconds(3)), parent.id());
        write(reparented);

        assertEquals(
                parent.id(),
                spans.findById(pid, traceId, spanId).orElseThrow().parentSpanId(),
                "the producer's statement about parentage is a producer column like any other");
    }

    @Test
    @DisplayName("the payload row is guarded by the same event_ts, so it can never lag its span")
    void payloadFollowsTheSameGuard() {
        write(version("streaming", null, t0));
        write(new SpanPayloadRow(pid, traceId, spanId, "prompt", null, null, null, t0.toString()));

        write(version("chat", 42L, t0.plusSeconds(3)));
        write(new SpanPayloadRow(
                pid,
                traceId,
                spanId,
                "prompt",
                "completion",
                null,
                null,
                t0.plusSeconds(3).toString()));
        write(new SpanPayloadRow(pid, traceId, spanId, "stale", null, null, null, t0.toString()));

        SpanPayloadRow read = payloads.find(pid, traceId, spanId).orElseThrow();
        assertEquals("completion", read.output());
        assertNotEquals(
                "stale",
                read.input(),
                "a replayed partial payload beside a final span row is exactly the disagreement this prevents");
    }

    private void write(SpanRow row) {
        spans.upsertAll(List.of(row));
    }

    private void write(SpanPayloadRow row) {
        payloads.upsertAll(List.of(row));
    }

    private SpanRow version(String name, @Nullable Long outputTokens, Instant eventTs) {
        return new SpanRow(
                pid,
                traceId,
                spanId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "llm",
                name,
                true,
                null,
                null,
                null,
                null,
                t0.toString(),
                outputTokens == null ? null : eventTs.toString(),
                null,
                null,
                null,
                null,
                null,
                outputTokens,
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
                SpanRow.ResolverState.PENDING,
                SpanRow.ResolverState.PENDING,
                eventTs.toString(),
                false,
                null,
                null,
                null,
                null);
    }

    private static SpanRow withParent(SpanRow row, String parentSpanId) {
        return new SpanRow(
                row.projectId(),
                row.traceId(),
                row.id(),
                parentSpanId,
                row.path(),
                row.sessionId(),
                row.userId(),
                row.projectVersionId(),
                row.callSiteId(),
                row.traceName(),
                row.kind(),
                row.name(),
                false,
                row.status(),
                row.level(),
                row.errorType(),
                row.errorMessage(),
                row.startedAt(),
                row.endedAt(),
                row.latencyMs(),
                row.ttftMs(),
                row.providedModelName(),
                row.modelId(),
                row.inputTokens(),
                row.outputTokens(),
                row.cacheReadTokens(),
                row.cacheWriteTokens(),
                row.reasoningTokens(),
                row.inputCost(),
                row.outputCost(),
                row.cacheReadCost(),
                row.cacheWriteCost(),
                row.costSource(),
                row.priceBookVersion(),
                row.inputPreview(),
                row.outputPreview(),
                row.correlationState(),
                row.pathState(),
                row.eventTs(),
                row.isDeleted(),
                null,
                null,
                null,
                null);
    }
}
