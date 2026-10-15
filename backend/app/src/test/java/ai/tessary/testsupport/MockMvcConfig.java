// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.auth.AuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * One {@link MockMvc} per context, with the auth filter. MockMvc never runs a servlet filter it is not handed, so
 * without {@code addFilters} every request reaches the controller with no {@code TenantContext} and an auth test
 * proves nothing.
 */
@Configuration(proxyBeanMethods = false)
public class MockMvcConfig {

    @Bean
    MockMvc mockMvc(WebApplicationContext wac, AuthFilter authFilter) {
        return MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }
}
