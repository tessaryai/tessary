// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link SweepRouting#sweepFor}: the sweep {@code ClassifierService#setEnabled} asks before turning a row on. It is the
 * sweep {@link ClassifierWorker} would hand the row's jobs to, so a sweep never vetoes a row it would never run.
 */
class SweepRoutingTest {

    private static final String UNDECLARED = "undeclared_kind";
    private static final String OBSERVED = BuiltInDetector.Kind.TOOL_ERROR;
    private static final String DRIFT = BuiltInDetector.Kind.DURATION_DRIFT;

    private final BuiltInClassifierCatalog catalog = mock(BuiltInClassifierCatalog.class);
    private final ClassifierSweep undeclared = sweep(UNDECLARED);
    private final ClassifierSweep observed = sweep(OBSERVED);
    private final ClassifierSweep drift = sweep(DRIFT);
    private final SweepRouting routing =
            new SweepRouting(catalog, new ClassifierSweepRegistry(TestObjectProvider.of(undeclared, observed, drift)));

    /** Catches a registered kind no module declares being left without the sweep the worker runs it with. */
    @Test
    void aKindNoModuleDeclaresIsRunByItsRegisteredSweep() {
        when(catalog.grainFor(UNDECLARED)).thenReturn(Grain.OBSERVATION);

        assertEquals(Optional.of(undeclared), routing.sweepFor(UNDECLARED));
    }

    /** Catches a fallback to some other sweep for a kind nothing claims. */
    @Test
    void aKindWithNoSweepHasNone() {
        when(catalog.grainFor("nobody")).thenReturn(Grain.OBSERVATION);

        assertTrue(routing.sweepFor("nobody").isEmpty());
    }

    /**
     * Catches {@code sweepFor} ignoring the grain: a sweep that also claims a catalog kind of observation grain is
     * never handed its jobs, so it must not veto turning that classifier on.
     */
    @Test
    void aDeclaredKindThatIsNotWindowGrainIsNeverHandedToASweep() {
        when(catalog.declares(OBSERVED)).thenReturn(true);
        when(catalog.grainFor(OBSERVED)).thenReturn(Grain.OBSERVATION);

        assertTrue(routing.sweepFor(OBSERVED).isEmpty());
    }

    /** Catches a declared window kind losing the sweep the worker dispatches it to. */
    @Test
    void aDeclaredWindowKindIsRunByItsRegisteredSweep() {
        when(catalog.declares(DRIFT)).thenReturn(true);
        when(catalog.grainFor(DRIFT)).thenReturn(Grain.WINDOW);

        assertEquals(Optional.of(drift), routing.sweepFor(DRIFT));
    }

    private static ClassifierSweep sweep(String kind) {
        ClassifierSweep sweep = mock(ClassifierSweep.class);
        when(sweep.kinds()).thenReturn(Set.of(kind));
        return sweep;
    }
}
