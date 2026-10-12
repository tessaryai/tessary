// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SweepRouting#sweepFor}: the sweep {@code ClassifierService#setEnabled} asks before turning a row on. It is the
 * sweep {@link ClassifierWorker} would hand the row's jobs to, so a sweep never vetoes a row it would never run.
 */
@ExtendWith(MockitoExtension.class)
class SweepRoutingTest {

    private static final String UNDECLARED = "undeclared_kind";
    private static final String OBSERVED = BuiltInDetector.Kind.TOOL_ERROR;
    private static final String DRIFT = BuiltInDetector.Kind.DURATION_DRIFT;

    @Mock
    BuiltInClassifierCatalog catalog;

    @Mock
    ClassifierSweep undeclared;

    @Mock
    ClassifierSweep observed;

    @Mock
    ClassifierSweep drift;

    private SweepRouting routing;

    @BeforeEach
    void setUp() {
        when(undeclared.kinds()).thenReturn(Set.of(UNDECLARED));
        when(observed.kinds()).thenReturn(Set.of(OBSERVED));
        when(drift.kinds()).thenReturn(Set.of(DRIFT));
        routing = new SweepRouting(
                catalog, new ClassifierSweepRegistry(TestObjectProvider.of(undeclared, observed, drift)));
    }

    /**
     * Catches a registered kind no module declares being left without the sweep the worker runs it with. The catalog
     * gives the kind no window grain, so routing that reads the catalog for it finds no sweep; the right routing never
     * asks.
     */
    @Test
    void aKindNoModuleDeclaresIsRunByItsRegisteredSweep() {
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
}
