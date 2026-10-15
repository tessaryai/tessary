// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llm.ProjectOrgResolver;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import ai.tessary.llmspi.ModelLane;
import java.util.Optional;

/**
 * Resolves the decision lane's provider (pinned, else {@code LanePriority}) and the org's decrypted key; empty means no
 * provider. Another build may extend it to resolve {@link ModelProvider#PLATFORM}, throwing
 * {@link ai.tessary.llm.PlatformCreditExhausted} when out of credit. {@link #hasProvider} never calls {@link #resolve},
 * so a credit check cannot reach it.
 */
public class DecisionProviderResolver {

    private final ProjectModelSettings settings;
    private final ProviderCredentialRepository credentials;
    private final SecretBox secretBox;
    private final ProjectOrgResolver orgResolver;

    public DecisionProviderResolver(
            ProjectModelSettings settings,
            ProviderCredentialRepository credentials,
            SecretBox secretBox,
            ProjectOrgResolver orgResolver) {
        this.settings = settings;
        this.credentials = credentials;
        this.secretBox = secretBox;
        this.orgResolver = orgResolver;
    }

    /** Opens no key and asks nothing about credit. */
    public boolean hasProvider(String projectId, ModelLane lane) {
        String orgId = orgResolver.orgIdFor(projectId);
        if (orgId == null) return false;
        Optional<ProjectModelSettings.ResolvedDecisionModel> model = settings.resolveDecisionModel(projectId, lane);
        if (model.isEmpty()) return false;
        ModelProvider provider = model.get().provider();
        // Offered only when the deployment supplies it to this org.
        return provider == ModelProvider.PLATFORM
                || credentials
                        .findByOrgAndProvider(orgId, provider)
                        .map(ProviderCredential::apiKeySealed)
                        .filter(key -> !key.isBlank())
                        .isPresent();
    }

    public Optional<DecisionTarget> resolve(String projectId, ModelLane lane) {
        String orgId = orgResolver.orgIdFor(projectId);
        if (orgId == null) return Optional.empty();
        Optional<ProjectModelSettings.ResolvedDecisionModel> model = settings.resolveDecisionModel(projectId, lane);
        if (model.isEmpty()) return Optional.empty();
        ModelProvider provider = model.get().provider();
        Optional<ProviderCredential> cred = credentials.findByOrgAndProvider(orgId, provider);
        if (cred.isEmpty()) return Optional.empty();
        String sealed = cred.get().apiKeySealed();
        if (sealed == null || sealed.isBlank()) return Optional.empty();
        return Optional.of(new DecisionTarget(
                provider,
                model.get().modelId(),
                DecisionTarget.endpointFor(provider, cred.get().baseUrlOverride()),
                secretBox.open(sealed)));
    }
}
