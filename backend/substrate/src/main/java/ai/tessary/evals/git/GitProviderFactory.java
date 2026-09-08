// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.GitError;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves the right provider implementation by {@link GitProvider}. Spring
 * injects every registered {@link GitProviderClient} / {@link GitWebhookAdapter}
 * / {@link GitTokenService} bean, so adding a provider is just adding its impl
 * classes — nothing here changes. Mirrors the {@code ingest/SourceFactory} seam.
 */
@Component
public class GitProviderFactory {

    private final Map<GitProvider, GitProviderClient> clients = new EnumMap<>(GitProvider.class);
    private final Map<GitProvider, GitWebhookAdapter> adapters = new EnumMap<>(GitProvider.class);
    private final Map<GitProvider, GitTokenService> tokens = new EnumMap<>(GitProvider.class);

    public GitProviderFactory(
            List<GitProviderClient> clientBeans,
            List<GitWebhookAdapter> adapterBeans,
            List<GitTokenService> tokenBeans) {
        for (GitProviderClient c : clientBeans) clients.put(c.provider(), c);
        for (GitWebhookAdapter a : adapterBeans) adapters.put(a.provider(), a);
        for (GitTokenService t : tokenBeans) tokens.put(t.provider(), t);
    }

    public GitProviderClient client(GitProvider provider) {
        return require(clients.get(provider), provider);
    }

    public GitWebhookAdapter adapter(GitProvider provider) {
        return require(adapters.get(provider), provider);
    }

    public GitTokenService tokenService(GitProvider provider) {
        return require(tokens.get(provider), provider);
    }

    public List<GitWebhookAdapter> adapters() {
        return List.copyOf(adapters.values());
    }

    private <T> T require(T impl, GitProvider provider) {
        if (impl == null) {
            throw new EvalsException(GitError.UNSUPPORTED_PROVIDER, provider.wire());
        }
        return impl;
    }
}
