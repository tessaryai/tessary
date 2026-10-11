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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** {@link SpanPayloadRepository#listByTraceIds}: the payloads of a set of traces, every column, in one read. */
@SpringBootTest
class SpanPayloadRepositoryTraceIdsTest {

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

    @Test
    void readsEveryPayloadOfTheNamedTraces() {
        var fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
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
