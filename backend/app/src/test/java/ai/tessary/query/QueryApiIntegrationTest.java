// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.query.QueryDtos.CountRequest;
import ai.tessary.query.QueryDtos.FacetsRequest;
import ai.tessary.query.QueryDtos.SearchRequest;
import ai.tessary.query.QueryDtos.TimeRange;
import ai.tessary.query.QueryDtos.TimeseriesRequest;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Acceptance test for the aggregation-first query API: each of {@code count}, {@code timeseries},
 * {@code facets}, and {@code search} returns correct results against ingested substrate + signal data,
 * and every read funnels through the query API (controller → service → repository), never a raw table
 * from a caller above the repository. Exercised against the real pgvector Postgres (Testcontainers), so
 * the substrate indexes and the {@code date_trunc(... ::timestamptz)} bucketing run for real.
 */
@SpringBootTest
class QueryApiIntegrationTest {

    @Autowired
    QueryController controller;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    /** A project-scoped MCP-token context, exactly as the AuthFilter mints for an {@code tsy_}. */
    private static TenantContext token(String projectId) {
        return new TenantContext("user-1", null, "org-1", projectId, "member", "tok-1");
    }

    private SubstrateV2Fixtures fixtures() {
        return new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    /** Seed two spans (kind=llm "chat" / kind=tool "search"), at t0 and t0+2h. */
    private String seedProject(String name) {
        String pid = TenantFixture.bootstrap(tenants, name).project().id();
        Instant t0 = Instant.parse("2026-06-10T00:00:00Z");
        Instant t2 = t0.plus(2, ChronoUnit.HOURS);
        var fx = fixtures();
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.session(pid, sessionId, t0);

        var llm = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(t0)
                .payload("hi", "hello there")
                .previews("hi", "hello there")
                .writeRef();
        var tool = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .parentSpanId(llm.spanId())
                .kind("tool")
                .name("search")
                .at(t2)
                .payload("q", "result")
                .previews("q", "result")
                .writeRef();
        // The query API windows on created_at (arrival), which the repository will not let a payload set,
        // so the fixture writes it, exactly as the metering fixtures do.
        backdate(pid, llm, t0);
        backdate(pid, tool, t2);

        // Two tool_calls under the tool span: one success, one error, different tool names.
        fx.toolCall(pid, tool, "web_search", null, t0);
        fx.toolCall(pid, tool, "db_lookup", "timeout", t2);

        // One signal + two detections (different severities) over the spans. 'frustration' is off
        // by default, so the project's seeded catalog will not already own a 'frustration' row: the
        // DELETE below is a no-op in that case rather than replacing an existing row, and the raw
        // INSERT that follows creates this test's own row directly, bypassing capability gating
        // entirely (this test is about the query API, not about whether frustration is enabled).
        String classifierId = Ids.ulid();
        jdbc.sql("DELETE FROM classifier WHERE project_id = :pid AND classifier_key = 'frustration'")
                .param("pid", pid)
                .update();
        jdbc.sql("INSERT INTO classifier (id, project_id, classifier_key, name, detector, built_in, version, enabled, "
                        + "created_at, updated_at) VALUES (:id, :pid, 'frustration', 'Frustration', 'keyword', "
                        + "true, 1, true, :now, :now)")
                .param("id", classifierId)
                .param("pid", pid)
                .param("now", t0.toString())
                .update();
        insertSignalEvent(pid, classifierId, "frustration", sessionId, llm, "warn", t0);
        insertSignalEvent(pid, classifierId, "frustration", sessionId, tool, "critical", t2);

        return pid;
    }

    /** Back-date a span's arrival clock so the {@code created_at} windows in these tests are deterministic. */
    private void backdate(String pid, SpanRef span, Instant at) {
        jdbc.sql("UPDATE span SET created_at = :at::timestamptz"
                        + " WHERE project_id = :pid AND trace_id = :tid AND id = :sid")
                .param("at", at.toString())
                .param("pid", pid)
                .param("tid", span.traceId())
                .param("sid", span.spanId())
                .update();
    }

    /**
     * Seed a classifier <em>detection</em>: a detection lives in its own classifier's table, and the
     * query API's {@code classifier_events} dataset reads it back through the DetectionTableRegistry-stitched union
     * ({@code classifier_id} = the classifier key, which is the persisted wire token).
     *
     * <p>Seeded here into the span-grain table so the dataset's subject-pair filters have both halves to
     * work on: a producer span id is half a key and {@code subject_trace_id} is the other half.
     */
    private void insertSignalEvent(
            String pid,
            String classifierId,
            String classifierKey,
            String sessionId,
            SpanRef subject,
            String severity,
            Instant at) {
        jdbc.sql("INSERT INTO secret_leak_detection (id, project_id, classifier_id, classifier_key, "
                        + "subject_session_id, subject_trace_id, subject_span_id, severity, confidence, created_at) "
                        + "VALUES (:id, :pid, :sid, :key, :ctx, :trace, :subj, :sev, 'high', :at::timestamptz)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("sid", classifierId)
                .param("ctx", sessionId)
                .param("trace", subject.traceId())
                .param("subj", subject.spanId())
                .param("key", classifierKey)
                .param("sev", severity)
                .param("at", at.toString())
                .update();
    }

    // ---- count -------------------------------------------------------------------------------

    @Test
    void countReturnsMatchingRows() {
        String pid = seedProject("query-count");
        var ctx = token(pid);

        assertEquals(
                2,
                controller
                        .count(ctx, new CountRequest("spans", null, null))
                        .data()
                        .count());
        assertEquals(
                2,
                controller
                        .count(ctx, new CountRequest("tool_calls", null, null))
                        .data()
                        .count());
        assertEquals(
                2,
                controller
                        .count(ctx, new CountRequest("classifier_events", null, null))
                        .data()
                        .count());

        // Filter by an allow-listed discriminator.
        assertEquals(
                1,
                controller
                        .count(ctx, new CountRequest("spans", null, Map.of("kind", "tool")))
                        .data()
                        .count());
        // The v2 dimensions: cost_source is written on every span, so an unpriced fixture counts as such.
        assertEquals(
                2,
                controller
                        .count(ctx, new CountRequest("spans", null, Map.of("cost_source", "unpriced")))
                        .data()
                        .count());
        // Time range narrows to the first instant only (the t0 rows).
        long inWindow = controller
                .count(
                        ctx,
                        new CountRequest(
                                "tool_calls", new TimeRange("2026-06-10T00:00:00Z", "2026-06-10T01:00:00Z"), null))
                .data()
                .count();
        assertEquals(1, inWindow);
    }

    // ---- timeseries --------------------------------------------------------------------------

    @Test
    void timeseriesBucketsByInterval() {
        String pid = seedProject("query-timeseries");
        var ctx = token(pid);

        var buckets = controller
                .timeseries(
                        ctx,
                        new TimeseriesRequest(
                                "spans", "hour", new TimeRange("2026-06-09T00:00:00Z", "2026-06-11T00:00:00Z"), null))
                .data()
                .buckets();
        // Two spans 2h apart -> two distinct hourly buckets, each count 1, ascending.
        assertEquals(2, buckets.size());
        assertEquals(1, buckets.get(0).count());
        assertEquals(1, buckets.get(1).count());
        assertTrue(buckets.get(0).bucketStart().compareTo(buckets.get(1).bucketStart()) < 0);
        // Sum over buckets equals the unbucketed count (the correctness invariant).
        long total =
                buckets.stream().mapToLong(QueryDtos.TimeseriesBucket::count).sum();
        assertEquals(2, total);

        // A daily interval collapses both into one bucket.
        var daily = controller
                .timeseries(
                        ctx,
                        new TimeseriesRequest(
                                "classifier_events",
                                "day",
                                new TimeRange("2026-06-09T00:00:00Z", "2026-06-11T00:00:00Z"),
                                null))
                .data()
                .buckets();
        assertEquals(1, daily.size());
        assertEquals(2, daily.get(0).count());
    }

    @Test
    void timeseriesRequiresExplicitRange() {
        String pid = seedProject("query-ts-range");
        var ctx = token(pid);
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.timeseries(ctx, new TimeseriesRequest("spans", "hour", null, null)));
        assertEquals(QueryError.INVALID_RANGE, e.error());
    }

    // ---- facets ------------------------------------------------------------------------------

    @Test
    void facetsBreakdownByDimension() {
        String pid = seedProject("query-facets");
        var ctx = token(pid);

        var spanKinds = controller
                .facets(ctx, new FacetsRequest("spans", "kind", null, null, null))
                .data();
        assertEquals("kind", spanKinds.field());
        assertEquals(2, spanKinds.facets().size());
        // Each kind appears once; both buckets count 1.
        assertTrue(spanKinds.facets().stream().allMatch(f -> f.count() == 1));

        var toolNames = controller
                .facets(ctx, new FacetsRequest("tool_calls", "name", null, null, null))
                .data();
        assertEquals(2, toolNames.facets().size());

        // A v2 dimension: every span carries cost_source, so it faceted into one bucket here.
        var costSources = controller
                .facets(ctx, new FacetsRequest("spans", "cost_source", null, null, null))
                .data();
        assertEquals(1, costSources.facets().size());
        assertEquals("unpriced", costSources.facets().get(0).value());

        // The detections' subject vocabulary is facetable, so legacy-vocabulary rows are tellable apart.
        var subjectKinds = controller
                .facets(ctx, new FacetsRequest("classifier_events", "subject_kind", null, null, null))
                .data();
        assertTrue(subjectKinds.facets().stream().anyMatch(f -> "span".equals(f.value())));
    }

    /**
     * The coverage read grader synthesis grounds on: which call sites have telemetry in an environment,
     * and how much. One facet request answers it; untagged observations fall in the null bucket and so
     * never present as a call site.
     */
    @Test
    void facetsAndFiltersSpansByCallSiteAndEnvironment() {
        String pid =
                TenantFixture.bootstrap(tenants, "query-call-site").project().id();
        Instant t0 = Instant.parse("2026-06-10T00:00:00Z");

        var fx = fixtures();
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.session(pid, sessionId, t0);

        // Two spans tagged `summarizer`, one tagged `router`, one untagged.
        insertTagged(pid, traceId, sessionId, "summarizer", t0);
        insertTagged(pid, traceId, sessionId, "summarizer", t0);
        insertTagged(pid, traceId, sessionId, "router", t0);
        insertTagged(pid, traceId, sessionId, null, t0);

        var ctx = token(pid);
        var byCallSite = controller
                .facets(ctx, new FacetsRequest("spans", "call_site_id", null, Map.of(), null))
                .data();
        var counts = byCallSite.facets().stream()
                .filter(f -> f.value() != null)
                .collect(java.util.stream.Collectors.toMap(f -> f.value(), f -> f.count()));
        assertEquals(Long.valueOf(2), counts.get("summarizer"));
        assertEquals(Long.valueOf(1), counts.get("router"));
        assertEquals(2, counts.size(), "only tagged spans name a call site");

        // The untagged span is not absent: GROUP BY emits it as a null-valued bucket that is
        // ranked, and capped, like any other. Callers must drop it themselves, and must treat a full
        // top_n response as possibly having evicted a real call site to make room for it.
        var nullBucket =
                byCallSite.facets().stream().filter(f -> f.value() == null).toList();
        assertEquals(1, nullBucket.size(), "untagged spans form exactly one null facet bucket");
        assertEquals(1, nullBucket.get(0).count(), "the null bucket counts the untagged span");

        long summarizer = controller
                .count(ctx, new CountRequest("spans", null, Map.of("call_site_id", "summarizer")))
                .data()
                .count();
        assertEquals(2, summarizer, "call_site_id is filterable");
    }

    /** One span on {@code traceId}, optionally stamped with a call site. */
    private void insertTagged(String pid, String traceId, String sessionId, @Nullable String callSiteId, Instant at) {
        var ref = fixtures()
                .spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .callSiteId(callSiteId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(at)
                .payload("hi", "hello there")
                .writeRef();
        backdate(pid, ref, at);
    }

    @Test
    void facetsRejectsUnknownDimension() {
        String pid = seedProject("query-facets-bad");
        var ctx = token(pid);
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.facets(ctx, new FacetsRequest("spans", "input", null, null, null)));
        assertEquals(QueryError.UNKNOWN_FIELD, e.error());
    }

    // ---- search ------------------------------------------------------------------------------

    @Test
    void searchReturnsMatchingRows() {
        String pid = seedProject("query-search");
        var ctx = token(pid);

        // Keyword over the span's stored output preview ("hello there" on the llm span only). The
        // full payload is off-row and deliberately not searched here: this dataset stays a
        // single-table read.
        var byKeyword = controller
                .search(ctx, new SearchRequest("spans", "hello", null, null, null, null, null))
                .data();
        assertEquals(1, byKeyword.rows().size());
        assertEquals("chat", byKeyword.rows().get(0).fields().get("name"));
        assertNull(byKeyword.nextCursor());

        // A span row's id is the composite handle, and both halves also ride as their own fields, so
        // a caller can hand them straight to get_span without parsing anything.
        var hit = byKeyword.rows().get(0);
        assertEquals(hit.fields().get("trace_id") + ":" + hit.fields().get("span_id"), hit.id());

        // Structured filter (no keyword) returns all matching rows.
        var byFilter = controller
                .search(ctx, new SearchRequest("tool_calls", null, null, null, Map.of("name", "db_lookup"), null, null))
                .data();
        assertEquals(1, byFilter.rows().size());
        assertEquals("db_lookup", byFilter.rows().get(0).fields().get("name"));

        // Pagination: limit=1 over 2 spans yields a next_cursor; the next page completes the set. The
        // keyset tiebreak is (created_at, trace_id, id): a span id alone does not break ties, because
        // two traces may legitimately hold spans with the same producer id.
        var page1 = controller
                .search(ctx, new SearchRequest("spans", null, null, null, null, 1, null))
                .data();
        assertEquals(1, page1.rows().size());
        assertNotNull(page1.nextCursor());
        var page2 = controller
                .search(ctx, new SearchRequest("spans", null, null, null, null, 1, page1.nextCursor()))
                .data();
        assertEquals(1, page2.rows().size());
        assertFalse(page1.rows().get(0).id().equals(page2.rows().get(0).id()));

        // A cursor minted by an earlier release has the wrong arity. It degrades to page one rather than
        // 500-ing on someone's bookmarked page token.
        var legacyCursor = controller
                .search(ctx, new SearchRequest("spans", null, null, null, null, 1, "2026-06-10T00:00:00Z|some-ulid"))
                .data();
        assertEquals(1, legacyCursor.rows().size());
        assertEquals(page1.rows().get(0).id(), legacyCursor.rows().get(0).id());
    }

    @Test
    void searchRejectsSemanticModeAsUnknown() {
        // The API rejects an unknown "semantic" search mode outright, a 400 UNKNOWN_SEARCH_MODE
        // rather than a silent fallback to keyword.
        String pid = seedProject("query-search-semantic");
        var ctx = token(pid);
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.search(ctx, new SearchRequest("spans", "x", "semantic", null, null, null, null)));
        assertEquals(QueryError.UNKNOWN_SEARCH_MODE, e.error());
    }

    // ---- auth + tenancy ----------------------------------------------------------------------

    @Test
    void rejectsNonTokenContext() {
        String pid = seedProject("query-auth");
        // A user-session context (no MCP token) must be rejected: the surface is token-scoped.
        var userCtx = new TenantContext("user-1", null, "org-1", pid, "member", null);
        TessaryException e = assertThrows(
                TessaryException.class, () -> controller.count(userCtx, new CountRequest("spans", null, null)));
        assertEquals(QueryError.TOKEN_REQUIRED, e.error());
    }

    @Test
    void readsAreScopedToTheToprojectsOwnData() {
        String pidA = seedProject("query-tenant-a");
        String pidB = seedProject("query-tenant-b");
        // Project A's token sees only A's rows even though B has identical data.
        assertEquals(
                2,
                controller
                        .count(token(pidA), new CountRequest("spans", null, null))
                        .data()
                        .count());
        assertEquals(
                2,
                controller
                        .count(token(pidB), new CountRequest("spans", null, null))
                        .data()
                        .count());
        // Cross-checking the raw table confirms 4 total across both projects: the query API isolates them.
        Long all = jdbc.sql("SELECT COUNT(*) FROM span WHERE project_id IN (:a, :b)")
                .param("a", pidA)
                .param("b", pidB)
                .query(Long.class)
                .single();
        assertEquals(4L, all);
    }

    @Test
    void rejectsUnknownDataset() {
        String pid = seedProject("query-bad-dataset");
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.count(token(pid), new CountRequest("not_a_dataset", null, null)));
        assertEquals(QueryError.UNKNOWN_DATASET, e.error());
    }

    /**
     * {@code observations} is not a dataset: an unknown dataset like any other misspelling, not a
     * silently-working second name for {@code spans}.
     */
    @Test
    void rejectsTheRetiredObservationsAlias() {
        String pid = seedProject("query-retired-alias");
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.count(token(pid), new CountRequest("observations", null, null)));
        assertEquals(QueryError.UNKNOWN_DATASET, e.error());
    }
}
