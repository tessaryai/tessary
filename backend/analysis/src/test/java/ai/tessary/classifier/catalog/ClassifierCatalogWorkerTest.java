// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

@ExtendWith(MockitoExtension.class)
class ClassifierCatalogWorkerTest {

    @Mock
    ClassifierService classifiers;

    /**
     * A project scan that fails ends the tick quietly and resyncs nothing: the exception stays out of the
     * scheduler thread, and the next tick scans again.
     */
    @Test
    void aFailedProjectScanEndsTheTickWithoutResyncingAnything() {
        ProjectRepository unreadable = new ProjectRepository(mock(JdbcClient.class)) {
            @Override
            public List<Project> findActive() {
                throw new IllegalStateException("database unavailable");
            }
        };

        assertDoesNotThrow(
                () -> new ClassifierCatalogWorker(classifiers, unreadable, new TraceMdcBridge(Tracer.NOOP)).tick());
        verifyNoInteractions(classifiers);
    }

    /**
     * One project whose resync throws is skipped, not fatal: the projects after it in the same tick are
     * still reconciled, rather than every later project waiting on one broken catalog.
     */
    @Test
    void aProjectWhoseResyncFailsDoesNotStopTheNextProject() {
        Project broken = project("p-broken");
        Project healthy = project("p-healthy");
        ProjectRepository two = new ProjectRepository(mock(JdbcClient.class)) {
            @Override
            public List<Project> findActive() {
                return List.of(broken, healthy);
            }
        };
        when(classifiers.resyncBuiltIns(broken)).thenThrow(new IllegalStateException("catalog row locked"));
        when(classifiers.resyncBuiltIns(healthy)).thenReturn(2);

        assertDoesNotThrow(() -> new ClassifierCatalogWorker(classifiers, two, new TraceMdcBridge(Tracer.NOOP)).tick());
        verify(classifiers).resyncBuiltIns(healthy);
    }

    private static Project project(String id) {
        return new Project(id, "org-1", id, id, null, "2026-09-01T00:00:00Z", null, null, false, null);
    }
}
