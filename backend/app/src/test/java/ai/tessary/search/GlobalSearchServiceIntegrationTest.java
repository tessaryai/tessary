// SPDX-License-Identifier: Apache-2.0
package ai.tessary.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.search.GlobalSearchDtos.SearchHit;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The global search palette over real Postgres with pg_trgm: ranked trace hits scoped to the caller's project. The
 * trace leg searches {@code span_payload} full-text and {@code span.name} by trigram, and returns the trace id, which
 * is what {@code traces/<id>} routes on.
 */
@SpringBootTest
class GlobalSearchServiceIntegrationTest {

    @Autowired
    GlobalSearchService service;

    @Autowired
    GlobalSearchRepository repository;

    @Autowired
    GlobalSearchController controller;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @Test
    void resultsAreRankedBestFirst() {
        String pid = TenantFixture.bootstrap(tenants, "search-rank").project().id();
        // Two spans: one whose payload repeats the term (higher ts_rank), one with a single mention.
        seedSpan(pid, "latency latency latency", "latency latency in the response", "latency budget blown");
        seedSpan(pid, "tone", "a passing aside", "mentions latency once");

        List<SearchHit> hits = service.search(pid, "latency");

        assertFalse(hits.isEmpty(), "the term matches at least one span");
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(
                    hits.get(i - 1).score() >= hits.get(i).score(), "hits are sorted by descending score (best-first)");
        }
    }

    @Test
    void searchIsScopedToProject() {
        String pidA = TenantFixture.bootstrap(tenants, "search-a").project().id();
        String pidB = TenantFixture.bootstrap(tenants, "search-b").project().id();
        String traceA = seedSpan(pidA, "quarterly forecast", "tenant A only", "tenant A only");
        seedSpan(pidB, "quarterly forecast", "tenant B only", "tenant B only");

        List<SearchHit> fromA = service.search(pidA, "quarterly forecast");
        assertEquals(1, fromA.size(), "only the caller's project is searched");
        // The id, not the snippet: a trigram hit carries no snippet, and it is the other tenant's row that must not
        // appear.
        assertEquals(traceA, fromA.get(0).id(), "no other tenant's row leaks in");
    }

    @Test
    void tolerantOfTyposAndShortPrefixes() {
        // Pure FTS needs whole tokens; the {@code name % :q} trigram fallback forgives typos and prefixes.
        String pid = TenantFixture.bootstrap(tenants, "search-trgm").project().id();
        String traceId = seedSpan(pid, "refund tone check", "in", "out");

        List<SearchHit> typo = service.search(pid, "refnud");
        assertTrue(
                typo.stream().anyMatch(h -> h.id().equals(traceId)),
                "a mildly misspelled term surfaces the relevant trace via the trigram leg");

        List<SearchHit> prefix = service.search(pid, "refn");
        assertTrue(
                prefix.stream().anyMatch(h -> h.id().equals(traceId)),
                "a short prefix surfaces the relevant trace via the trigram leg");
    }

    @Test
    void exactMatchesRankAboveTrigramOnlyMatches() {
        // An exact full-text hit must rank above a pure-trigram one.
        String pid =
                TenantFixture.bootstrap(tenants, "search-trgm-rank").project().id();
        String exactId = seedSpan(pid, "refund accuracy", "was the refund amount correct", "refund issued");
        // Trigram-only: no whole "refund" token.
        seedSpan(pid, "refurbish notes", "notes about refurbishment", "refurbishment done");

        List<SearchHit> hits = service.search(pid, "refund");

        assertFalse(hits.isEmpty(), "the exact term matches at least the exact span");
        assertEquals(exactId, hits.get(0).id(), "the exact full-text hit ranks first, above any trigram-only hit");
    }

    @Test
    void blankQueryReturnsNoHits() {
        String pid = TenantFixture.bootstrap(tenants, "search-blank").project().id();
        seedSpan(pid, "anything", "any body", "any body");
        assertTrue(service.search(pid, "   ").isEmpty(), "a blank query never scans, returns empty");
    }

    /** Payload text is searchable: the FTS leg reads {@code span_payload}, where v2 keeps the conversation. */
    @Test
    void spanPayloadTextIsSearchableThoughTheSpanNameIsNot() {
        String pid =
                TenantFixture.bootstrap(tenants, "search-payload").project().id();
        String traceId = seedSpan(pid, "chat", "the customer demanded a chargeback", "we declined");

        List<SearchHit> hits = service.search(pid, "chargeback");
        assertTrue(
                hits.stream().anyMatch(h -> h.id().equals(traceId)),
                "a term appearing only in the payload still surfaces the trace");
    }

    /**
     * The FTS leg must spell migration 0077's capped expression exactly, or no index matches and every payload is
     * scanned. With non-bitmap paths off, a plan exists only if {@code ix_span_payload_fts} serves it.
     */
    @Test
    @Transactional
    void spanFullTextLegIsIndexBacked() {
        String pid =
                TenantFixture.bootstrap(tenants, "search-fts-explain").project().id();
        seedSpan(pid, "chat", "the customer demanded a chargeback", "we declined");
        jdbc.sql("SET LOCAL enable_seqscan = off").update();
        jdbc.sql("SET LOCAL enable_indexscan = off").update();

        String plan = jdbc.sql("""
                        EXPLAIN (FORMAT TEXT)
                        SELECT trace_id FROM span_payload
                        WHERE to_tsvector('simple',
                                  left(coalesce(input, ''), 100000) || ' ' || left(coalesce(output, ''), 100000))
                              @@ plainto_tsquery('simple', :q)
                        """).param("q", "chargeback").query((rs, n) -> rs.getString(1)).list().stream()
                .collect(Collectors.joining("\n"));

        assertTrue(
                plan.contains("ix_span_payload_fts") && plan.contains("Bitmap Index Scan"),
                "the capped to_tsvector predicate must be served by ix_span_payload_fts, not a Seq Scan:\n" + plan);
    }

    /** The same, for the span-name trigram leg (ix_span_name_trgm, migration 0081). */
    @Test
    @Transactional
    void spanNameTrigramLegIsIndexBacked() {
        String pid =
                TenantFixture.bootstrap(tenants, "search-span-trgm").project().id();
        seedSpan(pid, "refund tone check", "in", "out");
        jdbc.sql("SET LOCAL enable_seqscan = off").update();
        jdbc.sql("SET LOCAL enable_indexscan = off").update();
        jdbc.sql("SET LOCAL pg_trgm.word_similarity_threshold = 0.4").update();

        String plan = jdbc.sql("""
                        EXPLAIN (FORMAT TEXT)
                        SELECT trace_id FROM span WHERE name %> :q
                        """).param("q", "refn").query((rs, n) -> rs.getString(1)).list().stream()
                .collect(Collectors.joining("\n"));

        assertTrue(
                plan.contains("ix_span_name_trgm") && plan.contains("Bitmap Index Scan"),
                "the `name %> :q` predicate on span must be served by ix_span_name_trgm:\n" + plan);
    }

    /**
     * Seeds a trace, span, and span_payload with raw SQL, the name feeding the trigram leg and the payload the FTS
     * leg. Returns the trace id.
     */
    private String seedSpan(String projectId, String name, String input, String output) {
        String now = Instant.now().toString();
        String traceId = Ids.ulid();
        jdbc.sql("""
                        INSERT INTO trace (project_id, id, started_at, event_ts)
                        VALUES (:pid, :id, :now::timestamptz, :now::timestamptz)
                        """)
                .param("pid", projectId)
                .param("id", traceId)
                .param("now", now)
                .update();
        String spanId = Ids.ulid();
        jdbc.sql("""
                        INSERT INTO span (project_id, trace_id, id, kind, name, started_at, event_ts)
                        VALUES (:pid, :trace, :id, 'llm', :name, :now::timestamptz, :now::timestamptz)
                        """)
                .param("pid", projectId)
                .param("trace", traceId)
                .param("id", spanId)
                .param("name", name)
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO span_payload (project_id, trace_id, span_id, input, output, event_ts)
                        VALUES (:pid, :trace, :id, :input, :output, :now::timestamptz)
                        """)
                .param("pid", projectId)
                .param("trace", traceId)
                .param("id", spanId)
                .param("input", input)
                .param("output", output)
                .param("now", now)
                .update();
        return traceId;
    }

    /**
     * One hit per trace, keeping the stronger named hit; an unnamed span is titled by its trace; a long excerpt is
     * clipped; a caller outside the org is refused.
     */
    @Test
    void theSearchEndpointNamesEachTraceOnceAndTitlesAnUnnamedSpanByItsTrace() {
        var fix = TenantFixture.bootstrap(tenants, "search-endpoint");
        String pid = fix.project().id();
        String weakPayload = seedSpan(pid, null, "a note on reconciliation", "ok");
        seedSpanIn(pid, weakPayload, "reconciliation", "unrelated words");
        String longBody = "reconciliation " + "x".repeat(200);
        String unnamed = seedSpan(pid, null, longBody, "ok");
        String blankName = seedSpan(pid, "   ", "reconciliation", "ok");
        TenantContext member = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);

        List<SearchHit> hits = controller
                .search(member, fix.org().slug(), fix.project().slug(), "reconciliation")
                .data()
                .hits();

        Map<String, SearchHit> byTrace = hits.stream().collect(Collectors.toMap(SearchHit::id, Function.identity()));
        assertEquals(3, hits.size(), "one hit per trace: " + hits);
        assertEquals("reconciliation", byTrace.get(weakPayload).title(), "the stronger named-span hit wins");
        assertEquals(null, byTrace.get(weakPayload).snippet());
        assertEquals("Trace " + unnamed, byTrace.get(unnamed).title());
        assertEquals(longBody.substring(0, 117) + "\u2026", byTrace.get(unnamed).snippet());
        assertEquals("Trace " + blankName, byTrace.get(blankName).title());
        assertEquals("reconciliation", byTrace.get(blankName).snippet());

        var outsider =
                TenantFixture.bootstrap(tenants, "search-endpoint-outsider").user();
        TenantContext stranger = new TenantContext(outsider.id(), outsider.email(), null, null, null, null);
        ResponseStatusException refused = assertThrows(
                ResponseStatusException.class,
                () -> controller.search(
                        stranger, fix.org().slug(), fix.project().slug(), "reconciliation"));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
    }

    /** The merged legs are capped at the limit: two payload-only and two name-only matches at limit two return two. */
    @Test
    void theTraceLegCapsTheMergedHitsAtItsLimit() {
        String pid = TenantFixture.bootstrap(tenants, "search-cap").project().id();
        seedSpan(pid, "chat", "escalation needed", "ok");
        seedSpan(pid, "chat", "escalation again", "ok");
        seedSpan(pid, "escalation", "nothing here", "ok");
        seedSpan(pid, "escalation step", "nothing here", "ok");

        assertEquals(4, repository.searchLexical(pid, "escalation", 10).size(), "precondition: four traces match");
        assertEquals(2, repository.searchLexical(pid, "escalation", 2).size());
    }

    /** One more span, under an existing trace, for a trace that matches on more than one span. */
    private void seedSpanIn(String projectId, String traceId, @Nullable String name, String input) {
        String now = Instant.now().toString();
        String spanId = Ids.ulid();
        jdbc.sql("""
                        INSERT INTO span (project_id, trace_id, id, kind, name, started_at, event_ts)
                        VALUES (:pid, :trace, :id, 'llm', :name, :now::timestamptz, :now::timestamptz)
                        """)
                .param("pid", projectId)
                .param("trace", traceId)
                .param("id", spanId)
                .param("name", name)
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO span_payload (project_id, trace_id, span_id, input, output, event_ts)
                        VALUES (:pid, :trace, :id, :input, 'ok', :now::timestamptz)
                        """)
                .param("pid", projectId)
                .param("trace", traceId)
                .param("id", spanId)
                .param("input", input)
                .param("now", now)
                .update();
    }
}
