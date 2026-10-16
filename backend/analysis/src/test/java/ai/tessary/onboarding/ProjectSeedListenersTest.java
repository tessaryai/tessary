// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.alert.AlertService;
import ai.tessary.alert.CaseAlertSeedListener;
import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.ClassifierSeedListener;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.open.media.MediaStore;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.storage.MediaRefRepository;
import ai.tessary.storage.RetrievedDocRepository;
import ai.tessary.storage.SessionRepository;
import ai.tessary.tenant.ProjectCreatedEvent;
import ai.tessary.tenant.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The default case-opened rule, the built-in classifiers and the sample project's demo data are seeded after the
 * project commits. With no surrounding transaction each listener runs inline inside project creation, so a seed
 * failure that escaped would fail the create of a project that already exists; it has to be swallowed, since the
 * heartbeat can seed the rule and the classifiers later and the sample data is belt-and-braces.
 */
@ExtendWith(MockitoExtension.class)
class ProjectSeedListenersTest {

    private static final ProjectCreatedEvent CREATED = new ProjectCreatedEvent("p1");

    @Mock
    AlertService alerts;

    @Mock
    ClassifierService classifiers;

    @Mock
    ProjectRepository projects;

    @Test
    void aFailedSeedDoesNotFailProjectCreation() {
        when(alerts.ensureCaseOpenedRule("p1")).thenThrow(new IllegalStateException("db down"));
        when(classifiers.seedBuiltIns("p1")).thenThrow(new IllegalStateException("db down"));
        when(projects.findById("p1")).thenThrow(new IllegalStateException("db down"));
        SampleProjectSeedListener sample = new SampleProjectSeedListener(
                projects,
                mock(PipelineService.class),
                classifiers,
                mock(MetricBaselineRepository.class),
                mock(SampleDataRepository.class),
                mock(MediaStore.class),
                mock(MediaRefRepository.class),
                mock(SessionRepository.class),
                mock(RetrievedDocRepository.class),
                mock(FindingRepository.class),
                mock(FindingEvidenceRepository.class),
                mock(ClassifierDetectionWriteRepository.class),
                mock(GroundednessAssessmentRepository.class),
                mock(FrustrationAssessmentRepository.class),
                mock(CaseOpener.class));

        assertDoesNotThrow(() -> new CaseAlertSeedListener(alerts).onProjectCreated(CREATED));
        assertDoesNotThrow(() -> new ClassifierSeedListener(classifiers).onProjectCreated(CREATED));
        assertDoesNotThrow(() -> sample.onProjectCreated(CREATED));
    }
}
