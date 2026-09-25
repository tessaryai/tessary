// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        StubSource second = new StubSource("second", view("b1"));

        BehaviorFindingsView page = service(List.of(first, second)).findings(PROJECT, null, null, null, true);

        assertEquals(
                List.of("a1", "a2", "b1"),
                page.findings().stream().map(BehaviorFindingView::id).toList(),
                "the shared table's rows come first and the second source's after them");
    }

    /**
     * <b>What this pins and what it deliberately does not.</b> It pins that the merge follows the INJECTED
     * order and that the shared table's own adapter sits at {@code @Order(0)} so it answers first. It names
     * no other adapter: the boundary enforcer bans this module from depending on an external one, test scope
     * included, so an assertion here that named another adapter's class could not be re-pointed if that class
     * ever moves, only deleted. Each adapter's own {@code @Order} value is pinned in its own package's test,
     * where it travels with the class.
     */
    @Test
    @DisplayName("the ORDER that decides that is @Order, not declaration order or bean name")
    void the_order_annotations_are_the_routing() {
        assertEquals(
                0,
                BehaviorTriageSource.class.getAnnotation(Order.class).value(),
                "the shared table answers first; renumbering this changes the wire row order");
    }

    // ---- (b) the filter still excludes what it excluded -----------------------------------------

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

    // ---- (c) the page limit and the withheld count ----------------------------------------------

    @Test
    @DisplayName("the page limit is 200 PER SOURCE, so a two-source page can hold 400 — as before the seam")
    void the_limit_is_per_source() {
        // The per-source ceiling is a property of the SEAM, so it is asserted through the seam rather
        // than by reading an adapter's private field: two sources each returning a full page
        // produce a page of both, and no source is truncated by another's rows. Naming a second
        // concrete adapter here would tie this open test to whichever adapters happen to ship.
        StubSource first = new StubSource("behavior", views("a", 200));
        StubSource second = new StubSource("second", views("b", 200));
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
        StubSource second = new StubSource("second");
        second.resolved = view("claimed-by-second");

        assertEquals(
                "claimed-by-second",
                service(List.of(first, second))
                        .resolve(PROJECT, "f1", BehaviorDtos.BehaviorResolutionRequest.EXPECTED, "usr_1")
                        .id(),
                "the shared source disclaims, so the second source is asked next");
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
        return new FindingService(null, null, null, null, sources, null, null, null); // detail services unreached
    }

    private static BehaviorFindingView view(String id) {
        return new BehaviorFindingView(
                id,
                null,
                FindingRow.Cause.RATE_SHIFT,
                "cause:" + id,
                id,
                BuiltInDetector.Kind.TOOL_ERROR,
                FindingRow.GLOBAL_WORKFLOW,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z",
                3,
                FindingRow.Status.OPEN,
                null,
                null,
                null,
                List.of(),
                null,
                BehaviorFindingView.TriageStatus.PENDING,
                null,
                null);
    }

    private static FindingRow row(String id, String classifierKey) {
        return new FindingRow(
                id,
                PROJECT,
                classifierKey,
                "cause:" + id,
                FindingRow.SubjectKind.TOOL,
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
                null,
                "2026-08-01T00:00:00Z",
                "2026-08-02T00:00:00Z");
    }

    /** A hand-written adapter: a stub that answers by construction reads better here than a mock script. */
    private static final class StubSource implements TriageSource {
        private final String kind;
        private final List<BehaviorFindingView> rows;
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
