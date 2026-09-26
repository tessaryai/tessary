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
import org.springframework.transaction.annotation.Transactional;

/**
 * What the {@link TriageSource} merge must not change, without a database. {@code FindingService.findings()} iterates
 * adapters, and a dropped or reordered filter, a limit applied once instead of per source, or an order by bean name
 * instead of {@code @Order} is invisible to the compiler.
 */
class FindingServiceMergeTest {

    private static final String PROJECT = "prj_1";

    // The merged order is the injected list's.

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

    // The filter still excludes what it did.

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

    /**
     * No repositories: every method here routes through the sources first, which is the property ({@code
     * FindingService} knows no table).
     */
    @SuppressWarnings("NullAway") // the repositories are unreachable here
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

    /** A hand-written adapter reads better than a mock script. */
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
            // the recording half is the integration suite's
            assertNotNull(Instant.parse(now));
        }
    }
}
