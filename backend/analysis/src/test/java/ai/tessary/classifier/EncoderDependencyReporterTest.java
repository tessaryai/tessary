// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link EncoderDependencyReporter} counts a project as depending on the encoder when its groundedness row
 * is enabled and its org holds the capability, and nothing else: whether the model answers right now is
 * not an input, because a model that is down is still depended on.
 */
@ExtendWith(MockitoExtension.class)
class EncoderDependencyReporterTest {

    @Mock
    CapabilityService capabilities;

    /** The active projects, in order. */
    private static ProjectRepository activeProjects(Project... active) {
        return new ProjectRepository(mock(JdbcClient.class)) {
            @Override
            public List<Project> findActive() {
                return List.of(active);
            }
        };
    }

    /** Each project's enabled classifier rows; a project not in the map has none. */
    private static ClassifierRepository enabledRows(Map<String, List<ClassifierRow>> byProject) {
        return new ClassifierRepository(mock(JdbcClient.class)) {
            @Override
            public List<ClassifierRow> listEnabled(String projectId) {
                return byProject.getOrDefault(projectId, List.of());
            }
        };
    }

    @Test
    void dependentProjectsAndTheirOrgsAreCountedApart() {
        // Three dependent projects in two orgs, plus a project whose only row is not encoder-backed.
        ProjectRepository projects =
                activeProjects(project("p1", "o1"), project("p2", "o1"), project("p3", "o2"), project("p4", "o2"));
        ClassifierRepository classifiers = enabledRows(Map.of(
                "p1", List.of(row("p1", Kind.GROUNDEDNESS, "groundedness")),
                "p2", List.of(row("p2", Kind.GROUNDEDNESS, "groundedness")),
                "p3", List.of(row("p3", Kind.GROUNDEDNESS, "groundedness")),
                "p4", List.of(row("p4", Kind.SECRET_LEAK, "secret_leak"))));
        when(capabilities.isEnabled("o1", Capability.GROUNDEDNESS)).thenReturn(true);
        when(capabilities.isEnabled("o2", Capability.GROUNDEDNESS)).thenReturn(true);

        EncoderDependencyReporter.Dependency d =
                new EncoderDependencyReporter(projects, classifiers, capabilities).report();

        assertEquals(new EncoderDependencyReporter.Dependency(3, 2, Set.of("groundedness")), d);
        assertFalse(d.decommissionable());
    }

    @Test
    void anOrgThatTurnedTheCapabilityOffDoesNotDepend() {
        ProjectRepository projects = activeProjects(project("p1", "o1"));
        ClassifierRepository classifiers =
                enabledRows(Map.of("p1", List.of(row("p1", Kind.GROUNDEDNESS, "groundedness"))));
        when(capabilities.isEnabled("o1", Capability.GROUNDEDNESS)).thenReturn(false);

        EncoderDependencyReporter.Dependency d =
                new EncoderDependencyReporter(projects, classifiers, capabilities).report();

        assertEquals(new EncoderDependencyReporter.Dependency(0, 0, Set.of()), d);
        assertTrue(d.decommissionable());
    }

    @Test
    void noEnabledGroundednessRowIsDecommissionable() {
        ProjectRepository projects = activeProjects(project("p1", "o1"));

        EncoderDependencyReporter.Dependency d =
                new EncoderDependencyReporter(projects, enabledRows(Map.of()), capabilities).report();

        assertEquals(new EncoderDependencyReporter.Dependency(0, 0, Set.of()), d);
        assertTrue(d.decommissionable());
    }

    /**
     * A count that fails on boot is logged and the context still starts: a diagnostic that could stop the
     * platform booting is worse than the question it answers. The daily run counts again.
     */
    @Test
    void aFailedCountNeverFailsBootAndTheDailyRunCountsAgain() {
        AtomicInteger scans = new AtomicInteger();
        ProjectRepository failsOnce = new ProjectRepository(mock(JdbcClient.class)) {
            @Override
            public List<Project> findActive() {
                if (scans.getAndIncrement() == 0) throw new IllegalStateException("database unavailable");
                return List.of();
            }
        };
        EncoderDependencyReporter reporter =
                new EncoderDependencyReporter(failsOnce, enabledRows(Map.of()), capabilities);

        assertDoesNotThrow(reporter::reportOnBoot);
        reporter.reportDaily();

        assertEquals(2, scans.get(), "the daily run scanned the projects again");
    }

    private static Project project(String id, String orgId) {
        return new Project(id, orgId, id, id, null, "2026-01-01T00:00:00Z", null, null, false, null);
    }

    private static ClassifierRow row(String projectId, String detector, String key) {
        return new ClassifierRow(
                "c-" + key,
                projectId,
                key,
                key,
                null,
                detector,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
    }
}
