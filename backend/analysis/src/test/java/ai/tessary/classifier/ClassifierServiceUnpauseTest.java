// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.GroundednessProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.PlatformCreditExhausted;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.EncoderAvailability;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Saving a key re-resolves the lane of every paused Frustration in the org. An org out of platform credit makes
 * that resolve throw; the key save must still succeed and leave the classifier paused.
 */
class ClassifierServiceUnpauseTest {

    private static final String ORG = "org-1";
    private static final String PID = "proj-1";

    private final ClassifierRepository signals = mock(ClassifierRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final DecisionProviderResolver decisionProviders = mock(DecisionProviderResolver.class);
    private final ClassifierService service = new ClassifierService(
            signals,
            mock(ClassifierDetectionRepository.class),
            mock(ClassifierJobRepository.class),
            mock(BuiltInClassifierCatalog.class),
            mock(SubstrateReadRepository.class),
            new ObjectMapper(),
            new ClassifierProperties(),
            mock(CapabilityService.class),
            projects,
            mock(MetricBaselineRepository.class),
            decisionProviders,
            mock(EncoderAvailability.class),
            new GroundednessProperties());

    @Test
    void anOrgOutOfPlatformCreditKeepsItsPauseWhenAnotherKeyIsSaved() {
        when(projects.findByOrg(ORG))
                .thenReturn(List.of(new Project(PID, ORG, "p", "P", null, "t0", null, null, true, null)));
        when(signals.listByProject(PID)).thenReturn(List.of(frustration()));
        when(signals.findPause(PID, "cls-f"))
                .thenReturn(Optional.of(new ClassifierPause(ClassifierPause.NO_CREDIT, Instant.EPOCH)));
        when(decisionProviders.resolve(PID, ModelLane.FRUSTRATION)).thenThrow(new NoCredit());

        assertEquals(0, service.unpauseForProvider(ORG, ModelProvider.OPENAI));
        verify(signals, never()).unpause(PID, "cls-f");
    }

    /** Enabling asks whether the lane has a provider, not whether it has credit: a sweep pauses it as no_credit. */
    @Test
    void enablingFrustrationOnAProviderWithNoCreditLeftIsAccepted() {
        when(projects.findById(PID))
                .thenReturn(Optional.of(new Project(PID, ORG, "p", "P", null, "t0", null, null, true, null)));
        when(signals.findById(PID, "cls-f")).thenReturn(Optional.of(frustration()));
        when(signals.setEnabled(PID, "cls-f", true)).thenReturn(1);
        when(decisionProviders.hasProvider(PID, ModelLane.FRUSTRATION)).thenReturn(true);
        when(decisionProviders.resolve(PID, ModelLane.FRUSTRATION)).thenThrow(new NoCredit());

        service.setEnabled(PID, "cls-f", true);

        verify(signals).setEnabled(PID, "cls-f", true);
    }

    private static ClassifierRow frustration() {
        return new ClassifierRow(
                "cls-f",
                PID,
                "frustration",
                "Frustration",
                null,
                BuiltInDetector.Kind.FRUSTRATION,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
    }

    private static final class NoCredit extends TessaryException implements PlatformCreditExhausted {
        NoCredit() {
            super(ModelConfigError.MISSING_CREDENTIALS, ModelProvider.PLATFORM);
        }
    }
}
