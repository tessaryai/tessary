// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricDriftSweep;
import ai.tessary.classifier.metric.MetricSource;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.toolerror.ToolErrorService;
import ai.tessary.classifier.toolerror.ToolErrorSweep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every catalog kind that sweeps at a fitting tier has exactly one sweep claiming it, the invariant the registry
 * depends on and nothing else pins ({@code ClassifierWorkerLoggingTest} stubs {@code kinds()}). An unmatched kind is
 * inert by design, so dropping one silently stops that classifier's findings everywhere, with only a {@code
 * signal.sweep.no-handler} WARN. Sweeps are built with mocks, since {@code kinds()} is a declaration; add a new one
 * to {@link #openSweeps()}.
 */
class SweepCatalogCoverageTest {

    @Test
    @DisplayName(
            "every WINDOW catalog kind is claimed by exactly one sweep, and no sweep claims a kind the catalog does not define")
    void fitting_tier_kinds_and_registered_sweeps_agree() {
        BuiltInClassifierCatalog catalog = catalog();

        Set<String> fittingTierKinds = new TreeSet<>();
        for (BuiltInClassifierCatalog.BuiltIn builtIn : catalog.builtIns()) {
            Grain grain = catalog.grainFor(builtIn.detector());
            if (grain == Grain.WINDOW) fittingTierKinds.add(builtIn.detector());
        }

        Set<String> claimed = new TreeSet<>();
        for (ClassifierSweep sweep : openSweeps()) {
            for (String kind : sweep.kinds()) {
                if (!claimed.add(kind)) {
                    throw new AssertionError("two sweeps claim " + kind
                            + "; the registry rejects that at startup, so this is a build-time catch");
                }
            }
        }

        // Whole sets: one-way containment would miss a sweep claiming a kind the catalog dropped.
        assertEquals(
                fittingTierKinds,
                claimed,
                "the catalog's fitting-tier kinds and the kinds the sweeps claim have drifted apart; "
                        + "an unclaimed kind is a classifier that silently stops producing findings, and a "
                        + "claimed kind the catalog does not define is the same bug from the "
                        + "other end");
    }

    /** Every sweep this tree defines. */
    private static List<ClassifierSweep> openSweeps() {
        ClassifierJobRepository jobs = mock(ClassifierJobRepository.class);
        return List.of(
                new MetricDriftSweep(
                        jobs,
                        mock(MetricBaselineRepository.class),
                        mock(FindingRepository.class),
                        mock(FindingEvidenceRepository.class),
                        mock(BehaviorSubstrateRepository.class),
                        mock(MetricSource.class),
                        new ObjectMapper()),
                new ToolErrorSweep(jobs, mock(ToolErrorService.class)));
    }

    private static BuiltInClassifierCatalog catalog() {
        return new BuiltInClassifierCatalog(
                new ObjectMapper(),
                mock(SubstrateReadRepository.class),
                mock(EncoderScorer.class),
                TestObjectProvider.of(List.of()));
    }
}
