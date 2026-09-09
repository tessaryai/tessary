// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import ai.tessary.ingest.CallSiteRegistry;
import ai.tessary.model.CallSite;
import ai.tessary.model.Pipeline;
import ai.tessary.version.ProjectVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * Persist the bundle's committed knowledge index. No-op when absent.
     *
     * <p><b>Write-only since Track A</b>, and deliberately kept: the index powered the observer's Tier-1
     * anchor mapping, and the observer was removed. The bundle still carries the shard and the plugin
     * still publishes it, so dropping the write would silently discard data an unchanged producer sends.
     * {@link #currentKnowledgeIndex} is its read side and currently has no caller.
     */
    public void storeKnowledgeIndex(String projectId, @Nullable String knowledgeIndexJson) {
        if (knowledgeIndexJson == null || knowledgeIndexJson.isBlank()) return;
        pipelines.stampKnowledgeIndex(projectId, knowledgeIndexJson);
    }

    /** Read this project's pipeline from the DB. Returns {@link Pipeline#empty()} for fresh projects. */
    public Pipeline getPipeline(String projectId) {
        return pipelines.load(projectId);
    }

    /** True when the project has had a pipeline imported. */
    public boolean hasPipeline(String projectId) {
        return pipelines.exists(projectId);
    }

    /** The project's existing call-site ids — loaded once per ingest batch (see {@link #ensureCallSite}). */
    @Override
    public Set<String> callSiteIds(String projectId) {
        return pipelines.callSiteIds(projectId);
    }

    /**
     * Materialize a minimal call site for a resolved-but-unknown id so plain-OTLP telemetry (explicit
     * {@code tessary.call_site_id}, no plugin pipeline) is gradable and visible in the Pipeline — call sites
     * emerge from traffic (see docs/reference/ingestion-contract/README.md). Idempotent.
     */
    /**
     * See {@link PipelineRepository#setCallSiteOutputSchema}. A genuine change announces itself as a
     * {@link CallSiteFactChangedEvent} so classifiers gated on the schema can re-score the history they
     * swept while it was missing (the schema necessarily lands after the traffic it describes).
     *
     * @return whether the stored schema actually changed.
     */
    public boolean setCallSiteOutputSchema(String projectId, String callSiteId, @Nullable String outputSchemaJson) {
        boolean changed = pipelines.setCallSiteOutputSchema(projectId, callSiteId, canonicalJson(outputSchemaJson));
        if (changed) {
            events.publishEvent(
                    new CallSiteFactChangedEvent(projectId, CallSiteFact.OUTPUT_SCHEMA, Set.of(callSiteId)));
        }
        return changed;
    }

    @Override
    public void ensureCallSite(String projectId, String id) {
        pipelines.ensureCallSite(projectId, id);
    }

    /** The commit the project's pipeline definitions are currently synced to (empty when unbound). */
    public Optional<String> currentSyncedCommit(String projectId) {
        return pipelines.currentSyncedCommit(projectId);
    }

    /**
     * The project's committed knowledge index JSON (regions + prompt-content hashes), empty when the
     * bundle predates the KB. Its one consumer was the observer's Tier-1 anchor mapping, removed by
     * Track A — see {@link #storeKnowledgeIndex} for why the pair is kept rather than deleted.
     */
    public Optional<String> currentKnowledgeIndex(String projectId) {
        return pipelines.currentKnowledgeIndex(projectId);
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
     * Diff-friendly merge: insert new entities, update existing ones by id.
     * Never deletes. {@code replaceMeta=true} means the upload carried meta,
     * so the meta block and meta entities (call sites, chains, failure modes)
     * get upserted. {@code false} leaves them untouched.
     */
    public PipelineRepository.Diff upsert(String projectId, Pipeline pipeline, boolean replaceMeta) {
        // An upload that carries no meta never touches call_site, so it cannot move a shape — skip the read.
        Map<String, String> beforeShapes = replaceMeta ? pipelines.callSiteShapes(projectId) : Map.of();
        Map<String, String> beforeSchemas = replaceMeta ? pipelines.callSiteOutputSchemas(projectId) : Map.of();
        PipelineRepository.Diff diff = pipelines.upsert(projectId, pipeline, replaceMeta);
        if (replaceMeta) {
            publishCallSiteFactChanges(projectId, beforeShapes, beforeSchemas, pipeline.callSites());
        }
        return diff;
    }

    /** See {@link CanonicalJson} — both writers of {@code output_schema} must agree byte-for-byte. */
    private @Nullable String canonicalJson(@Nullable String json) {
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
     * shard does not carry the fact", not "the fact is gone" — the repository carries the platform's
     * existing capture across the wipe for exactly those call sites, so nothing actually changed.
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
            if (outputSchema != null
                    && !Objects.equals(beforeSchemas.get(cs.id()), canonicalJson(outputSchema.toString()))) {
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
