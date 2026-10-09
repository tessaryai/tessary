// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import ai.tessary.ingest.CallSiteRegistry;
import ai.tessary.model.CallSite;
import ai.tessary.model.Pipeline;
import ai.tessary.version.ProjectVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Per-project pipeline access. The runtime source of truth is per-project DB
 * tables; the on-disk {@code .tessary/} bundle is the import format
 * (parsed shard-by-shard inside {@link ai.tessary.pipeline.ImportController}).
 */
@Service
public class PipelineService implements CallSiteRegistry {

    private final PipelineRepository pipelines;
    private final ProjectVersionService versions;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public PipelineService(
            PipelineRepository pipelines,
            ProjectVersionService versions,
            ApplicationEventPublisher events,
            ObjectMapper mapper) {
        this.pipelines = pipelines;
        this.versions = versions;
        this.events = events;
        this.mapper = mapper;
    }

    /**
     * Bind the project's current pipeline to the commit it was synthesized
     * against and materialize that project version (reason {@code pipeline_sync}).
     * No-op when the bundle didn't declare a commit.
     */
    public void stampVersion(
            String projectId, @Nullable String commitSha, @Nullable String repoOwner, @Nullable String repoName) {
        if (commitSha == null || commitSha.isBlank()) return;
        pipelines.stampSyncedCommit(projectId, commitSha, repoOwner, repoName);
        versions.reasonPipelineSync(projectId, commitSha);
    }

    /** Read this project's pipeline from the DB. Returns {@link Pipeline#empty()} for fresh projects. */
    public Pipeline getPipeline(String projectId) {
        return pipelines.load(projectId);
    }

    /** The project's existing call-site ids — loaded once per ingest batch (see {@link #ensureCallSite}). */
    @Override
    public Set<String> callSiteIds(String projectId) {
        return pipelines.callSiteIds(projectId);
    }

    /**
     * Materialize a minimal call site for a resolved-but-unknown id so plain-OTLP telemetry (explicit
     * {@code tessary.call_site_id}, no plugin pipeline) is gradable and visible in the Pipeline — call sites
     * emerge from traffic (see devdocs/reference/ingestion-contract/README.md). Idempotent.
     */
    @Override
    public void ensureCallSite(String projectId, String id) {
        pipelines.ensureCallSite(projectId, id);
    }

    /**
     * Full sync: every entity not in the upload is deleted; everything in
     * the upload is inserted fresh. Pipeline meta is always replaced.
     */
    public PipelineRepository.Diff replace(String projectId, Pipeline pipeline) {
        Map<String, String> beforeShapes = pipelines.callSiteShapes(projectId);
        Map<String, String> beforeSchemas = pipelines.callSiteOutputSchemas(projectId);
        PipelineRepository.Diff diff = pipelines.replace(projectId, pipeline);
        publishCallSiteFactChanges(projectId, beforeShapes, beforeSchemas, pipeline.callSites());
        return diff;
    }

    /**
     * Diff-friendly merge: the meta block is upserted and meta entities (call sites, chains, failure
     * modes) are inserted or updated by id. Never deletes.
     */
    public PipelineRepository.Diff upsert(String projectId, Pipeline pipeline) {
        Map<String, String> beforeShapes = pipelines.callSiteShapes(projectId);
        Map<String, String> beforeSchemas = pipelines.callSiteOutputSchemas(projectId);
        PipelineRepository.Diff diff = pipelines.upsert(projectId, pipeline);
        publishCallSiteFactChanges(projectId, beforeShapes, beforeSchemas, pipeline.callSites());
        return diff;
    }

    /** See {@link CanonicalJson} — a re-declared schema must compare byte-for-byte with the stored one. */
    private String canonicalJson(JsonNode json) {
        return CanonicalJson.of(mapper, json);
    }

    /**
     * Announce the call sites whose code-tracked facts the import actually moved, so the built-ins
     * gated on them re-score the history they swept while the fact was absent or wrong.
     *
     * <p>Diffed here rather than inside the repository because {@code replace} deletes every call site
     * before re-inserting it — a diff taken afterwards would read every import as a first capture and
     * rewind everything, every time.
     *
     * <p>Only call sites present in the upload are considered. A call site the import DELETED is
     * deliberately not reported: its observations no longer resolve to a gated call site at all, so
     * re-sweeping could not score them either way.
     *
     * <p>A bundle that is SILENT on {@code output_schema} reports no schema change. Silence means "this
     * shard does not carry the fact", not "the fact is gone" — the repository carries the stored
     * schema across the wipe for exactly those call sites, so nothing actually changed.
     */
    private void publishCallSiteFactChanges(
            String projectId,
            Map<String, String> beforeShapes,
            Map<String, String> beforeSchemas,
            List<CallSite> incoming) {
        Set<String> shapeChanged = new LinkedHashSet<>();
        Set<String> schemaChanged = new LinkedHashSet<>();
        for (CallSite cs : incoming) {
            if (!Objects.equals(beforeShapes.get(cs.id()), cs.shape())) {
                shapeChanged.add(cs.id());
            }
            var outputSchema = cs.outputSchema();
            if (outputSchema != null && !Objects.equals(beforeSchemas.get(cs.id()), canonicalJson(outputSchema))) {
                schemaChanged.add(cs.id());
            }
        }
        if (!shapeChanged.isEmpty()) {
            events.publishEvent(new CallSiteFactChangedEvent(projectId, CallSiteFact.SHAPE, shapeChanged));
        }
        if (!schemaChanged.isEmpty()) {
            events.publishEvent(new CallSiteFactChangedEvent(projectId, CallSiteFact.OUTPUT_SCHEMA, schemaChanged));
        }
    }
}
