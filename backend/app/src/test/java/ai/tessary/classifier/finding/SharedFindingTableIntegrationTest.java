// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.ingest.PreviewCursor;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The shared {@code finding} table's invariants against the real schema, since each is a constraint or predicate a
 * unit test would only mock: the live uniqueness arbiter and its {@code blocked} arm, the bounded append-only
 * evidence set, and the case's mandatory finding pointer.
 */
@SpringBootTest
class SharedFindingTableIntegrationTest {

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    FindingService behaviorDrift;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    /** The dossier reads the first page in the detector's order and needs the cursor to say whether more follow. */
    @Test
    @DisplayName("the evidence page is the unpaged order's head, and the counts report every role")
    void evidencePagesInStableOrderAndCountsEveryRole() {
        Project p = project("finding-evidence-paging");
        String findingId = firing(p, "gram-paging");
        String now = Instant.now().toString();

        List<FindingEvidenceRepository.Ref> members = new ArrayList<>();
        for (int i = 0; i < 5; i++) members.add(FindingEvidenceRepository.Ref.span("trace-" + i, "span-" + i));
        evidence.record(p.id(), findingId, FindingEvidenceRow.Role.MEMBER, members, now);
        evidence.record(
                p.id(),
                findingId,
                FindingEvidenceRow.Role.BASELINE,
                List.of(
                        FindingEvidenceRepository.Ref.trace("ref-0"),
                        FindingEvidenceRepository.Ref.trace("ref-1"),
                        FindingEvidenceRepository.Ref.trace("ref-2")),
                now);

        List<String> unpaged = evidence.listByFinding(p.id(), findingId).stream()
                .map(r -> r.role() + ':' + r.id())
                .toList();
        FindingEvidenceRepository.Page head = evidence.page(p.id(), findingId, 3);
        assertEquals(
                unpaged.subList(0, 3),
                head.rows().stream().map(r -> r.role() + ':' + r.id()).toList(),
                "the paged order must be the unpaged order");
        assertNotNull(head.nextCursor(), "a page that stopped short of the set says more rows follow");

        FindingEvidenceRepository.Page whole = evidence.page(p.id(), findingId, 50);
        assertEquals(8, whole.rows().size());
        assertNull(whole.nextCursor(), "a page that exhausted the set mints no cursor");

        // Every role is reported, so a detector with no reference side reads as an explicit zero.
        var counts = evidence.countsByRole(p.id(), findingId);
        assertEquals(FindingEvidenceRow.Role.ALL, List.copyOf(counts.keySet()));
        assertEquals(5L, counts.get(FindingEvidenceRow.Role.MEMBER));
        assertEquals(3L, counts.get(FindingEvidenceRow.Role.BASELINE));
        assertEquals(0L, counts.get(FindingEvidenceRow.Role.WITNESS));

        // count_only on a finding that has eight rows: an empty refs list is the caller's request, not vanished
        // evidence.
        var sized = behaviorDrift.findingEvidence(p.id(), findingId);
        assertTrue(sized.refs().isEmpty(), "the cheap first call spends nothing on rows");
        assertTrue(sized.rowsOmitted(), "rowsOmitted is what separates 'did not ask' from 'has none'");
        assertNull(sized.nextCursor(), "a call that returned no rows must not offer to resume after them");
        assertEquals(5L, sized.counts().get(FindingEvidenceRow.Role.MEMBER));
        assertEquals(5L, sized.recordedCounts().get(FindingEvidenceRow.Role.MEMBER));
    }

    /**
     * A trace-grain ref resolves to one span. {@code is_logical_root} marks every sub-agent boundary, so a plain join
     * multiplied a ref by the agents inside: zero-latency copies that disagreed with {@code countsByRole} and padded
     * any computation over the rows.
     */
    @Test
    @DisplayName("a trace-grain ref resolves to ONE span, the outermost root, however many agents ran inside")
    void traceGrainEvidenceDoesNotFanOutAcrossLogicalRoots() {
        Project p = project("finding-evidence-fanout");
        String findingId = firing(p, "gram-fanout");
        String now = Instant.now().toString();
        String traceId = "trace-many-roots";

        // One parentless root plus a logical root per sub-agent, all flagged is_logical_root.
        jdbc.sql("INSERT INTO trace (project_id, id, started_at, event_ts) VALUES (:pid, :tid, now(), now())")
                .param("pid", p.id())
                .param("tid", traceId)
                .update();
        insertRootSpan(p.id(), traceId, "span-outer", null, "invoke_agent outer", 5_000L);
        insertRootSpan(p.id(), traceId, "span-sub-a", "span-outer", "invoke_agent sub-a", 0L);
        insertRootSpan(p.id(), traceId, "span-sub-b", "span-outer", "invoke_agent sub-b", 0L);
        insertRootSpan(p.id(), traceId, "span-sub-c", "span-outer", "invoke_agent sub-c", 0L);

        evidence.record(
                p.id(),
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.trace(traceId)),
                now);

        var page = behaviorDrift.findingEvidenceSpans(p.id(), findingId, FindingEvidenceRow.Role.MEMBER, 100, null);

        assertEquals(1, page.rows().size(), "one ref is one row: " + page.rows());
        assertEquals(
                page.counts().get(FindingEvidenceRow.Role.MEMBER),
                (long) page.rows().size(),
                "the page and the count must describe the same evidence");
        var row = page.rows().get(0);
        assertEquals("invoke_agent outer", row.name(), "the outermost root, not whichever sub-agent sorted first");
        assertEquals(5_000L, row.latencyMs(), "a zero-latency sub-agent root would drag every computed median");
        assertNull(page.nextCursor(), "a page that exhausted the set mints no cursor");
    }

    /**
     * A whole-run row reads tokens and cost from the trace rollup (the root agent span carries neither), lists every
     * model called, and mirrors the rollup's caveats. Latency stays the root step's (decision R2), as the detector
     * measured it.
     */
    @Test
    @DisplayName("a whole-run row reads tokens and cost from the trace rollup, every model, and the rollup flags")
    void wholeRunEvidenceRowReadsTraceRollupAndModels() {
        Project p = project("finding-evidence-whole-run");
        String findingId = firing(p, "gram-whole-run");
        String now = Instant.now().toString();
        String traceId = "trace-whole-run";

        jdbc.sql("""
                INSERT INTO trace (project_id, id, started_at, event_ts, total_tokens, total_cost,
                                    unpriced_spans, rolled_up_at, is_settled)
                VALUES (:pid, :tid, now(), now(), 9000, 1.2345, 2, NULL, false)
                """).param("pid", p.id()).param("tid", traceId).update();
        insertRootSpan(p.id(), traceId, "span-root", null, "invoke_agent root", 4_000L);
        insertLlmSpan(p.id(), traceId, "span-llm-1", "span-root", "claude-opus-5", false);
        insertLlmSpan(p.id(), traceId, "span-llm-2", "span-root", "claude-haiku-5", false);
        insertLlmSpan(p.id(), traceId, "span-llm-3", "span-root", "claude-opus-5", false);
        insertLlmSpan(p.id(), traceId, "span-deleted", "span-root", "gpt-ghost", true);

        evidence.record(
                p.id(),
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.trace(traceId)),
                now);

        var page = behaviorDrift.findingEvidenceSpans(p.id(), findingId, FindingEvidenceRow.Role.MEMBER, 100, null);
        assertEquals(1, page.rows().size());
        var row = page.rows().get(0);
        assertEquals(4_000L, row.latencyMs(), "latency stays the root step, not a trace-wide figure");
        assertEquals(9000L, row.totalTokens(), "tokens come from the trace rollup, not the root span");
        assertNotNull(row.totalCost(), "cost comes from the trace rollup, not the root span");
        assertEquals(
                1.2345, row.totalCost().doubleValue(), 1e-9, "cost comes from the trace rollup, not the root span");
        assertEquals(
                List.of("claude-haiku-5", "claude-opus-5"),
                row.models(),
                "every distinct model the run called, the deleted span's excluded: " + row.models());
        assertTrue(row.notRolledUp(), "rolled_up_at is null");
        assertTrue(row.partialCost(), "unpriced_spans > 0");
        assertTrue(row.staleTotals(), "is_settled is false");
    }

    /** Inserted directly: the fixtures build natural traces, and this needs several logical roots in one. */
    private void insertRootSpan(
            String projectId, String traceId, String spanId, @Nullable String parentId, String name, long latencyMs) {
        jdbc.sql("INSERT INTO span (project_id, trace_id, id, parent_span_id, kind, name, is_logical_root,"
                        + " started_at, latency_ms, event_ts)"
                        + " VALUES (:pid, :tid, :sid, CAST(:parent AS text), 'agent', :name, true,"
                        + " now(), :latency, now())")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("sid", spanId)
                .param("parent", parentId)
                .param("name", name)
                .param("latency", latencyMs)
                .update();
    }

    private void insertLlmSpan(
            String projectId, String traceId, String spanId, String parentId, String model, boolean deleted) {
        jdbc.sql("INSERT INTO span (project_id, trace_id, id, parent_span_id, kind, name,"
                        + " provided_model_name, started_at, event_ts, is_deleted)"
                        + " VALUES (:pid, :tid, :sid, :parent, 'llm', 'chat', :model, now(), now(), :deleted)")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("sid", spanId)
                .param("parent", parentId)
                .param("model", model)
                .param("deleted", deleted)
                .update();
    }

    /**
     * Project scope through the real service: {@code findingEvidence} resolves the finding under the caller's project
     * first, so another tenant's id reads not-found, never a 403 that would confirm it exists. The repository is
     * asserted too, so a {@code page} or {@code countsByRole} missing its {@code project_id} predicate fails here.
     */
    @Test
    @DisplayName("another tenant's finding id reads as not-found, and its evidence does not page")
    void evidenceIsScopedToTheCallersProject() {
        Project owner = project("finding-evidence-owner");
        Project stranger = project("finding-evidence-stranger");
        String findingId = firing(owner, "gram-scope");
        evidence.record(
                owner.id(),
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.trace("trace-owned")),
                Instant.now().toString());

        // The positive control: without it every assertion below also passes on a finding never written.
        assertEquals(
                1L,
                behaviorDrift.findingEvidence(owner.id(), findingId).counts().get(FindingEvidenceRow.Role.MEMBER),
                "the owner reads its own evidence");

        TessaryException e =
                assertThrows(TessaryException.class, () -> behaviorDrift.findingEvidence(stranger.id(), findingId));
        assertEquals(ClassifierError.FINDING_NOT_FOUND, e.error(), "a cross-tenant id must not read as forbidden");

        assertTrue(
                evidence.page(stranger.id(), findingId, 100).rows().isEmpty(),
                "the page's own project predicate is what makes the service guard belt-and-braces");
        assertEquals(
                0L,
                evidence.countsByRole(stranger.id(), findingId).get(FindingEvidenceRow.Role.MEMBER),
                "a count that ignored the project would report the owner's population to a stranger");
    }

    /**
     * {@code ux_finding_live} excludes every ruled row, since a person's ruling uses the same columns as a machine's:
     * the next firing opens a fresh finding rather than mutating one a person already ruled on.
     */
    @Test
    @DisplayName("a human-ruled finding leaves the live arbiter, so a later firing opens a fresh one")
    void aHumanRuledFindingLeavesTheLiveArbiter() {
        Project p = project("finding-human-ruled-arm");
        String findingId = firing(p, "gram-blocked");
        findings.recordHumanRuling(
                p.id(),
                findingId,
                FindingRow.TriageVerdict.POSITIVE,
                "A person ruled this a real deviation.",
                Instant.now().toString());

        String second = firing(p, "gram-blocked");

        assertTrue(!second.equals(findingId), "the ruled row left the arbiter, so the firing opened a new row");
        FindingRow ruled = findings.findById(p.id(), findingId).orElseThrow();
        assertEquals(FindingRow.Status.OPEN, ruled.status(), "a positive ruling stays open");
        assertEquals(FindingRow.TriageVerdict.POSITIVE, ruled.triageVerdict(), "the human verdict survives");
        FindingRow fresh = findings.findById(p.id(), second).orElseThrow();
        assertEquals(FindingRow.Status.OPEN, fresh.status(), "the new row starts unruled, exactly like a first firing");
        assertTrue(fresh.triageVerdict() == null, "the new row starts unruled, exactly like a first firing");
    }

    private Project project(String name) {
        return TenantFixture.bootstrap(tenants, name).project();
    }

    /**
     * A span page resumes after its own last row, not the over-fetched one; an unreadable or other-generation cursor
     * restarts at page one; an aged-out span still reads with no start time. Secret-leak and malformed-output pages
     * join their own tables the same way.
     */
    @Test
    @DisplayName("the span page resumes after its own last row and restarts on a cursor it cannot read")
    void spanPageResumesAfterItsLastRowAndRestartsOnAnUnreadableCursor() {
        Project p = project("finding-span-page");
        String findingId = firing(p, "gram-span-page");
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        for (int i = 0; i < 3; i++) refs.add(FindingEvidenceRepository.Ref.span("trace-" + i, "span-" + i));
        evidence.record(
                p.id(),
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                refs,
                Instant.now().toString());

        for (boolean joined : new boolean[] {false, true}) {
            FindingEvidenceRepository.SpanPage first =
                    evidence.spanPage(p.id(), findingId, null, 2, null, joined, joined);
            FindingEvidenceRepository.SpanPage second =
                    evidence.spanPage(p.id(), findingId, null, 2, first.nextCursor(), joined, joined);

            assertEquals(List.of("trace-0", "trace-1"), traces(first));
            assertNotNull(first.nextCursor());
            assertEquals(List.of("trace-2"), traces(second));
            assertNull(second.nextCursor());
            assertNull(second.rows().get(0).startedAt(), "the span aged out; the ref still reads");
        }
        String sep = "\u001f";
        for (String token : List.of(
                "v1" + sep + "member" + sep + "first" + sep + "x",
                "v0" + sep + "member" + sep + "1" + sep + "x",
                "v1" + sep + "" + sep + "1" + sep + "x",
                "v1" + sep + "member" + sep + "1")) {
            assertEquals(
                    List.of("trace-0", "trace-1"),
                    traces(evidence.spanPage(p.id(), findingId, null, 2, PreviewCursor.encode(token, 0), false, false)),
                    "an unreadable cursor restarts at page one: " + token);
        }
    }

    /**
     * A cause key with quotes, backslashes and control characters is escaped into the payload; unescaped, the jsonb
     * cast rejects the write.
     */
    @Test
    void aCauseKeyWithCharactersJsonMustEscapeIsRecordedIntact() {
        Project p = project("finding-escaped-key");
        String nasty = "tool:\"quoted\" \\path\n\r\t\u0001end";
        String now = Instant.now().toString();

        FindingRepository.Recorded recorded = Objects.requireNonNull(findings.recordRecomputedRate(
                Ids.ulid(),
                p.id(),
                BuiltInDetector.Kind.TOOL_ERROR,
                "clf-escape:" + nasty,
                FindingRow.Cause.RATE_SHIFT,
                nasty,
                FindingRow.SubjectKind.TOOL,
                "search_docs",
                "search_docs",
                12,
                null,
                null,
                "{\"n_cur\":12}",
                now,
                now,
                now));

        assertEquals(
                nasty,
                findings.findById(p.id(), recorded.findingId()).orElseThrow().nativeCauseKey());
    }

    private static List<String> traces(FindingEvidenceRepository.SpanPage page) {
        return page.rows().stream()
                .map(FindingEvidenceRepository.SpanRef::traceId)
                .toList();
    }

    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    private String firing(Project p, String gram) {
        String now = Instant.now().toString();
        return Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        p.id(),
                        BuiltInDetector.Kind.REGEX,
                        "clf-" + gram,
                        gram,
                        1,
                        "cs-a",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
    }
}
