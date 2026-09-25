// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The project-scoped writes behind the enable toggle and the mode switch. Both read the row through the
 * tenant guard first, then write under the same project; a write that matches no row (the row went between
 * the two) must answer NOT_FOUND rather than report a change that never landed.
 */
class ClassifierServiceWriteGuardTest {

    private static final String PID = "proj-1";

    private final ClassifierRepository signals = mock(ClassifierRepository.class);
    private final ClassifierService service = new ClassifierService(
            signals,
            mock(ClassifierDetectionRepository.class),
            mock(ClassifierJobRepository.class),
            mock(BuiltInClassifierCatalog.class),
            mock(SubstrateReadRepository.class),
            new ObjectMapper(),
            new ClassifierProperties(),
            mock(CapabilityService.class),
            mock(ProjectRepository.class),
            mock(ai.tessary.classifier.metric.MetricBaselineRepository.class),
            mock(ai.tessary.llm.decisions.DecisionProviderResolver.class),
            mock(ai.tessary.plan.EncoderAvailability.class),
            new ai.tessary.config.GroundednessProperties());

    /** Catches a zero-row write being reported as a success, handing back a row the write never touched. */
    @ParameterizedTest
    @ValueSource(strings = {"enabled", "mode"})
    void aWriteThatMatchesNoRowIsNotFound(String write) {
        when(signals.findById(PID, "sig-a")).thenReturn(Optional.of(row()));
        Executable call;
        if ("enabled".equals(write)) {
            when(signals.setEnabled(PID, "sig-a", false)).thenReturn(0);
            call = () -> service.setEnabled(PID, "sig-a", false);
        } else {
            when(signals.setMode(PID, "sig-a", ClassifierRow.Mode.TRACKING)).thenReturn(0);
            call = () -> service.setMode(PID, "sig-a", ClassifierRow.Mode.TRACKING);
        }

        TessaryException e = assertThrows(TessaryException.class, call);

        assertEquals(ClassifierError.NOT_FOUND, e.error());
    }

    /** Catches an unknown operating point being written (and read back) as if it were a mode. */
    @Test
    void anUnknownModeIsRefusedBeforeAnythingIsReadOrWritten() {
        TessaryException e = assertThrows(TessaryException.class, () -> service.setMode(PID, "sig-a", "aggressive"));

        assertEquals(ClassifierError.INVALID_MODE, e.error());
        verifyNoInteractions(signals);
    }

    private static ClassifierRow row() {
        return new ClassifierRow(
                "sig-a",
                PID,
                "sig-sig-a",
                "Signal a",
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
}
