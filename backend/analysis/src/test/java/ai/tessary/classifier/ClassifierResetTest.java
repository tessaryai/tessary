// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository;
import ai.tessary.classifier.frustration.FrustrationRateRepository;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository.Restart;
import ai.tessary.classifier.worker.ClassifierSweep;
import ai.tessary.classifier.worker.ClassifierSweepRegistry;
import ai.tessary.classifier.worker.SweepContext;
import ai.tessary.classifier.worker.SweepOutcome;
import ai.tessary.classifier.worker.SweepRouting;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The reset of a classifier whose kind a registered sweep runs: the sweep owns that kind's assessments and learned
 * state, so the reset asks it to forget them, or the rewound cursor finds every match already judged.
 */
class ClassifierResetTest {

    private static final String PID = "proj-1";
    private static final String KIND = "undeclared_kind";

    /** A sweep from outside the tree that records the rows it was asked to forget. */
    private static final class ForgettingSweep implements ClassifierSweep {
        final List<String> forgotten = new ArrayList<>();

        @Override
        public Set<String> kinds() {
            return Set.of(KIND);
        }

        @Override
        public SweepOutcome sweep(SweepContext ctx) {
            return new SweepOutcome(0, 0);
        }

        @Override
        public int reset(ClassifierRow classifier) {
            forgotten.add(classifier.id());
            return 3;
        }
    }

    private final ClassifierService classifiers = mock(ClassifierService.class);
    private final ClassifierJobRepository jobs = mock(ClassifierJobRepository.class);
    private final BuiltInClassifierCatalog catalog = mock(BuiltInClassifierCatalog.class);
    private final ForgettingSweep sweep = new ForgettingSweep();

    private final ClassifierReset reset = new ClassifierReset(
            classifiers,
            mock(ClassifierRepository.class),
            jobs,
            mock(ClassifierDetectionWriteRepository.class),
            mock(FindingRepository.class),
            mock(FrustrationAssessmentRepository.class),
            mock(FrustrationRateRepository.class),
            mock(GroundednessAssessmentRepository.class),
            mock(GroundednessRateRepository.class),
            mock(MalformedOutputRateRepository.class),
            mock(ToolErrorStateRepository.class),
            mock(ToolErrorReferenceRepository.class),
            mock(MetricBaselineRepository.class),
            new SweepRouting(catalog, new ClassifierSweepRegistry(TestObjectProvider.of(sweep))));

    /** Catches a reset that clears only the detection registry for a sweep-run kind, so nothing is judged again. */
    @Test
    void theSweepThatRunsTheKindForgetsWhatItJudged() {
        ClassifierRow row = ClassifierRowBuilder.of(KIND)
                .id("clf-1")
                .projectId(PID)
                .custom()
                .build();
        when(classifiers.get(PID, "clf-1")).thenReturn(row);
        when(jobs.restart(PID, "clf-1")).thenReturn(Restart.RESTARTED);
        when(catalog.declares(KIND)).thenReturn(false);

        reset.reset(PID, "clf-1", "user-1");

        assertEquals(List.of("clf-1"), sweep.forgotten);
    }
}
