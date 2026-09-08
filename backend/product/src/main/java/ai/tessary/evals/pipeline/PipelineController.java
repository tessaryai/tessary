// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pipeline;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.model.Pipeline;
import ai.tessary.evals.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the project's pipeline content. Post-Phase-D the response envelope
 * is simpler — no on-disk path, no parse errors, no repair notes — but the
 * shape is kept stable so the frontend's {@code PipelineEnvelope} interface
 * still parses.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/pipeline")
public class PipelineController {

    private final PipelineService pipelineService;
    private final TenantPathResolver resolver;

    public PipelineController(PipelineService pipelineService, TenantPathResolver resolver) {
        this.pipelineService = pipelineService;
        this.resolver = resolver;
    }

    public record PipelineEnvelope(
            boolean ok, String path, List<String> repairs, List<String> errors, Pipeline pipeline) {}

    @GetMapping
    public ApiResponse<PipelineEnvelope> get(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(envelope(r.project().id()));
    }

    /**
     * Reload is a no-op since the DB is the source of truth. We keep it 200-OK
     * so any UI "Reload pipeline" button doesn't break.
     */
    @PostMapping("/reload")
    public ApiResponse<PipelineEnvelope> reload(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(envelope(r.project().id()));
    }

    private PipelineEnvelope envelope(String projectId) {
        Pipeline pipeline = pipelineService.getPipeline(projectId);
        return new PipelineEnvelope(
                true, // schema is DB-validated; no parse-failure mode anymore
                "db://project/" + projectId,
                List.of(),
                List.of(),
                pipeline);
    }
}
