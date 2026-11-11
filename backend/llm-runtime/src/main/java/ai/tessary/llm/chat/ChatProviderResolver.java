// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import ai.tessary.crypto.SecretBox;
import ai.tessary.ingest.UrlGuard;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import org.springframework.stereotype.Component;

/**
 * Resolves a provider the org holds a key for into a {@link ChatTarget}, failing loudly on every gap. The SSRF guard
 * runs here rather than in {@link HttpChatClient}, so the client stays testable against a loopback socket.
 */
@Component
public class ChatProviderResolver {

    private final ProviderCredentialRepository credentials;
    private final SecretBox secretBox;

    public ChatProviderResolver(ProviderCredentialRepository credentials, SecretBox secretBox) {
        this.credentials = credentials;
        this.secretBox = secretBox;
    }

    /** @param requestedModel ignored for CUSTOM, whose credential names the model its endpoint serves */
    public ChatTarget resolve(String orgId, ModelProvider provider, String requestedModel) {
        ChatDialect dialect = ChatTarget.dialectFor(provider);
        ProviderCredential credential = credentials
                .findByOrgAndProvider(orgId, provider)
                .filter(c -> c.apiKeySealed() != null && !c.apiKeySealed().isBlank())
                .orElseThrow(() -> new TessaryException(ModelConfigError.MISSING_CREDENTIALS, provider));
        String model = provider == ModelProvider.CUSTOM ? customModel(credential) : requestedModel;
        URI endpoint = ChatTarget.endpointFor(provider, credential.baseUrlOverride());
        UrlGuard.requirePublicHttp(endpoint.toString());
        if (!secretBox.isConfigured()) throw new TessaryException(ModelConfigError.SECRET_KEY_NOT_CONFIGURED);
        return new ChatTarget(provider, model, endpoint, secretBox.open(credential.apiKeySealed()), dialect);
    }

    private static String customModel(ProviderCredential credential) {
        String name = credential.customModelName();
        if (name == null || name.isBlank()) {
            throw new TessaryException(
                    PlaygroundError.PROVIDER_UNSUPPORTED,
                    ModelProvider.CUSTOM,
                    "the credential names no custom_model_name");
        }
        return name;
    }
}
