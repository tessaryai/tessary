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
 * The query API ({@code count}, {@code timeseries}, {@code facets}, {@code search}) against real Postgres, so the
 * substrate indexes and {@code date_trunc} bucketing run for real, with every read going through controller, service
 * and repository.
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

    private static TenantContext token(String projectId) {
        return new TenantContext("user-1", null, "org-1", projectId, "member", "tok-1");
    }

    private SubstrateV2Fixtures fixtures() {
        return new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    /** Two spans (llm "chat", tool "search") at t0 and t0+2h. */
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
        // The window is on started_at; backdating created_at keeps the displayed ingest time deterministic.
        backdate(pid, llm, t0);
        backdate(pid, tool, t2);

        fx.toolCall(pid, tool, "web_search", null, t0);
        fx.toolCall(pid, tool, "db_lookup", "timeout", t2);

        // 'frustration' is off by default, so this test inserts its own classifier row directly; it is about the
        // query API, not capability gating.
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
     * A detection in the span-grain table, read back through the {@code classifier_events} union. {@code
     * subject_started_at} is set because the dataset ranges on it (0012, decision 8b); null would drop the row from
     * every ranged read.
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
                        + "subject_session_id, subject_trace_id, subject_span_id, severity, confidence,"
                        + " subject_started_at, created_at) "
                        + "VALUES (:id, :pid, :sid, :key, :ctx, :trace, :subj, :sev, 'high', :at::timestamptz,"
                        + " :at::timestamptz)")
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

        assertEquals(
                1,
                controller
                        .count(ctx, new CountRequest("spans", null, Map.of("kind", "tool")))
                        .data()
                        .count());
        // cost_source is written on every span, so an unpriced fixture counts as such.
        assertEquals(
                2,
                controller
                        .count(ctx, new CountRequest("spans", null, Map.of("cost_source", "unpriced")))
                        .data()
                        .count());
        long inWindow = controller
                .count(
                        ctx,
                        new CountRequest(
                                "tool_calls", new TimeRange("2026-06-10T00:00:00Z", "2026-06-10T01:00:00Z"), null))
                .data()
                .count();
        assertEquals(1, inWindow);
    }

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
        assertEquals(2, buckets.size());
        assertEquals(1, buckets.get(0).count());
        assertEquals(1, buckets.get(1).count());
        assertTrue(buckets.get(0).bucketStart().compareTo(buckets.get(1).bucketStart()) < 0);
        // The bucket sum equals the unbucketed count.
        long total =
                buckets.stream().mapToLong(QueryDtos.TimeseriesBucket::count).sum();
        assertEquals(2, total);

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

    /**
     * Decision 8: spans bucket on {@code started_at}. Both spans share one {@code created_at}, so bucketing on it
     * would collapse them.
     */
    @Test
    void timeseriesBucketsOnStartedAtEvenWhenCreatedAtIsIdentical() {
        String pid = TenantFixture.bootstrap(tenants, "query-ts-started-at")
                .project()
                .id();
        Instant t0 = Instant.parse("2026-06-10T00:00:00Z");
        Instant t2 = t0.plus(2, ChronoUnit.HOURS);
        Instant arrived = Instant.parse("2026-06-15T00:00:00Z");
        var fx = fixtures();
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.session(pid, sessionId, t0);

        var first = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .at(t0)
                .writeRef();
        var second = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .kind("tool")
                .name("search")
                .at(t2)
                .writeRef();
        backdate(pid, first, arrived);
        backdate(pid, second, arrived);

        var buckets = controller
                .timeseries(
                        token(pid),
                        new TimeseriesRequest(
                                "spans", "hour", new TimeRange("2026-06-09T00:00:00Z", "2026-06-11T00:00:00Z"), null))
                .data()
                .buckets();
        assertEquals(2, buckets.size(), "buckets follow started_at, not the shared created_at: " + buckets);
    }

    @Test
    void facetsBreakdownByDimension() {
        String pid = seedProject("query-facets");
        var ctx = token(pid);

        var spanKinds = controller
                .facets(ctx, new FacetsRequest("spans", "kind", null, null, null))
                .data();
        assertEquals("kind", spanKinds.field());
        assertEquals(2, spanKinds.facets().size());
        assertTrue(spanKinds.facets().stream().allMatch(f -> f.count() == 1));

        var toolNames = controller
                .facets(ctx, new FacetsRequest("tool_calls", "name", null, null, null))
                .data();
        assertEquals(2, toolNames.facets().size());

        var costSources = controller
                .facets(ctx, new FacetsRequest("spans", "cost_source", null, null, null))
                .data();
        assertEquals(1, costSources.facets().size());
        assertEquals("unpriced", costSources.facets().get(0).value());

        // The subject vocabulary is facetable, so legacy-vocabulary rows can be told apart.
        var subjectKinds = controller
                .facets(ctx, new FacetsRequest("classifier_events", "subject_kind", null, null, null))
                .data();
        assertTrue(subjectKinds.facets().stream().anyMatch(f -> "span".equals(f.value())));
    }

    /** The coverage read synthesis grounds on: call sites with telemetry in an environment, in one facet request. */
    @Test
    void facetsAndFiltersSpansByCallSiteAndEnvironment() {
        String pid =
                TenantFixture.bootstrap(tenants, "query-call-site").project().id();
        Instant t0 = Instant.parse("2026-06-10T00:00:00Z");

        var fx = fixtures();
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.session(pid, sessionId, t0);

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

        // The untagged span is a null bucket, ranked and capped like any other: callers must drop it, and a full
        // top_n may have evicted a real call site for it.
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

    @Test
    void searchReturnsMatchingRows() {
        String pid = seedProject("query-search");
        var ctx = token(pid);

        // Keyword over the stored output preview only; the payload is off-row, keeping this a single-table read.
        var byKeyword = controller
                .search(ctx, new SearchRequest("spans", "hello", null, null, null, null, null))
                .data();
        assertEquals(1, byKeyword.rows().size());
        assertEquals("chat", byKeyword.rows().get(0).fields().get("name"));
        assertNull(byKeyword.nextCursor());

        // Both halves of the composite id ride as fields, ready for get_span.
        var hit = byKeyword.rows().get(0);
        assertEquals(hit.fields().get("trace_id") + ":" + hit.fields().get("span_id"), hit.id());

        var byFilter = controller
                .search(ctx, new SearchRequest("tool_calls", null, null, null, Map.of("name", "db_lookup"), null, null))
                .data();
        assertEquals(1, byFilter.rows().size());
        assertEquals("db_lookup", byFilter.rows().get(0).fields().get("name"));

        // The keyset tiebreak is (created_at, trace_id, id): two traces may hold spans with the same producer id.
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

        // An older release's cursor degrades to page one rather than a 500.
        var legacyCursor = controller
                .search(ctx, new SearchRequest("spans", null, null, null, null, 1, "2026-06-10T00:00:00Z|some-ulid"))
                .data();
        assertEquals(1, legacyCursor.rows().size());
        assertEquals(page1.rows().get(0).id(), legacyCursor.rows().get(0).id());
    }

    /**
     * An unprefixed cursor restarts at page one even with the right shape: before decision 8 this exact token was
     * valid, and trusted it would resume at the wrong position.
     */
    @Test
    void unprefixedCursorFallsBackToPageOneEvenWithTheRightShape() {
        String pid = seedProject("query-search-unprefixed-cursor");
        var ctx = token(pid);

        var page1 = controller
                .search(ctx, new SearchRequest("spans", null, null, null, null, 1, null))
                .data();
        assertNotNull(page1.nextCursor());
        assertTrue(page1.nextCursor().startsWith("e1|"), page1.nextCursor());

        String unprefixed = page1.nextCursor().substring("e1|".length());
        var resumed = controller
                .search(ctx, new SearchRequest("spans", null, null, null, null, 1, unprefixed))
                .data();
        assertEquals(1, resumed.rows().size());
        assertEquals(
                page1.rows().get(0).id(),
                resumed.rows().get(0).id(),
                "an unprefixed cursor restarts at page one rather than resuming the keyset");
    }

    @Test
    void searchRejectsSemanticModeAsUnknown() {
        // An unknown "semantic" mode is a 400, never a silent keyword fallback.
        String pid = seedProject("query-search-semantic");
        var ctx = token(pid);
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.search(ctx, new SearchRequest("spans", "x", "semantic", null, null, null, null)));
        assertEquals(QueryError.UNKNOWN_SEARCH_MODE, e.error());
    }

    @Test
    void readsAreScopedToTheToprojectsOwnData() {
        String pidA = seedProject("query-tenant-a");
        String pidB = seedProject("query-tenant-b");
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
        // The raw table holds 4 across both projects; the API isolates them.
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

    /** {@code observations} is an unknown dataset, not a second name for {@code spans}. */
    @Test
    void rejectsTheRetiredObservationsAlias() {
        String pid = seedProject("query-retired-alias");
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> controller.count(token(pid), new CountRequest("observations", null, null)));
        assertEquals(QueryError.UNKNOWN_DATASET, e.error());
    }

    /**
     * Tool calls page on a bare-id handle; an empty handle restarts at page one rather than skipping every row
     * sharing its timestamp.
     */
    @Test
    void toolCallSearchPagesOnItsBareIdHandleAndAnEmptyHandleRestartsAtPageOne() {
        String pid = seedProject("query-search-bare-handle");
        var ctx = token(pid);

        var page1 = controller
                .search(ctx, new SearchRequest("tool_calls", null, null, null, null, 1, null))
                .data();
        assertEquals("db_lookup", page1.rows().get(0).fields().get("name"), "newest first");
        String cursor = page1.nextCursor();
        assertNotNull(cursor);
        var page2 = controller
                .search(ctx, new SearchRequest("tool_calls", null, null, null, null, 1, cursor))
                .data();
        String emptyHandle = cursor.substring(0, cursor.lastIndexOf('|') + 1);
        var restarted = controller
                .search(ctx, new SearchRequest("tool_calls", null, null, null, null, 1, emptyHandle))
                .data();

        assertEquals("web_search", page2.rows().get(0).fields().get("name"));
        assertEquals(page1.rows().get(0).id(), restarted.rows().get(0).id(), "an empty handle restarts at page one");
    }

    /**
     * An unallowed filter field or a non-{@code date_trunc} interval is refused by name before any SQL, so neither
     * reaches the query as an identifier.
     */
    @Test
    void anUnknownFilterFieldOrIntervalIsRefusedBeforeAnySql() {
        var ctx = token("no-such-project");
        TimeRange day = new TimeRange("2026-06-10T00:00:00Z", "2026-06-11T00:00:00Z");

        TessaryException field = assertThrows(
                TessaryException.class,
                () -> controller.count(ctx, new CountRequest("spans", null, Map.of("input", "x"))));
        TessaryException interval = assertThrows(
                TessaryException.class,
                () -> controller.timeseries(ctx, new TimeseriesRequest("spans", "fortnight", day, null)));

        assertEquals(QueryError.UNKNOWN_FIELD, field.error());
        assertEquals(QueryError.UNKNOWN_INTERVAL, interval.error());
    }
}
