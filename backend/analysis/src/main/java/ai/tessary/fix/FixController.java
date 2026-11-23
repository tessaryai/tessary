// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentRunDtos.RunStepsView;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The steps of a fix run, for the case page's run bar. The run itself is read on the case ({@code GET /cases/{id}}).
 * Not gated on the capability: a run that happened stays readable after the org turns fix pull requests off.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/fix-runs")
public class FixController {

    private final FixReads reads;
    private final TenantPathResolver resolver;

    public FixController(FixReads reads, TenantPathResolver resolver) {
        this.reads = reads;
        this.resolver = resolver;
    }

    /**
     * The run's newest agent run's steps after step {@code after}, oldest first. A client that holds a running step
     * passes {@code after} one below it, so the step comes back with its end.
     */
    @GetMapping("/{id}/steps")
    public ApiResponse<RunStepsView> steps(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int after) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(reads.steps(r.project().id(), id, after));
    }
}
