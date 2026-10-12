// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TraceV2Repository#listUnendedSessionTraces} and {@link TraceV2Repository#listEndedSessionTraces}: every trace
 * of one session, the detached sub-agent traces too, latest end first. A trace with no settled end comes first; the
 * ended ones are read a page at a time, each page from its index without a sort. The session is the conversation;
 * {@code thread_id} is only a column.
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

    @Autowired
    TransactionTemplate transactions;

    private record Seen(
            String id,
            @Nullable String parentTraceId,
            @Nullable String callSiteId,
            Instant startedAt,
            boolean settled,
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
        String t7 = SubstrateV2Fixtures.traceId();
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
        fx.trace(pid, t7, "sess-1", t0.plusMillis(6_000));
        jdbc.sql("UPDATE trace SET is_settled = true WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", t7)
                .update();

        List<TraceV2Repository.SessionTrace> all = new ArrayList<>(traces.listUnendedSessionTraces(pid, "sess-1"));
        all.addAll(traces.listEndedSessionTraces(pid, "sess-1", null, 10));

        assertEquals(
                List.of(
                        new Seen(t7, null, null, t0.plusMillis(6_000), true, null),
                        new Seen(t3, null, null, t0.plusMillis(2_000), false, null),
                        new Seen(t1, null, "cs-1", t0, true, t0.plusMillis(9_000)),
                        new Seen(t6, null, null, t0.plusMillis(3_000), true, t0.plusMillis(5_000)),
                        new Seen(t2, t1, null, t0.plusMillis(1_000), true, t0.plusMillis(5_000))),
                seen(all),
                "a start order puts t1 last, a thread-keyed read lists t4, a read that drops detached traces skips t2,"
                        + " a deleted trace lists t5, an unsettled trace placed by a stale end is not first, a settled"
                        + " trace with no ended span reads as not settled");
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
                List.of(new Seen(shared, null, null, t0, false, null)),
                seen(traces.listUnendedSessionTraces(mine, "sess-1")),
                "a read that drops the project filter lists the other project's traces of the same session id");
    }

    /**
     * Catches a page that skips or repeats a trace: each page starts after the last trace of the one before, also
     * when two traces end in the same instant.
     */
    @Test
    void readsTheEndedTracesAPageAtATime() {
        var fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        String pid = TenantFixture.bootstrap(tenants, "session-traces-pages")
                .project()
                .id();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String a = SubstrateV2Fixtures.traceId();
        String b = SubstrateV2Fixtures.traceId();
        String c = SubstrateV2Fixtures.traceId();
        fx.trace(pid, a, "sess-1", t0);
        fx.settle(pid, a, t0.plusMillis(9_000));
        fx.trace(pid, b, "sess-1", t0.plusMillis(3_000));
        fx.settle(pid, b, t0.plusMillis(5_000));
        fx.trace(pid, c, "sess-1", t0.plusMillis(1_000));
        fx.settle(pid, c, t0.plusMillis(5_000));

        List<String> pages = new ArrayList<>();
        TraceV2Repository.SessionTrace after = null;
        for (List<TraceV2Repository.SessionTrace> page = traces.listEndedSessionTraces(pid, "sess-1", null, 1);
                !page.isEmpty();
                page = traces.listEndedSessionTraces(pid, "sess-1", after, 1)) {
            after = page.get(page.size() - 1);
            pages.add(after.id());
        }

        assertEquals(List.of(a, b, c), pages, "b and c end in one instant, so the later start comes first");
    }

    /**
     * Catches a read that sorts the whole session: each read walks its index in the read's order, and a page after a
     * trace starts in the index at that trace, so its cost is its own rows and not the session's.
     */
    @Test
    void eachReadWalksItsIndexWithoutASort() {
        List<String> ended = plan("EXPLAIN "
                + TraceV2Repository.ENDED_SESSION_TRACES_AFTER
                        .replace(":pid", "'p'")
                        .replace(":sid", "'s'")
                        .replace(":end", "'2026-01-05T10:00:00Z'")
                        .replace(":start", "'2026-01-05T09:00:00Z'")
                        .replace(":id", "'t'")
                        .replace(":limit", "20"));
        List<String> unended = plan("EXPLAIN "
                + TraceV2Repository.UNENDED_SESSION_TRACES
                        .replace(":pid", "'p'")
                        .replace(":sid", "'s'"));

        assertEquals(
                List.of(true, false, true),
                List.of(
                        ended.stream().anyMatch(l -> l.contains("Index Scan using ix_trace_session_ended")),
                        ended.stream().anyMatch(l -> l.contains("Sort")),
                        ended.stream().anyMatch(l -> l.contains("Index Cond") && l.contains("ROW(ended_at"))),
                String.join("\n", ended));
        assertEquals(
                List.of(true, false),
                List.of(
                        unended.stream().anyMatch(l -> l.contains("Index Scan using ix_trace_session_unended")),
                        unended.stream().anyMatch(l -> l.contains("Sort"))),
                String.join("\n", unended));
    }

    /** The plan of a statement, with sequential and bitmap scans off so the planner shows the index it can use. */
    private List<String> plan(String explain) {
        return transactions.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
            return jdbc.sql(explain).query(String.class).list();
        });
    }

    private static List<Seen> seen(List<TraceV2Repository.SessionTrace> rows) {
        return rows.stream()
                .map(r -> new Seen(
                        r.id(),
                        r.parentTraceId(),
                        r.callSiteId(),
                        Instant.parse(r.startedAt()),
                        r.settled(),
                        r.settledEnd() == null ? null : Instant.parse(r.settledEnd())))
                .toList();
    }
}
