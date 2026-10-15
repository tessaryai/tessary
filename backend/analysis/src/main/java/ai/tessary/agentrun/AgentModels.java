// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.ModelCatalog;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The model an agent lane runs, its models.dev rates and its provider: the project's agentic model, else the observer's
 * on Bedrock. One answer so one-shot runs and sessions bill the same way.
 */
@Component
public class AgentModels {

    private final ProjectModelSettings modelSettings;
    private final ObserverProperties observerProps;
    private final ModelsDevRates rates;

    public AgentModels(ProjectModelSettings modelSettings, ObserverProperties observerProps, ModelsDevRates rates) {
        this.modelSettings = modelSettings;
        this.observerProps = observerProps;
        this.rates = rates;
    }

    public Choice resolve(String projectId, ModelLane lane) {
        Optional<ProjectModelSettings.ResolvedAgenticModel> resolved =
                modelSettings.resolveAgenticModel(projectId, lane);
        String model = resolved.map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                .orElseGet(() -> observerProps.getAgentic().getModel());
        // The deployment default is a Bedrock inference-profile id.
        String modelsDevId = resolved.isPresent()
                ? resolved.get().modelsDevId()
                : ModelCatalog.modelsDevId(ModelProvider.BEDROCK, model).orElse(null);
        ModelsDevRates.ModelCost modelCost = rates.cost(modelsDevId).orElse(null);
        ModelProvider provider = resolved.map(ProjectModelSettings.ResolvedAgenticModel::provider)
                .orElse(ModelProvider.BEDROCK);
        return new Choice(model, modelCost, provider);
    }

    /**
     * @param model the id the agent runs and the ledger stores @param modelCost null when models.dev does not price it
     */
    public record Choice(String model, ModelsDevRates.@Nullable ModelCost modelCost, ModelProvider provider) {}
}
