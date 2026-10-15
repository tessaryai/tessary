// SPDX-License-Identifier: Apache-2.0
package ai.tessary.apidoc;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code-first OpenAPI via springdoc. The generated spec feeds the checked-in drift guard, so it must be deterministic.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tessaryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tessary API")
                        .version("0.0.1")
                        .description("The Tessary platform HTTP API — the observability/query (/v1/*) and tenant"
                                + " (/api/...) surfaces. Generated from the controllers via springdoc; the"
                                + " checked-in spec is the single source of truth (see backend/contract)."));
    }

    /**
     * {@code <ControllerSimpleName>_<methodName>}, replacing springdoc's discovery-order suffix, which the byte-equal
     * drift test cannot tolerate.
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
