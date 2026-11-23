// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentModelsTest {

    private final ProjectModelSettings settings = mock(ProjectModelSettings.class);
    private final ObserverProperties observer = new ObserverProperties();
    private final AgentModels models = new AgentModels(settings, observer, rates());

    private static ModelsDevRates rates() {
        ModelsDevRates rates = mock(ModelsDevRates.class);
        when(rates.cost(any())).thenReturn(Optional.empty());
        return rates;
    }

    /**
     * The bug: a project with no frontier model runs the fix lane on the deployment's default model, so a model the
     * owner never accepted for code changes writes a pull request.
     */
    @Test
    void theFixLaneNeverFallsBackToTheDeploymentDefault() {
        when(settings.resolveAgenticModel("p1", ModelLane.FIX)).thenReturn(Optional.empty());

        TessaryException ex = assertThrows(TessaryException.class, () -> models.resolve("p1", ModelLane.FIX));

        assertEquals(ModelConfigError.FRONTIER_MODEL_MISSING, ex.error());
    }

    /** The bug: the fix lane's guard spreads to RCA and triage, which stop running on deployments with no org key. */
    @Test
    void anotherSandboxLaneStillFallsBackToTheDeploymentDefault() {
        when(settings.resolveAgenticModel("p1", ModelLane.RCA)).thenReturn(Optional.empty());

        AgentModels.Choice choice = models.resolve("p1", ModelLane.RCA);

        assertEquals(observer.getAgentic().getModel(), choice.model());
        assertEquals(ModelProvider.BEDROCK, choice.provider());
    }
}
