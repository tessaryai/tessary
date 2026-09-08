// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.evals.classifier.ClassifierDtos.ClassifierHealthView;
import ai.tessary.evals.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.evals.classifier.substrate.SubstrateReadRepository;
import ai.tessary.evals.classifier.worker.ClassifierJobRepository;
import ai.tessary.evals.classifier.worker.ClassifierJobRow;
import ai.tessary.evals.config.ClassifierProperties;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * gh#545: {@link ClassifierService#health} must surface a failing sweep (status/attempts/last error)
 * without touching the DB, and must still report a healthy row for a signal that has never been
 * enqueued. All collaborators are mocked — this is the read-model logic, not the sweep itself
 * (covered by {@link ClassifierJobRepositoryTest} / {@code ClassifierWorkerIntegrationTest}).
 */
class ClassifierHealthTest {

    private static final String PID = "proj-1";

    private ClassifierRepository signals;
    private ClassifierJobRepository jobs;
    private ClassifierProperties props;
    private ClassifierService service;

    @BeforeEach
    void setup() {
        signals = mock(ClassifierRepository.class);
        jobs = mock(ClassifierJobRepository.class);
        props = new ClassifierProperties();
        props.setMaxAttempts(5);

        service = new ClassifierService(
                signals,
                mock(ClassifierDetectionRepository.class),
                jobs,
                mock(BuiltInClassifierCatalog.class),
                mock(SubstrateReadRepository.class),
                new ObjectMapper(),
                props,
                mock(CapabilityService.class),
                mock(ProjectRepository.class),
                mock(ai.tessary.evals.classifier.metric.MetricBaselineRepository.class));
    }

    private static ClassifierRow signal(String id) {
        return new ClassifierRow(
                id,
                PID,
                "sig-" + id,
                "Signal " + id,
                null,
                "regex",
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
    }

    @Test
    void neverEnqueuedSignal_reportsPendingWithNoFailureHistory() {
        when(signals.listByProject(PID)).thenReturn(List.of(signal("sig-a")));
        when(jobs.listByProject(PID)).thenReturn(List.of());

        List<ClassifierHealthView> health = service.health(PID);

        assertEquals(1, health.size());
        ClassifierHealthView v = health.get(0);
        assertEquals("sig-a", v.classifierId());
        assertEquals(ClassifierJobRow.PENDING, v.status());
        assertEquals(0, v.attempts());
        assertEquals(5, v.maxAttempts());
        assertNull(v.lastError());
        assertNull(v.lastSweptAt());
        assertNull(v.nextAttemptAt());
    }

    @Test
    void failingSweep_surfacesStatusAttemptsAndLastError() {
        when(signals.listByProject(PID)).thenReturn(List.of(signal("sig-b")));
        ClassifierJobRow failing = new ClassifierJobRow(
                "job-1",
                PID,
                "sig-b",
                ClassifierJobRow.FAILED,
                null,
                null,
                null,
                null,
                3,
                "classify: connection refused",
                "2026-01-01T00:00:00Z",
                "2026-01-05T12:00:00Z");
        when(jobs.listByProject(PID)).thenReturn(List.of(failing));

        ClassifierHealthView v = service.health(PID).get(0);

        assertEquals(ClassifierJobRow.FAILED, v.status());
        assertEquals(3, v.attempts());
        assertEquals("classify: connection refused", v.lastError());
        assertEquals("2026-01-05T12:00:00Z", v.lastSweptAt());
        assertNull(v.nextAttemptAt(), "no backoff stamp on main yet (gh#531) — null reads as 'next heartbeat'");
    }

    @Test
    void healthySweep_reportsDoneWithNoAlarmingState() {
        when(signals.listByProject(PID)).thenReturn(List.of(signal("sig-c")));
        ClassifierJobRow done = new ClassifierJobRow(
                "job-2",
                PID,
                "sig-c",
                ClassifierJobRow.DONE,
                "2026-01-05T00:00:00Z",
                "obs-9",
                null,
                null,
                0,
                null,
                "2026-01-01T00:00:00Z",
                "2026-01-05T00:00:00Z");
        when(jobs.listByProject(PID)).thenReturn(List.of(done));

        ClassifierHealthView v = service.health(PID).get(0);

        assertEquals(ClassifierJobRow.DONE, v.status());
        assertEquals(0, v.attempts());
        assertNull(v.lastError());
    }
}
