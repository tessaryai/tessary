// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** {@link SpanPayloadRepository#listByTraceIds}: the payloads of a set of traces, every column, in one read. */
@SpringBootTest
record SpanPayloadRepositoryTraceIdsTest(
        SubstrateV2Fixtures fx, TenantService tenants, TraceV2Repository traces, SpanPayloadRepository payloads) {

    @Test
    void readsEveryPayloadOfTheNamedTraces() {

        String pid =
                TenantFixture.bootstrap(tenants, "payloads-by-traces").project().id();
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        SpanPayloadRow one = fullPayload(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), at), "one", at);
        SpanPayloadRow two = fullPayload(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), at), "two", at);
        fullPayload(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), at), "three", at);

        List<SpanPayloadRow> read = payloads.listByTraceIds(pid, List.of(one.traceId(), two.traceId()));

        assertEquals(
                Set.of(one, two),
                Set.copyOf(read),
                "a column the read drops comes back null, and a payload of another trace must not come back");
        assertEquals(2, read.size());
    }

    @Test
    void anotherProjectsPayloadUnderTheSameTraceIdIsNotRead() {

        String mine =
                TenantFixture.bootstrap(tenants, "payloads-mine").project().id();
        String theirs =
                TenantFixture.bootstrap(tenants, "payloads-theirs").project().id();
        Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String traceId = SubstrateV2Fixtures.traceId();
        SpanPayloadRow own = fullPayload(fx.llmSpan(mine, traceId, at), "mine", at);
        fullPayload(fx.llmSpan(theirs, traceId, at), "theirs", at);

        assertEquals(
                List.of(own),
                payloads.listByTraceIds(mine, List.of(traceId)),
                "a read that drops the project filter returns the other project's payload of the same trace id");
    }

    private SpanPayloadRow fullPayload(SpanRow span, String word, Instant at) {
        SpanPayloadRow row = new SpanPayloadRow(
                span.projectId(),
                span.traceId(),
                span.id(),
                "[{\"role\":\"user\",\"content\":\"" + word + "\"}]",
                "[{\"role\":\"assistant\",\"content\":\"" + word + " back\"}]",
                "{\"gen_ai.request.temperature\": 0.2}",
                "{\"input_tokens\": 5}",
                at.plusMillis(250).toString(),
                "[{\"kind\": \"api_key\", \"count\": 1}]");
        payloads.upsertAll(List.of(row));
        return row;
    }
}
