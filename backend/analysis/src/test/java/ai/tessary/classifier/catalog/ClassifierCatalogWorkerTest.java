// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
