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
 * {@link TraceV2Repository#listSessionTraces}: every trace of one session, the detached sub-agent traces too, latest
 * end first. A trace that is not settled has no end yet and comes first. The session is the conversation; {@code
 * thread_id} is only a column.
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
            Instant startedAt,
            @Nullable Instant settledEnd) {}

    @Test
    void listsEverySessionTraceLatestEndFirstWithItsFacts() {
        var fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        String pid =
                TenantFixture.bootstrap(tenants, "session-traces").project().id();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String t1 = SubstrateV2Fixtures.traceId();
        String t2 = SubstrateV2Fixtures.traceId();
        String t3 = SubstrateV2Fixtures.traceId();
        String t4 = SubstrateV2Fixtures.traceId();
        String t5 = SubstrateV2Fixtures.traceId();
        String t6 = SubstrateV2Fixtures.traceId();
        fx.trace(pid, t1, "sess-1", "thr-1", null, t0);
        fx.traceCallSite(pid, t1, "cs-1");
        fx.settle(pid, t1, t0.plusMillis(9_000));
        fx.trace(pid, t2, "sess-1", t0.plusMillis(1_000));
        fx.detachUnder(pid, t2, t1);
        fx.settle(pid, t2, t0.plusMillis(5_000));
        fx.trace(pid, t3, "sess-1", t0.plusMillis(2_000));
        jdbc.sql("UPDATE trace SET ended_at = :end::timestamptz WHERE project_id = :pid AND id = :id")
                .param("end", t0.plusMillis(2_500).toString())
                .param("pid", pid)
                .param("id", t3)
                .update();
        fx.trace(pid, t4, "sess-2", "thr-1", null, t0.plusMillis(3_000));
        fx.trace(pid, t5, "sess-1", t0.plusMillis(4_000));
        fx.settle(pid, t5, t0.plusMillis(10_000));
        jdbc.sql("UPDATE trace SET is_deleted = true WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", t5)
                .update();
        fx.trace(pid, t6, "sess-1", t0.plusMillis(3_000));
        fx.settle(pid, t6, t0.plusMillis(5_000));

        assertEquals(
                List.of(
                        new Seen(t3, null, null, t0.plusMillis(2_000), null),
                        new Seen(t1, null, "cs-1", t0, t0.plusMillis(9_000)),
                        new Seen(t6, null, null, t0.plusMillis(3_000), t0.plusMillis(5_000)),
                        new Seen(t2, t1, null, t0.plusMillis(1_000), t0.plusMillis(5_000))),
                seen(traces.listSessionTraces(pid, "sess-1")),
                "a start order puts t1 last, a thread-keyed read lists t4, a read that drops detached traces skips t2,"
                        + " a deleted trace lists t5, an unsettled trace placed by a stale end is not first");
    }

    @Test
    void anotherProjectsSessionWithTheSameIdIsNotRead() {
        var fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        String mine = TenantFixture.bootstrap(tenants, "session-traces-mine")
                .project()
                .id();
        String theirs = TenantFixture.bootstrap(tenants, "session-traces-theirs")
                .project()
                .id();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String shared = SubstrateV2Fixtures.traceId();
        fx.trace(mine, shared, "sess-1", t0);
        fx.trace(theirs, shared, "sess-1", t0.plusMillis(1_000));
        fx.trace(theirs, SubstrateV2Fixtures.traceId(), "sess-1", t0.plusMillis(2_000));

        assertEquals(
                List.of(new Seen(shared, null, null, t0, null)),
                seen(traces.listSessionTraces(mine, "sess-1")),
                "a read that drops the project filter lists the other project's traces of the same session id");
    }

    private static List<Seen> seen(List<TraceV2Repository.SessionTrace> rows) {
        return rows.stream()
                .map(r -> new Seen(
                        r.id(),
                        r.parentTraceId(),
                        r.callSiteId(),
                        Instant.parse(r.startedAt()),
                        r.settledEnd() == null ? null : Instant.parse(r.settledEnd())))
                .toList();
    }
}
