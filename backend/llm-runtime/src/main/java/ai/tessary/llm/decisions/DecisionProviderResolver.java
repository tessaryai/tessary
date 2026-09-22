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
import org.springframework.stereotype.Component;

/**
 * Project to decision target: the decision lane's provider and model for this project, then the org's
 * key for that provider, decrypted.
 *
 * <p>The provider comes from {@link ProjectModelSettings}, so a project that pinned OpenRouter on the
 * lane runs there even when the org also holds a TypeSafe key, and an unpinned lane follows
 * {@code LanePriority} (TypeSafe first). Empty when the org holds a key for no provider the lane
 * offers, which a caller treats as "no provider".
 */
@Component
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
