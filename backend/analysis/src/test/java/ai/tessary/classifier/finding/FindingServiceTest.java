// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the {@link TriageSource} merge must not change, without a database. {@code FindingService.findings()} iterates
 * adapters, and a dropped or reordered filter, a limit applied once instead of per source, or an order by bean name
 * instead of {@code @Order} is invisible to the compiler.
 *
 * <p>An absent adapter: a classpath with no triage source, or none owning an id, degrades to the no-data view rather
 * than throwing.
 */
class FindingServiceTest {

    private static final String PROJECT = "prj_1";

    // The merged order is the injected list's.

    @Test
    @DisplayName("the page is the sources concatenated in injected order, first source first")
    void merged_page_follows_source_order() {
        FakeTriageSource first = new FakeTriageSource("behavior", view("a1"), view("a2"));
        FakeTriageSource second = new FakeTriageSource("second", view("b1"));

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

    @Test
    @DisplayName("no TriageSource owns the id: detail, analyze and resolve all 404 rather than 500")
    void unclaimed_ids_are_not_found() {
        FindingService service = service(List.of(new FakeTriageSource("behavior")));
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(TessaryException.class, () -> service.finding(PROJECT, "f1"))
                        .error());
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(TessaryException.class, () -> service.analyze(PROJECT, "f1", null))
                        .error());
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(
                                TessaryException.class,
                                () -> service.resolve(
                                        PROJECT, "f1", BehaviorDtos.BehaviorResolutionRequest.EXPECTED, null))
                        .error());
    }

    @Test
    @DisplayName("an unrecognised resolution verb is rejected before any source is consulted")
    void the_verb_is_validated_at_the_entry_point() {
        assertEquals(
                ClassifierError.INVALID_RESOLUTION,
                assertThrows(TessaryException.class, () -> service(List.of()).resolve(PROJECT, "f1", "maybe", null))
                        .error());
    }

    /**
     * No repositories: every method here routes through the sources first, which is the property ({@code
     * FindingService} knows no table). Unreached collaborators are null, so a path touching one fails loudly.
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
        return FindingRowBuilder.of(classifierKey)
                .id(id)
                .projectId(PROJECT)
                .causeKey("cause:" + id)
                .subject(FindingRow.SubjectKind.TOOL, "sub_1")
                .dated("2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z")
                .sampleCount(3)
                .build();
    }
}
