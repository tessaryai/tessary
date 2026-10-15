// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.model.Pipeline;
import ai.tessary.open.errors.PipelineError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.pipeline.BundleAssembler.AssembledBundle;
import ai.tessary.pipeline.BundleAssembler.NamedBody;
import ai.tessary.web.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Multipart import of a sharded {@code .tessary/} bundle; parsing lives in {@link BundleAssembler}. Upsert (default)
 * never deletes; replace deletes absent entities. Both commit in one transaction. No capability gate, matching the
 * sibling pipeline write surfaces.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/import")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private static final String MODE_UPSERT = "upsert";
    private static final String MODE_REPLACE = "replace";

    private final BundleAssembler assembler;
    private final PipelineService pipelineService;
    private final TenantPathResolver resolver;

    public ImportController(BundleAssembler assembler, PipelineService pipelineService, TenantPathResolver resolver) {
        this.assembler = assembler;
        this.pipelineService = pipelineService;
        this.resolver = resolver;
    }

    public record EntityDiffView(int added, int updated, int removed) {
        static EntityDiffView from(PipelineRepository.EntityDiff d) {
            return new EntityDiffView(d.added(), d.updated(), d.removed());
        }
    }

    public record ImportResult(
            String mode,
            boolean metaReplaced,
            EntityDiffView callSites,
            EntityDiffView chains,
            EntityDiffView failureModes,
            List<String> repairs) {}

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ImportResult> importDirectory(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "mode", defaultValue = MODE_UPSERT) String mode,
            @RequestPart("files") MultipartFile[] files) {
        Resolved r = resolveAndAuthorize(ctx, orgSlug, projectSlug);
        String normalisedMode = normaliseMode(mode);
        AssembledBundle bundle = assembler.assemble(toNamedBodies(files));
        return ApiResponse.ok(commit(r, bundle, normalisedMode));
    }

    private List<NamedBody> toNamedBodies(MultipartFile[] files) {
        List<NamedBody> out = new ArrayList<>(files.length);
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            String name = f.getOriginalFilename();
            if (name == null || name.isBlank()) continue;
            try {
                out.add(new NamedBody(name, new String(f.getBytes(), StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new TessaryException(PipelineError.FILE_READ_FAILED, e, name);
            }
        }
        return out;
    }

    private Resolved resolveAndAuthorize(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        // Only owners mutate the pipeline; project bearer tokens are exempt so the plugin can push without a human.
        if (!"owner".equals(r.role()) && !ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner role required to import");
        }
        return r;
    }

    private String normaliseMode(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (MODE_UPSERT.equals(lower) || MODE_REPLACE.equals(lower)) return lower;
        throw new TessaryException(PipelineError.INVALID_MODE, raw);
    }

    private ImportResult commit(Resolved r, AssembledBundle bundle, String mode) {
        Pipeline pipeline = bundle.pipeline();
        String projectId = r.project().id();
        PipelineRepository.Diff diff;
        if (MODE_REPLACE.equals(mode)) {
            diff = pipelineService.replace(projectId, pipeline);
        } else {
            diff = pipelineService.upsert(projectId, pipeline);
        }

        // Without a declared commit the project stays unbound until a git integration connects.
        pipelineService.stampVersion(projectId, bundle.commitSha(), bundle.repoOwner(), bundle.repoName());

        log.info(
                Markers.OPS,
                "import {} [mode={}]: callSites=+{}/~{}/-{} failureModes=+{}/~{}/-{} commit={}",
                projectId,
                mode,
                diff.callSites().added(),
                diff.callSites().updated(),
                diff.callSites().removed(),
                diff.failureModes().added(),
                diff.failureModes().updated(),
                diff.failureModes().removed(),
                bundle.commitSha());

        return new ImportResult(
                mode,
                diff.metaReplaced(),
                EntityDiffView.from(diff.callSites()),
                EntityDiffView.from(diff.chains()),
                EntityDiffView.from(diff.failureModes()),
                List.of());
    }
}
