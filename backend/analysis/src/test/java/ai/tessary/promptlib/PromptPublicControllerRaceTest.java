// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.CapabilityService;
import ai.tessary.promptlib.PromptPublicDtos.PublicCreatePromptRequest;
import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.ResolvedVersion;
import ai.tessary.tenant.KeyScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Two SDK clients pushing the same new name at once: the loser's insert hits the unique name, and without the re-read
 * it answers 409 for a write that a common prompt API accepts as version 2. A real race needs two connections, so the
 * service is mocked here; the rest of the controller runs against Postgres in the app module.
 */
@ExtendWith(MockitoExtension.class)
class PromptPublicControllerRaceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:12:44.118Z");
    private static final TenantContext ADMIN =
            new TenantContext("user-1", null, "org-1", "proj-1", "member", "key-1", KeyScope.ADMIN);

    @Mock
    PromptService service;

    @Mock
    CapabilityService capabilities;

    @Test
    void postRacingAnotherCreateWritesTheNextVersion() {
        PromptRow raced = new PromptRow("prompt-1", "proj-1", "p", PromptType.TEXT, List.of(), 1, "user-2", NOW, NOW);
        PromptVersionRow v2 =
                new PromptVersionRow("v2", "proj-1", "prompt-1", 2, "\"b\"", "{}", null, "user-1", CreateVia.API, NOW);
        when(service.findByName("proj-1", "p")).thenReturn(Optional.empty(), Optional.of(raced));
        when(service.create(eq("proj-1"), eq("user-1"), eq(CreateVia.API), any()))
                .thenThrow(new TessaryException(PromptsError.NAME_EXISTS, "p"));
        when(service.createVersion(eq("proj-1"), eq("prompt-1"), eq("user-1"), eq(CreateVia.API), any()))
                .thenReturn(
                        new ResolvedVersion(raced, v2, new PromptBody.Text("b"), Map.of(), List.of("latest"), true));

        PromptPublicController controller = new PromptPublicController(service, capabilities);
        var created = controller
                .create(ADMIN, new PublicCreatePromptRequest("p", null, "b", null, null, null, null))
                .data();

        assertEquals(2, created == null ? 0 : created.version());
    }
}
