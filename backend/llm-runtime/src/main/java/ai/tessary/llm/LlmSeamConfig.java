// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Each {@code @Bean} is {@code @ConditionalOnMissingBean} so another build's resolver replaces this one. */
@Configuration(proxyBeanMethods = false)
public class LlmSeamConfig {

    @Bean
    @ConditionalOnMissingBean(AgenticCredentialResolver.class)
    AgenticCredentialResolver agenticCredentialResolver(
            ProviderCredentialRepository repo, SecretBox secretBox, ProjectOrgResolver orgResolver) {
        return new AgenticCredentialResolver(repo, secretBox, orgResolver);
    }

    @Bean
    @ConditionalOnMissingBean(DecisionProviderResolver.class)
    DecisionProviderResolver decisionProviderResolver(
            ProjectModelSettings settings,
            ProviderCredentialRepository repo,
            SecretBox secretBox,
            ProjectOrgResolver orgResolver) {
        return new DecisionProviderResolver(settings, repo, secretBox, orgResolver);
    }

    @Bean
    @ConditionalOnMissingBean(PlatformProviderSupplier.class)
    PlatformProviderSupplier platformProviderSupplier() {
        return PlatformProviderSupplier.none();
    }
}
