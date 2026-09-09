// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.ingest.IngestQuotaGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default plan SPI wiring for this build: a {@code @Bean} method, not a component-scanned class,
 * so {@link ConditionalOnMissingBean} sees only other candidates and a same-typed bean elsewhere
 * on the classpath can override it.
 */
@Configuration(proxyBeanMethods = false)
public class OpenPlanConfig {

    /** No cap, so nothing to enforce. Required by {@code OtlpIngestService}'s constructor. */
    @Bean
    @ConditionalOnMissingBean(IngestQuotaGate.class)
    UncappedIngestQuotaGate uncappedIngestQuotaGate() {
        return new UncappedIngestQuotaGate();
    }
}
