// SPDX-License-Identifier: Apache-2.0
package ai.tessary.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.search.GlobalSearchDtos.SearchHit;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceptance for the global search palette read surface: a typed query returns ranked content
 * matches, scoped to the caller's project. There is ONE hit type — trace — where there were
 * three; the grader and dataset legs went with their tables.
 * Exercises {@link GlobalSearchService} over a real Postgres (Testcontainers, with the pg_trgm
 * extension + name trigram indexes for typo/prefix tolerance).
 * The trace leg searches {@code span_payload} full-text and {@code span.name} by trigram, and it surfaces
 * the TRACE — that is what {@code traces/<id>} routes on — so a match is seeded as a real trace→span slice
 * and asserted by trace id.
 * Rows are seeded with a raw {@link JdbcClient} — the surface under test never writes, so a minimal
 * direct insert keeps the fixture free of unrelated repository coupling.
 */
@SpringBootTest
class GlobalSearchServiceIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    GlobalSearchService service;

    @Autowired
    GlobalSearchRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    /**
     * There is exactly ONE hit type now, and that is the claim worth pinning.
     *
     * <p>This test used to seed a grader, a dataset and a span and assert all three types came back.
     * The first two entity types and their search legs were deleted with their tables, so what is left to
     * assert is the narrower fact: a match surfaces as a {@code trace}, and the id it carries is the
     * TRACE id rather than the span's — which is what the palette's {@code traces/<id>} route takes.
     * Under v1 this returned the observation id into that same route, a key the route could not resolve.
     */
    @Test
    void aPayloadMatchSurfacesAsATraceHitNamingItsTrace() {
        String pid = TenantFixture.bootstrap(tenants, "search-types").project().id();
        String traceId = seedSpan(pid, "payment flow", "user asks about a payment", "processed the payment");

        List<SearchHit> hits = service.search(pid, "payment");

        var byType = hits.stream().collect(Collectors.groupingBy(SearchHit::type));
        assertEquals(Set.of("trace"), byType.keySet(), "trace is the only hit type the surface can produce");

        Set<String> ids = hits.stream().map(SearchHit::id).collect(Collectors.toSet());
        assertTrue(ids.contains(traceId), "the span match names its trace, not the span");
    }

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
        // Asserted on the id rather than the snippet: a trigram-leg hit matches on span.name and carries
        // no snippet, and the id is the stronger claim anyway — it is the other tenant's ROW that must
        // not be here, not merely its text.
        assertEquals(traceA, fromA.get(0).id(), "no other tenant's row leaks in");
    }

    @Test
    void tolerantOfTyposAndShortPrefixes() {
        // A trigram leg makes the palette forgiving as you type. Pure FTS needs whole
        // tokens, so a misspelling or a short prefix returns nothing; the pg_trgm `name % :q` fallback
        // surfaces the row anyway.
        String pid = TenantFixture.bootstrap(tenants, "search-trgm").project().id();
        String traceId = seedSpan(pid, "refund tone check", "in", "out");

        // Mild misspelling — "refnud" shares enough trigrams with "refund tone check" to match.
        List<SearchHit> typo = service.search(pid, "refnud");
        assertTrue(
                typo.stream().anyMatch(h -> h.id().equals(traceId)),
                "a mildly misspelled term surfaces the relevant trace via the trigram leg");

        // Short prefix — too short to be a whole FTS token, matched by trigram similarity on the name.
        List<SearchHit> prefix = service.search(pid, "refn");
        assertTrue(
                prefix.stream().anyMatch(h -> h.id().equals(traceId)),
                "a short prefix surfaces the relevant trace via the trigram leg");
    }

    @Test
    void exactMatchesRankAboveTrigramOnlyMatches() {
        // The composite score must keep an exact full-text hit above a pure-trigram one.
        String pid =
                TenantFixture.bootstrap(tenants, "search-trgm-rank").project().id();
        // Exact FTS hit on the whole token "refund".
        String exactId = seedSpan(pid, "refund accuracy", "was the refund amount correct", "refund issued");
        // Trigram-only hit: shares trigrams with the misspelling but contains no whole "refund" token.
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

    @Test
    void noMatchReturnsEmptyNotError() {
        String pid = TenantFixture.bootstrap(tenants, "search-empty").project().id();
        seedSpan(pid, "apples", "fruit basket", "fruit basket");
        assertTrue(service.search(pid, "zzzznonexistent").isEmpty(), "a no-match query lists empty, not an error");
    }

    /**
     * Payload text is searchable even though the span's own columns say nothing about it: the FTS leg reads
     * {@code span_payload}, which is where the conversation lives in v2.
     */
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
     * The span FTS leg must use migration 0077's capped expression CHARACTER FOR CHARACTER, or Postgres
     * matches no expression index and the query sequentially scans every payload in the project. This
     * asserts the index is genuinely reachable from the exact expression the repository spells: with every
     * non-bitmap path forced off, a plan can only be produced if {@code ix_span_payload_fts} serves it.
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

    // trigramLegIsIndexBacked lived here, EXPLAINing `SELECT id FROM grader WHERE name %> :q` against
    // ix_grader_name_trgm. Both the table and the index are gone. It is deleted rather than
    // re-pointed because spanNameTrigramLegIsIndexBacked above already makes the identical claim
    // (`name %> :q` must reach a gin_trgm_ops index via a Bitmap Index Scan, never a Seq Scan) on the
    // one table the surface still reads.

    /**
     * Seed a real substrate slice — trace → span → span_payload — with the searchable text split between
     * the span's {@code name} (the trigram leg) and the payload's input/output (the full-text leg), and
     * return the TRACE id, which is what a palette hit names.
     *
     * <p>Written with raw SQL rather than the v2 repositories: the surface under test never writes, and
     * a direct insert keeps the fixture free of repository coupling it does not exercise.
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
}
