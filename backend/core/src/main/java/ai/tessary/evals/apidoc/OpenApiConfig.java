// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.apidoc;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code-first OpenAPI configuration: derive the API contract from the controllers via
 * springdoc, so the checked-in canonical spec stays in lockstep with the running server.
 *
 * <p>The generated {@code /v3/api-docs} document is the input to the checked-in-spec drift guard, so it must be
 * <em>deterministic</em> across builds. springdoc's default {@code operationId} derivation appends a
 * numeric suffix based on discovery order (unstable); {@link #operationIdCustomizer()} replaces it with a
 * stable {@code <ControllerSimpleName>_<methodName>} id.
 */
@Configuration
public class OpenApiConfig {

    /** Top-level API metadata. */
    @Bean
    public OpenAPI evalsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tessary Evals API")
                        .version("0.0.1")
                        .description("The tessary evals platform HTTP API — the observability/query (/v1/*) and tenant"
                                + " (/api/...) surfaces. Generated from the controllers via springdoc; the"
                                + " checked-in spec is the single source of truth (see backend/contract)."));
    }

    /**
     * Deterministic {@code operationId}s: {@code <ControllerSimpleName>_<methodName>}. Stable across builds
     * (unlike springdoc's discovery-order-suffixed default), which the byte-equal spec drift test
     * requires.
     */
    @Bean
    public OperationCustomizer operationIdCustomizer() {
        return (operation, handlerMethod) -> {
            operation.setOperationId(handlerMethod.getBeanType().getSimpleName() + "_"
                    + handlerMethod.getMethod().getName());
            return operation;
        };
    }
}
