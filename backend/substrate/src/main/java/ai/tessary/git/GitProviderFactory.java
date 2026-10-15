// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Resolves a provider's beans by {@link GitProvider}; adding a provider is adding its impl classes. */
@Component
public class GitProviderFactory {

    private final Map<GitProvider, GitProviderClient> clients = new EnumMap<>(GitProvider.class);
    private final Map<GitProvider, GitTokenService> tokens = new EnumMap<>(GitProvider.class);

    public GitProviderFactory(List<GitProviderClient> clientBeans, List<GitTokenService> tokenBeans) {
        for (GitProviderClient c : clientBeans) clients.put(c.provider(), c);
        for (GitTokenService t : tokenBeans) tokens.put(t.provider(), t);
    }

    public GitProviderClient client(GitProvider provider) {
        return clients.get(provider);
    }

    public GitTokenService tokenService(GitProvider provider) {
        return tokens.get(provider);
    }
}
