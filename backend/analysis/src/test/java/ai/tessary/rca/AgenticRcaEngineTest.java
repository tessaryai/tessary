// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import ai.tessary.config.RcaProperties;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AgenticRcaEngine#validateSandboxConfig()} — nothing in this package exercised it before #857
 * (no {@code AgenticRcaEngineTest} existed at all). Only the pre-existing sandbox-key check is covered
 * here: #857 considered and rejected adding an equivalent unconditional blank-{@code mcp-base-url}
 * check to this method — see its javadoc for why (it would fail context refresh on every
 * {@code @SpringBootTest} in {@code app} that does not fake the property, proven concretely with
 * {@code OpenApiSpecDriftTest}). The per-job throw in {@link AgenticRcaEngine#run} remains the
 * enforcement point for a blank URL, unchanged by this issue.
 */
class AgenticRcaEngineTest {

    /** A minimal {@link RcaSandbox} test double keyed "e2b" — the default {@code RcaProperties} picks. */
    private static RcaSandbox fixedSandbox() {
        return new RcaSandbox() {
            @Override
            public String key() {
                return "e2b";
            }

            @Override
            public RcaSandbox.SandboxRun run(RcaSandbox.SandboxRequest req) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    private AgenticRcaEngine engine(RcaProperties props) {
        return new AgenticRcaEngine(
                props,
                List.of(fixedSandbox()),
                mock(GitIntegrationRepository.class),
                mock(GitProviderFactory.class),
                mock(ApiKeyService.class),
                mock(ProjectRepository.class),
                mock(OrgMembershipRepository.class),
                new ObjectMapper());
    }

    @Test
    void validateSandboxConfigPassesWithARegisteredSandbox() {
        RcaProperties props = new RcaProperties();
        // Default is already "e2b" (RcaProperties.Agentic#sandbox), asserted explicitly so this test
        // fails loudly if that default ever drifts.
        assertEquals("e2b", props.getAgentic().getSandbox());

        assertDoesNotThrow(() -> engine(props).validateSandboxConfig());
    }

    @Test
    void validateSandboxConfigRejectsAnUnregisteredSandboxKey() {
        RcaProperties props = new RcaProperties();
        props.getAgentic().setSandbox("no-such-driver");

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> engine(props).validateSandboxConfig());
        assertNotNull(e.getMessage());
        assertEquals(true, e.getMessage().contains("no-such-driver"));
    }
}
