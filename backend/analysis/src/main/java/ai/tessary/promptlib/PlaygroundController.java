// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.RequiresCapability;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunRequest;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunView;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs a prompt once on the org's own provider key. A run spends money, so it needs {@code ORG_MANAGE} and, for a
 * bearer key, the ADMIN scope. {@code BYO_PROVIDER_KEYS} is checked in the handler: a second
 * {@link RequiresCapability} on the method would replace the class-level one, not add to it.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/prompts/playground")
@RequiresCapability(Capability.PROMPT_MANAGEMENT)
public class PlaygroundController {

    private final PlaygroundService service;
    private final TenantPathResolver resolver;
    private final CapabilityService capabilities;

    public PlaygroundController(
            PlaygroundService service, TenantPathResolver resolver, CapabilityService capabilities) {
        this.service = service;
        this.resolver = resolver;
        this.capabilities = capabilities;
    }

    @PostMapping("/runs")
    public ApiResponse<PlaygroundRunView> run(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody PlaygroundRunRequest req) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "run the prompt playground");
        if (ctx.isMcpToken() && !ctx.keyPermits(KeyScope.ADMIN)) {
            throw new TessaryException(PromptsError.WRONG_KEY_SCOPE, "run");
        }
        capabilities.require(r.org().id(), Capability.BYO_PROVIDER_KEYS);
        return ApiResponse.ok(service.run(r.org().id(), r.project().id(), req));
    }
}
