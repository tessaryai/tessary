// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pipeline;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.auth.TenantPathResolver.Resolved;
import ai.tessary.evals.model.Pipeline;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.PipelineError;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.pipeline.BundleAssembler.AssembledBundle;
import ai.tessary.evals.pipeline.BundleAssembler.NamedBody;
import ai.tessary.evals.sop.SopIntakeDispatch;
import ai.tessary.evals.web.ApiResponse;
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
 * Multipart-only ingest for the sharded {@code .tessary/} bundle emitted by the
 * evals plugin. Shard parsing lives in {@link BundleAssembler}; this controller
 * handles auth, the multipart→body decode, the commit, and stamping the project
 * version the bundle declares.
 *
 * <p>Modes: <b>upsert</b> (default) inserts/updates by id, never deletes;
 * <b>replace</b> is a full sync that deletes entities absent from the upload.
 * Both commit inside one transaction so a partial-import state is never observable.
 *
 * <p><b>No capability gate.</b> This surface was gated on {@code GRADERS} until Track A retired that
 * capability; bundle import is now plain pipeline authoring, and the sibling pipeline write surfaces
 * ({@code PipelineController}) carry no gate either. Re-pointing it at another capability would have
 * been an arbitrary choice, not a preserved one.
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
    private final SopIntakeDispatch sopIntake;

    public ImportController(
            BundleAssembler assembler,
            PipelineService pipelineService,
            TenantPathResolver resolver,
            SopIntakeDispatch sopIntake) {
        this.assembler = assembler;
        this.pipelineService = pipelineService;
        this.resolver = resolver;
        this.sopIntake = sopIntake;
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
        if (files == null || files.length == 0) {
            throw new EvalsException(PipelineError.EMPTY_UPLOAD);
        }
        List<NamedBody> bodies = toNamedBodies(files);
        AssembledBundle bundle = assembler.assemble(bodies);
        ImportResult result = commit(r, bundle, normalisedMode);
        sopIntake.importSops(r.project().id(), bodies, bundle.commitSha());
        return ApiResponse.ok(result);
    }

    private List<NamedBody> toNamedBodies(MultipartFile[] files) {
        List<NamedBody> out = new ArrayList<>(files.length);
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String name = f.getOriginalFilename();
            if (name == null || name.isBlank()) continue;
            try {
                out.add(new NamedBody(name, new String(f.getBytes(), StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new EvalsException(PipelineError.FILE_READ_FAILED, e, name);
            }
        }
        return out;
    }

    private Resolved resolveAndAuthorize(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        // Members + viewers can curate, only owners can mutate the pipeline.
        // Project bearer tokens are exempt — the skill/plugin needs to push
        // regenerated pipelines without a human in the loop.
        if (!"owner".equals(r.role()) && !ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner role required to import");
        }
        return r;
    }

    private String normaliseMode(String raw) {
        if (raw == null) return MODE_UPSERT;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (MODE_UPSERT.equals(lower) || MODE_REPLACE.equals(lower)) return lower;
        throw new EvalsException(PipelineError.INVALID_MODE, raw);
    }

    private ImportResult commit(Resolved r, AssembledBundle bundle, String mode) {
        Pipeline pipeline = bundle.pipeline();
        String projectId = r.project().id();
        PipelineRepository.Diff diff;
        if (MODE_REPLACE.equals(mode)) {
            diff = pipelineService.replace(projectId, pipeline);
        } else {
            // Sharded layout always carries meta — pipeline/meta.yaml is required.
            diff = pipelineService.upsert(projectId, pipeline, true);
        }

        // Bind the pipeline to the commit it was synthesized against (when the
        // bundle declares one), materializing that project version. A bundle
        // without a declared commit leaves the project unbound until a git
        // integration is connected.
        pipelineService.stampVersion(projectId, bundle.commitSha(), bundle.repoOwner(), bundle.repoName());
        pipelineService.storeKnowledgeIndex(projectId, bundle.knowledgeIndexJson());

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
