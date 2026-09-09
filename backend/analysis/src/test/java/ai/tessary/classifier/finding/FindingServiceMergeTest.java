// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the {@link TriageSource} merge must not change, held without a database.
 *
 * <p>{@code FindingService.findings()}, the busiest read path in the product, iterates over adapters
 * rather than knowing both tables itself. Everything that could plausibly get wrong in that iteration
 * is invisible to the compiler and to the boundary enforcer: a filter dropped, two filters reordered, a
 * page limit applied once instead of per source, a row order decided by bean name instead of by
 * {@code @Order}. The Testcontainers suite would catch some of it and does not run on every machine, so
 * the properties are pinned here instead, plain JUnit, hand-written stubs, no Spring, no Docker.
 */
class FindingServiceMergeTest {

    private static final String PROJECT = "prj_1";

    // ---- (a) the merged row order is the injected list's order ----------------------------------

    @Test
    @DisplayName("the page is the sources concatenated in injected order, first source first")
    void merged_page_follows_source_order() {
        StubSource first = new StubSource("behavior", view("a1"), view("a2"));
        StubSource second = new StubSource(BuiltInDetector.Kind.SOP_CONFORMANCE, view("b1"));

        BehaviorFindingsView page = service(List.of(first, second)).findings(PROJECT, null, null, null, true);

        assertEquals(
                List.of("a1", "a2", "b1"),
                page.findings().stream().map(BehaviorFindingView::id).toList(),
                "the shared table's rows come first and conformance's after them");
    }

    /**
     * <b>What this pins and what it deliberately does not.</b> It pins that the merge follows the
     * INJECTED order and that the shared table's own adapter sits at {@code @Order(0)} so it answers
     * first. It no longer names conformance's adapter and asserts {@code @Order(10)} on it: the
     * boundary enforcer bans this module from depending on an external one, test scope included, so an
     * assertion here that names a conformance class could not be re-pointed if that class ever moves,
     * only deleted. Each adapter's own {@code @Order} value is pinned in its own package's test, where
     * it travels with the class.
     */
    @Test
    @DisplayName("the ORDER that decides that is @Order, not declaration order or bean name")
    void the_order_annotations_are_the_routing() {
        assertEquals(
                0,
                BehaviorTriageSource.class.getAnnotation(Order.class).value(),
                "the shared table answers first; renumbering this changes the wire row order");
    }

    // ---- (b) the three filters still exclude what they excluded ---------------------------------

    @Test
    @DisplayName("the flag layer's filter drops a withheld classifier's rows")
    void flag_layer_filter_still_excludes() {
        List<FindingRow> rows = List.of(row("f1", BuiltInDetector.Kind.TOOL_ERROR), row("f2", "cost_drift"));

        assertEquals(
                List.of("f2"),
                FindingFilters.visible(rows, Set.of(BuiltInDetector.Kind.TOOL_ERROR)).stream()
                        .map(FindingRow::id)
                        .toList(),
                "a finding belongs to the classifier that wrote it, so a withheld classifier hides its leads");
    }

    @Test
    @DisplayName("the SOP de-dup filter drops shared-table conformance rows, which have their own projection")
    void sop_dedup_filter_still_excludes() {
        List<FindingRow> rows =
                List.of(row("f1", BuiltInDetector.Kind.SOP_CONFORMANCE), row("f2", BuiltInDetector.Kind.TOOL_ERROR));

        assertEquals(
                List.of("f2"),
                FindingFilters.visible(rows, Set.of()).stream()
                        .map(FindingRow::id)
                        .toList(),
                "without this every SOP finding appears twice, once stripped of its `kind` field");
    }

    @Test
    @DisplayName("the two filters run in that relative order, and both apply to one row")
    void both_filters_apply_together() {
        List<FindingRow> rows = List.of(
                row("withheld", BuiltInDetector.Kind.TOOL_ERROR),
                row("sop", BuiltInDetector.Kind.SOP_CONFORMANCE),
                row("kept", "cost_drift"));

        assertEquals(
                List.of("kept"),
                FindingFilters.visible(rows, Set.of(BuiltInDetector.Kind.TOOL_ERROR)).stream()
                        .map(FindingRow::id)
                        .toList());
    }

    @Test
    @DisplayName("conformanceApplies: no call-site narrowing, own rail or unfiltered, and not withheld")
    void conformance_predicate_still_excludes() {
        assertTrue(FindingFilters.conformanceApplies(null, null, Set.of()), "the unfiltered page includes it");
        assertTrue(
                FindingFilters.conformanceApplies(null, BuiltInDetector.Kind.SOP_CONFORMANCE, Set.of()),
                "so does its own detector rail");
        assertFalse(
                FindingFilters.conformanceApplies("cs_1", null, Set.of()),
                "an SOP rule is conversation-scoped, so any call-site narrowing excludes it");
        assertFalse(
                FindingFilters.conformanceApplies(null, BuiltInDetector.Kind.TOOL_ERROR, Set.of()),
                "another classifier's rail excludes it");
        assertFalse(
                FindingFilters.conformanceApplies(null, null, Set.of(BuiltInDetector.Kind.SOP_CONFORMANCE)),
                "the flag layer withholds it exactly as it withholds any other classifier");
    }

    // ---- (c) the page limit and the withheld count ----------------------------------------------

    @Test
    @DisplayName("the page limit is 200 PER SOURCE, so a two-source page can hold 400 — as before the seam")
    void the_limit_is_per_source() throws Exception {
        var field = BehaviorTriageSource.class.getDeclaredField("DEFAULT_FINDING_LIMIT");
        field.setAccessible(true);
        assertEquals(200, field.getInt(null), "the shared table's own ceiling, unchanged by the split");

        // The per-source ceiling is a property of the SEAM, so it is asserted through the seam rather
        // than by reading a second adapter's private field: two sources each returning a full page
        // produce a page of both, and no source is truncated by another's rows. Naming a second
        // concrete adapter here would tie this open test to whichever adapters happen to ship.
        StubSource first = new StubSource("behavior", views("a", 200));
        StubSource second = new StubSource(BuiltInDetector.Kind.SOP_CONFORMANCE, views("b", 200));
        assertEquals(
                400,
                service(List.of(first, second))
                        .findings(PROJECT, null, null, null, true)
                        .findings()
                        .size(),
                "the limit is per source, so N sources can fill N pages");
    }

    /** {@code count} rows with distinct ids, for the per-source ceiling above. */
    private static BehaviorFindingView[] views(String prefix, int count) {
        BehaviorFindingView[] out = new BehaviorFindingView[count];
        for (int i = 0; i < count; i++) out[i] = view(prefix + i);
        return out;
    }

    @Test
    @DisplayName("countWithheld is summed across sources and stays gated on confirmedOnly")
    void withheld_count_is_summed_and_gated() {
        StubSource first = new StubSource("behavior");
        first.withheld = 7;
        StubSource second = new StubSource(BuiltInDetector.Kind.SOP_CONFORMANCE);
        second.withheld = 0; // the conformance source has no such state and answers zero

        FindingService service = service(List.of(first, second));
        assertEquals(7, service.findings(PROJECT, null, null, null, true).withheld());

        first.confirmedOnlyGate = true;
        assertEquals(
                0,
                service.findings(PROJECT, null, null, null, false).withheld(),
                "confirmedOnly=false is the raw Layer-1 stream and reports no gate count");
    }

    // ---- (d) the transaction boundary -----------------------------------------------------------

    @Test
    @DisplayName("@Transactional stays on the ENTRY point, not on the adapters")
    void resolve_is_transactional_at_the_entry_point() throws Exception {
        Method resolve =
                FindingService.class.getMethod("resolve", String.class, String.class, String.class, String.class);
        assertNotNull(
                resolve.getAnnotation(Transactional.class),
                "the allowlist row, the gram state, the status and the changelog are one judgement; "
                        + "annotating the adapter instead leaves the happy path working and nothing failing "
                        + "until a mid-write error resolves a finding with no allowlist row behind it");
    }

    @Test
    @DisplayName("resolve routes to the first source that claims the id, and 404s when none do")
    void resolve_routes_by_ownership() {
        StubSource first = new StubSource("behavior");
        StubSource second = new StubSource(BuiltInDetector.Kind.SOP_CONFORMANCE);
        second.resolved = view("claimed-by-conformance");

        assertEquals(
                "claimed-by-conformance",
                service(List.of(first, second))
                        .resolve(PROJECT, "f1", BehaviorDtos.BehaviorResolutionRequest.EXPECTED, "usr_1")
                        .id(),
                "the shared source disclaims, so conformance is asked next");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /**
     * A service with no repositories. Every method exercised here routes through the source list before
     * it can reach one, which is precisely the property under test: {@code FindingService} knows no
     * table. A constructor that could not take these nulls would mean the routing had leaked back into
     * the service.
     */
    @SuppressWarnings("NullAway") // deliberate: the injected repositories are unreachable on these paths
    private static FindingService service(List<TriageSource> sources) {
        return new FindingService(null, null, null, null, sources);
    }

    private static BehaviorFindingView view(String id) {
        return new BehaviorFindingView(
                id,
                null,
                FindingRow.Cause.NOVELTY,
                "cause:" + id,
                id,
                BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                FindingRow.GLOBAL_WORKFLOW,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                3,
                List.of(),
                FindingRow.Status.OPEN,
                null,
                null,
                null,
                List.of(),
                null,
                BehaviorFindingView.TriageStatus.PENDING,
                null,
                0,
                null);
    }

    private static FindingRow row(String id, String classifierKey) {
        return new FindingRow(
                id,
                PROJECT,
                classifierKey,
                "cause:" + id,
                FindingRow.SubjectKind.BEHAVIOR_PROFILE,
                "sub_1",
                null,
                null,
                FindingRow.Status.OPEN,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                null,
                null,
                null,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z");
    }

    /** A hand-written adapter: a stub that answers by construction reads better here than a mock script. */
    private static final class StubSource implements TriageSource {
        private final String kind;
        private final List<BehaviorFindingView> rows;
        private long withheld;
        private boolean confirmedOnlyGate;
        private @Nullable BehaviorFindingView resolved;

        StubSource(String kind, BehaviorFindingView... rows) {
            this.kind = kind;
            this.rows = new ArrayList<>(List.of(rows));
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public List<Escalatable> listAutoEscalatable(String projectId, long minObservations, int limit) {
            return List.of();
        }

        @Override
        public Optional<BehaviorAnalysisView> analyze(String projectId, String findingId) {
            return Optional.empty();
        }

        @Override
        public List<BehaviorFindingView> list(
                String projectId,
                @Nullable String status,
                @Nullable String callSiteId,
                @Nullable String detector,
                boolean confirmedOnly) {
            return List.copyOf(rows);
        }

        @Override
        public long countWithheld(String projectId, @Nullable String callSiteId, boolean confirmedOnly) {
            return confirmedOnlyGate && !confirmedOnly ? 0 : withheld;
        }

        @Override
        public Optional<BehaviorFindingDetailView> detail(String projectId, String findingId) {
            return Optional.empty();
        }

        @Override
        public Optional<BehaviorFindingView> resolve(
                String projectId, String findingId, String action, @Nullable String userId) {
            return Optional.ofNullable(resolved);
        }

        @Override
        public Optional<TriageBrief> brief(BehaviorTriageJobRow job) {
            return Optional.empty();
        }

        @Override
        public void recordVerdict(
                String projectId,
                String findingId,
                BehaviorTriageVerdict verdict,
                @Nullable String citationsJson,
                String now) {
            // no-op: the recording half is exercised by the integration suite, not here
            assertNotNull(Instant.parse(now));
        }
    }
}
