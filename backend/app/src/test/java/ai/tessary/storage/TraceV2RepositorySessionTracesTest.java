// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link TraceV2Repository#listSessionTraces}: every trace of one session, the detached sub-agent traces too, newest
 * first, page by page. The session is the conversation; {@code thread_id} is only a column.
 */
@SpringBootTest
class TraceV2RepositorySessionTracesTest {

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

    private record Seen(
            String id,
            @Nullable String parentTraceId,
            @Nullable String callSiteId,
            Instant startedAt) {}

    @Test
    void listsEverySessionTraceNewestFirstWithItsFacts() {
        var fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        String pid =
                TenantFixture.bootstrap(tenants, "session-traces").project().id();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String t1 = SubstrateV2Fixtures.traceId();
        String t2 = SubstrateV2Fixtures.traceId();
        String t3 = SubstrateV2Fixtures.traceId();
        String t4 = SubstrateV2Fixtures.traceId();
        String t5 = SubstrateV2Fixtures.traceId();
        fx.trace(pid, t1, "sess-1", "thr-1", null, t0);
        fx.traceCallSite(pid, t1, "cs-1");
        fx.trace(pid, t2, "sess-1", t0.plusMillis(1_000));
        fx.detachUnder(pid, t2, t1);
        fx.trace(pid, t3, "sess-1", t0.plusMillis(2_000));
        fx.trace(pid, t4, "sess-2", "thr-1", null, t0.plusMillis(3_000));
        fx.trace(pid, t5, "sess-1", t0.plusMillis(4_000));
        jdbc.sql("UPDATE trace SET is_deleted = true WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", t5)
                .update();

        List<TraceV2Repository.SessionTrace> first = traces.listSessionTraces(pid, "sess-1", null, 2);
        List<TraceV2Repository.SessionTrace> second = traces.listSessionTraces(pid, "sess-1", first.get(1), 2);

        assertEquals(
                List.of(new Seen(t3, null, null, t0.plusMillis(2_000)), new Seen(t2, t1, null, t0.plusMillis(1_000))),
                seen(first),
                "a thread-keyed read lists t4, a read that drops detached traces skips t2, a deleted trace lists t5");
        assertEquals(
                List.of(new Seen(t1, null, "cs-1", t0)),
                seen(second),
                "the cursor after t2 must give exactly the older trace, with no repeat and no skip");
    }

    private static List<Seen> seen(List<TraceV2Repository.SessionTrace> rows) {
        return rows.stream()
                .map(r -> new Seen(r.id(), r.parentTraceId(), r.callSiteId(), Instant.parse(r.startedAt())))
                .toList();
    }
}
