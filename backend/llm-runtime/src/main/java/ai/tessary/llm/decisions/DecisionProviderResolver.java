// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.ModelCatalog;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProjectOrgResolver;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Project to decision target: the org's decision-model key, decrypted, and the one decision model the
 * catalog offers on that gateway.
 *
 * <p>TypeSafe is preferred over OpenRouter when the org holds both, since it is the model's own
 * endpoint. Empty when the org holds a key for neither, which a caller treats as "no provider".
 */
@Component
public class DecisionProviderResolver {

    /** Gateways that can carry a decision call, most preferred first. */
    static final List<ModelProvider> GATEWAYS = List.of(ModelProvider.TYPESAFE, ModelProvider.OPENROUTER);

    private final ProviderCredentialRepository credentials;
    private final SecretBox secretBox;
    private final ProjectOrgResolver orgResolver;

    public DecisionProviderResolver(
            ProviderCredentialRepository credentials, SecretBox secretBox, ProjectOrgResolver orgResolver) {
        this.credentials = credentials;
        this.secretBox = secretBox;
        this.orgResolver = orgResolver;
    }

    public Optional<DecisionTarget> resolve(String projectId) {
        String orgId = orgResolver.orgIdFor(projectId);
        if (orgId == null) return Optional.empty();
        return GATEWAYS.stream()
                .map(provider -> target(orgId, provider))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<DecisionTarget> target(String orgId, ModelProvider provider) {
        Optional<ProviderCredential> cred = credentials.findByOrgAndProvider(orgId, provider);
        Optional<String> model = decisionModel(provider);
        if (cred.isEmpty() || model.isEmpty()) return Optional.empty();
        String sealed = cred.get().apiKeySealed();
        if (sealed == null || sealed.isBlank()) return Optional.empty();
        return Optional.of(new DecisionTarget(
                provider,
                model.get(),
                DecisionTarget.endpointFor(provider, cred.get().baseUrlOverride()),
                secretBox.open(sealed)));
    }

    /** The catalog's decision model on {@code provider}; one per provider by construction. */
    static Optional<String> decisionModel(ModelProvider provider) {
        return ModelCatalog.entries().stream()
                .filter(e -> e.provider() == provider && e.decision())
                .map(ModelCatalog.CatalogEntry::modelName)
                .findFirst();
    }
}
