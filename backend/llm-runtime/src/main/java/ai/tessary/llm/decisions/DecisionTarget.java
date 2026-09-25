// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.PlatformCatalog;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Where a decision call goes and on whose key: the gateway, the model id in that gateway's spelling,
 * the endpoint, and the org's decrypted key. The key lives in process only for the call; this
 * record's {@link #toString} never prints it.
 */
public record DecisionTarget(ModelProvider provider, String modelId, URI endpoint, String apiKey) {

    /** TypeSafe's own path under its base URL. */
    static final String TYPESAFE_PATH = "/v1/systemone";

    /** OpenRouter's decision path, which sits beside its {@code /v1} chat API rather than under it. */
    static final String OPENROUTER_PATH = "/alpha/decisions";

    /**
     * The decision endpoint for {@code provider}, from a credential's base URL override or the
     * platform default. A trailing {@code /v1} is dropped first: the Providers form shows the chat
     * base URL for OpenRouter, and neither decision path lives under it.
     */
    public static URI endpointFor(ModelProvider provider, @Nullable String baseUrlOverride) {
        String path =
                switch (provider) {
                    case TYPESAFE -> TYPESAFE_PATH;
                    case OPENROUTER -> OPENROUTER_PATH;
                    default -> throw new TessaryException(ModelConfigError.UNKNOWN_PROVIDER, provider);
                };
        String base = baseUrlOverride != null && !baseUrlOverride.isBlank()
                ? baseUrlOverride.trim()
                : PlatformCatalog.find(provider).orElseThrow().defaultBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.toLowerCase(Locale.ROOT).endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return URI.create(base + path);
    }

    @Override
    public String toString() {
        return "DecisionTarget[provider=" + provider + ", modelId=" + modelId + ", endpoint=" + endpoint + "]";
    }
}
