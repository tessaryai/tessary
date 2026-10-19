// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboard;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardCreateRequest;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardList;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardSummary;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardUpdateRequest;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class MetricDashboardServiceTest {

    private static final String PID = "proj-1";
    private static final String STAMP = "2026-10-01T09:00:00Z";
    private static final String NOW = "2026-10-19T08:30:00Z";
    private static final MetricDashboardDefinition ONE_TILE = MetricDashboardValidatorTest.def(
            MetricDashboardValidatorTest.W.number("spend").build());
    private static final MetricDashboardDefinition TWO_TILES = MetricDashboardValidatorTest.def(
            MetricDashboardValidatorTest.W.number("a").build(),
            MetricDashboardValidatorTest.W.number("b").at(3, 0, 3, 2).build());

    private final MetricDashboardRepository repo = mock(MetricDashboardRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MetricDashboardService service =
            new MetricDashboardService(repo, mapper, Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC));

    /** The bug: the built-in missing from the switcher, listed after a copy, or a widget count read as zero. */
    @Test
    @DisplayName("list puts the default first, then each stored dashboard with its widget count")
    void listPutsTheDefaultFirstAndCountsWidgets() {
        MetricDashboardRow alpha = row("01A", "Alpha", TWO_TILES);
        MetricDashboardRow beta = row("01B", "Beta", MetricDashboardDefinition.empty());
        when(repo.list(PID)).thenReturn(List.of(alpha, beta));

        MetricDashboardList list = service.list(PID, true);

        assertEquals(
                List.of(
                        DefaultDashboard.summary(),
                        new MetricDashboardSummary("01A", "Alpha", "about Alpha", false, 2, STAMP),
                        new MetricDashboardSummary("01B", "Beta", "about Beta", false, 0, STAMP)),
                list.dashboards());
        assertTrue(list.canManage());
        assertFalse(service.list(PID, false).canManage());
    }

    /** The bug: the built-in looked up in the table (and 404), or an unknown id answered with something else. */
    @Test
    @DisplayName("get of default is the constant without a read, a stored id reads its row, an unknown id is 404")
    void getDefaultIsTheConstantAndAnUnknownIdIs404() {
        assertEquals(DefaultDashboard.view(false), service.get(PID, "default", false));
        verifyNoInteractions(repo);

        MetricDashboardRow stored = row("01A", "Alpha", ONE_TILE);
        when(repo.find(PID, "01A")).thenReturn(Optional.of(stored));
        assertEquals(
                new MetricDashboard("01A", "Alpha", "about Alpha", false, ONE_TILE, "user-0", STAMP, STAMP, true),
                service.get(PID, "01A", true));

        when(repo.find(PID, "nope")).thenReturn(Optional.empty());
        assertCode(
                MetricsError.DASHBOARD_NOT_FOUND,
                "No dashboard 'nope' for this project",
                () -> service.get(PID, "nope", true));
    }

    /** The bug: clone-first saves drop the user's edit and store the untouched source instead. */
    @Test
    @DisplayName("create stores the given definition even when clone_of names a source")
    void createUsesTheGivenDefinitionOverTheCloneSource() {
        MetricDashboard made = service.create(
                PID, "user-1", new MetricDashboardCreateRequest("Default (copy)", "mine", ONE_TILE, "default"), true);

        MetricDashboardRow inserted = inserted();
        assertEquals(ONE_TILE, parse(inserted.definitionJson()));
        assertEquals(26, inserted.id().length());
        assertEquals(PID, inserted.projectId());
        assertEquals("Default (copy)", inserted.name());
        assertEquals("mine", inserted.description());
        assertEquals("user-1", inserted.createdBy());
        assertEquals(NOW, inserted.createdAt());
        assertEquals(NOW, inserted.updatedAt());
        verify(repo, never()).find(any(), any());

        assertEquals(
                new MetricDashboard(inserted.id(), "Default (copy)", "mine", false, ONE_TILE, "user-1", NOW, NOW, true),
                made);
    }

    /** The bug: "Make a copy" of the built-in starting empty instead of with its nine widgets. */
    @Test
    @DisplayName("create with clone_of default and no definition copies the default's definition")
    void createClonesDefaultWhenNoDefinitionIsGiven() {
        service.create(PID, "user-1", new MetricDashboardCreateRequest("Copy", null, null, "default"), false);
        assertEquals(DefaultDashboard.DEFINITION, parse(inserted().definitionJson()));
    }

    /** The bug: copying a saved dashboard reads the wrong row, or a missing source silently makes an empty one. */
    @Test
    @DisplayName("create clones a stored dashboard's definition, and an unknown clone_of is 404")
    void createClonesAStoredDashboardAndAnUnknownCloneIs404() {
        when(repo.find(PID, "01A")).thenReturn(Optional.of(row("01A", "Alpha", TWO_TILES)));
        service.create(PID, "user-1", new MetricDashboardCreateRequest("Alpha 2", null, null, "01A"), true);
        assertEquals(TWO_TILES, parse(inserted().definitionJson()));

        when(repo.find(PID, "ghost")).thenReturn(Optional.empty());
        assertCode(
                MetricsError.DASHBOARD_NOT_FOUND,
                "No dashboard 'ghost' for this project",
                () -> service.create(PID, "user-1", new MetricDashboardCreateRequest("X", null, null, "ghost"), true));
        verify(repo).insert(any());
    }

    /** The bug: "New dashboard" with no body fails on a null definition or a null description column. */
    @Test
    @DisplayName("create without a definition or clone_of stores an empty definition and an empty description")
    void createWithoutDefinitionOrCloneStartsEmpty() {
        service.create(PID, "user-1", new MetricDashboardCreateRequest("Fresh", null, null, null), true);
        MetricDashboardRow inserted = inserted();
        assertEquals(MetricDashboardDefinition.empty(), parse(inserted.definitionJson()));
        assertEquals("", inserted.description());
    }

    /** The bug: the per-project cap not enforced, or enforced one early so the twentieth dashboard is refused. */
    @Test
    @DisplayName("the twentieth dashboard is stored and the twenty-first is refused")
    void theTwentyFirstDashboardRefuses() {
        when(repo.count(PID)).thenReturn(19);
        assertDoesNotThrow(
                () -> service.create(PID, "u", new MetricDashboardCreateRequest("N20", null, null, null), true));

        when(repo.count(PID)).thenReturn(20);
        assertCode(
                MetricsError.TOO_MANY_DASHBOARDS,
                "A project holds at most 20 dashboards",
                () -> service.create(PID, "u", new MetricDashboardCreateRequest("N21", null, null, null), true));
        verify(repo).insert(any());
    }

    /** The bug: the unique-index violation escapes as a 500 instead of a 409 that names the clashing name. */
    @Test
    @DisplayName("a duplicate name on create or update is 409 DUPLICATE_NAME")
    void aDuplicateNameOnCreateOrUpdateIs409() {
        doThrow(new DuplicateKeyException("ux_metric_dashboard_name"))
                .when(repo)
                .insert(any());
        assertCode(
                MetricsError.DUPLICATE_NAME,
                "A dashboard named 'Ops' already exists in this project",
                () -> service.create(PID, "u", new MetricDashboardCreateRequest("Ops", null, null, null), true));

        when(repo.find(PID, "01A")).thenReturn(Optional.of(row("01A", "Alpha", ONE_TILE)));
        doThrow(new DuplicateKeyException("ux_metric_dashboard_name"))
                .when(repo)
                .update(any());
        assertCode(
                MetricsError.DUPLICATE_NAME,
                "A dashboard named 'Beta' already exists in this project",
                () -> service.update(PID, "01A", new MetricDashboardUpdateRequest("Beta", null, ONE_TILE), true));

        doThrow(new DuplicateKeyException("ux_metric_dashboard_name"))
                .when(repo)
                .rename(any(), any(), any(), any(), any());
        assertCode(
                MetricsError.DUPLICATE_NAME,
                "A dashboard named 'Gamma' already exists in this project",
                () -> service.update(PID, "01A", new MetricDashboardUpdateRequest("Gamma", null, null), true));
    }

    /** The bug: a stored dashboard named "Default" sits next to the built-in and the switcher shows two pills alike. */
    @Test
    @DisplayName("the built-in's name is taken on create and update, trimmed")
    void theBuiltInNameIsTakenOnCreateAndUpdate() {
        assertCode(
                MetricsError.DUPLICATE_NAME,
                "A dashboard named 'Default' already exists in this project",
                () -> service.create(PID, "u", new MetricDashboardCreateRequest("Default", null, null, null), true));

        when(repo.find(PID, "01A")).thenReturn(Optional.of(row("01A", "Alpha", ONE_TILE)));
        assertCode(
                MetricsError.DUPLICATE_NAME,
                "A dashboard named ' Default ' already exists in this project",
                () -> service.update(PID, "01A", new MetricDashboardUpdateRequest(" Default ", null, ONE_TILE), true));
        verify(repo, never()).insert(any());
        verify(repo, never()).update(any());
    }

    /** The bug: the built-in edited or deleted (it has no row), or a missing id answered 200. */
    @Test
    @DisplayName("update and delete of default are 409, and of an unknown id are 404")
    void updateAndDeleteOfDefaultAre409AndOfAnUnknownIdAre404() {
        String readOnly = "The default dashboard is built in; make a copy to edit it";
        assertCode(
                MetricsError.BUILT_IN_READ_ONLY,
                readOnly,
                () -> service.update(PID, "default", new MetricDashboardUpdateRequest("D", null, ONE_TILE), true));
        assertCode(MetricsError.BUILT_IN_READ_ONLY, readOnly, () -> service.delete(PID, "default"));
        verifyNoInteractions(repo);

        when(repo.find(PID, "nope")).thenReturn(Optional.empty());
        assertCode(
                MetricsError.DASHBOARD_NOT_FOUND,
                "No dashboard 'nope' for this project",
                () -> service.update(PID, "nope", new MetricDashboardUpdateRequest("D", null, ONE_TILE), true));
        when(repo.delete(PID, "nope")).thenReturn(false);
        assertCode(
                MetricsError.DASHBOARD_NOT_FOUND,
                "No dashboard 'nope' for this project",
                () -> service.delete(PID, "nope"));

        when(repo.find(PID, "01B")).thenReturn(Optional.of(row("01B", "Beta", ONE_TILE)));
        when(repo.rename(PID, "01B", "Gone", "", NOW)).thenReturn(Optional.empty());
        assertCode(
                MetricsError.DASHBOARD_NOT_FOUND,
                "No dashboard '01B' for this project",
                () -> service.update(PID, "01B", new MetricDashboardUpdateRequest("Gone", null, null), true));

        when(repo.delete(PID, "01A")).thenReturn(true);
        assertDoesNotThrow(() -> service.delete(PID, "01A"));
        verify(repo, never()).update(any());
    }

    /** The bug: an update that loses the author or creation time, keeps the old clock, or binds a null description. */
    @Test
    @DisplayName("update writes the new name, description, definition and updated_at, and keeps the author")
    void updateWritesNameDescriptionDefinitionAndUpdatedAt() {
        when(repo.find(PID, "01A")).thenReturn(Optional.of(row("01A", "Alpha", ONE_TILE)));

        MetricDashboard updated =
                service.update(PID, "01A", new MetricDashboardUpdateRequest("Renamed", null, TWO_TILES), false);

        ArgumentCaptor<MetricDashboardRow> written = ArgumentCaptor.forClass(MetricDashboardRow.class);
        verify(repo).update(written.capture());
        MetricDashboardRow w = written.getValue();
        assertEquals("01A", w.id());
        assertEquals(PID, w.projectId());
        assertEquals("Renamed", w.name());
        assertEquals("", w.description());
        assertEquals(TWO_TILES, parse(w.definitionJson()));
        assertEquals("user-0", w.createdBy());
        assertEquals(STAMP, w.createdAt());
        assertEquals(NOW, w.updatedAt());

        assertEquals(new MetricDashboard("01A", "Renamed", "", false, TWO_TILES, "user-0", STAMP, NOW, false), updated);

        MetricDashboardDefinition overlapping = MetricDashboardValidatorTest.def(
                MetricDashboardValidatorTest.W.number("a").build(),
                MetricDashboardValidatorTest.W.number("b").build());
        assertCode(
                MetricsError.INVALID_WIDGET,
                "Widget 'b': overlaps widget 'a'",
                () -> service.update(PID, "01A", new MetricDashboardUpdateRequest("R", null, overlapping), true));
        verify(repo).update(any());
    }

    /** The bug: a rename writes the definition its tab cached, over widgets another tab saved since. */
    @Test
    @DisplayName("an update without a definition writes name and description and answers the stored definition")
    void anUpdateWithoutADefinitionKeepsTheStoredDefinition() {
        when(repo.find(PID, "01A")).thenReturn(Optional.of(row("01A", "Alpha", ONE_TILE)));
        MetricDashboardRow savedElsewhere = new MetricDashboardRow(
                "01A",
                PID,
                "Renamed",
                "new words",
                mapper.valueToTree(TWO_TILES).toString(),
                "user-0",
                STAMP,
                NOW);
        when(repo.rename(PID, "01A", "Renamed", "new words", NOW)).thenReturn(Optional.of(savedElsewhere));

        MetricDashboard renamed =
                service.update(PID, "01A", new MetricDashboardUpdateRequest("Renamed", "new words", null), true);

        assertEquals(
                new MetricDashboard("01A", "Renamed", "new words", false, TWO_TILES, "user-0", STAMP, NOW, true),
                renamed);
        verify(repo, never()).update(any());
    }

    /** The bug: stored JSON in a shape the strict mapper cannot read back, which breaks every later read. */
    @Test
    @DisplayName("a stored definition reads back through the strict mapper unchanged")
    void storedJsonRoundTripsThroughTheStrictMapper() {
        MetricDashboardDefinition withNullFilters = MetricDashboardValidatorTest.def(MetricDashboardValidatorTest.W
                .series("s")
                .filters(null)
                .title(null)
                .build());
        for (MetricDashboardDefinition def : List.of(DefaultDashboard.DEFINITION, withNullFilters)) {
            service.create(PID, "u", new MetricDashboardCreateRequest("R", null, def, null), true);
            MetricDashboardRow stored = lastInserted();
            when(repo.find(PID, stored.id())).thenReturn(Optional.of(stored));
            assertEquals(def, service.get(PID, stored.id(), true).definition());
        }
    }

    private MetricDashboardRow inserted() {
        ArgumentCaptor<MetricDashboardRow> captor = ArgumentCaptor.forClass(MetricDashboardRow.class);
        verify(repo).insert(captor.capture());
        return captor.getValue();
    }

    private MetricDashboardRow lastInserted() {
        ArgumentCaptor<MetricDashboardRow> captor = ArgumentCaptor.forClass(MetricDashboardRow.class);
        verify(repo, atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }

    private MetricDashboardDefinition parse(String json) {
        try {
            return mapper.readValue(json, MetricDashboardDefinition.class);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private MetricDashboardRow row(String id, String name, MetricDashboardDefinition def) {
        return new MetricDashboardRow(
                id, PID, name, "about " + name, mapper.valueToTree(def).toString(), "user-0", STAMP, STAMP);
    }

    private static void assertCode(ErrorCode code, String message, Executable call) {
        TessaryException ex = assertThrows(TessaryException.class, call);
        assertSame(code, ex.error());
        assertEquals(message, ex.getMessage());
    }
}
