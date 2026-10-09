// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The content search's second shape and its timeout. A walk threshold of one sends every search with a content match
 * down the newest-first walk, which the default threshold reserves for common words; a short timeout makes a blocked
 * search fail inside the test.
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false",
            "tessary.traces.search.walk-threshold=1",
            "tessary.traces.search.timeout-ms=500"
        })
class TraceSearchShapesTest {

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
    DataSource dataSource;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void seedFixtures() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    /** The walk finds what the index finds: live spans only, newest first, any word order. */
    @Test
    void walkShapeListsTheTracesTheIndexShapeLists() {
        String pid = TenantFixture.bootstrap(tenants, "search-walk").project().id();
        Instant t0 = Instant.parse("2026-08-12T08:00:00Z");
        String older = SubstrateV2Fixtures.traceId();
        fx.payload(fx.llmSpan(pid, older, t0), "{\"content\":\"the refund was issued\"}", "ok", null);
        String newer = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.llmSpan(pid, newer, t0.plusSeconds(1));
        fx.payload(root, "hello", "hi", null);
        SpanRow child = fx.span(pid, newer, SubstrateV2Fixtures.spanId(), root.id(), "llm", t0, t0.plusSeconds(1));
        fx.payload(child, "issued a refund", "done", null);
        String deletedOnly = SubstrateV2Fixtures.traceId();
        SpanRow deleted = fx.llmSpan(pid, deletedOnly, t0.plusSeconds(2));
        fx.payload(deleted, "refund issued", "ok", null);
        jdbc.sql("UPDATE span SET is_deleted = true WHERE project_id = :pid AND trace_id = :tid")
                .param("pid", pid)
                .param("tid", deletedOnly)
                .update();
        fx.payload(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), t0.plusSeconds(3)), "hello", "hi", null);

        assertEquals(
                List.of(newer, older),
                traces.list(pid, query("refund issued"), null, 10, null, null, null).stream()
                        .map(TraceV2Repository.Summary::id)
                        .toList());
    }

    /**
     * A search that runs past the timeout fails with a clear error rather than holding a connection. A held lock on
     * {@code span_payload} makes the search wait, so the timeout fires however fast the machine is.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void searchPastTheTimeoutFailsAsTooBroad() throws Exception {
        String pid =
                TenantFixture.bootstrap(tenants, "search-timeout").project().id();
        fx.payload(
                fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), Instant.parse("2026-08-12T08:00:00Z")),
                "refund",
                "ok",
                null);

        try (Connection blocker = dataSource.getConnection();
                Statement lock = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            lock.execute("LOCK TABLE span_payload IN ACCESS EXCLUSIVE MODE");
            try {
                TessaryException e = assertThrows(
                        TessaryException.class, () -> traces.list(pid, query("refund"), null, 10, null, null, null));
                assertEquals(QueryError.SEARCH_TOO_BROAD, e.error());
            } finally {
                blocker.rollback();
            }
        }
    }

    private static TraceV2Repository.TraceQuery query(String q) {
        return new TraceV2Repository.TraceQuery(null, null, null, null, null, null, null, q, null);
    }
}
