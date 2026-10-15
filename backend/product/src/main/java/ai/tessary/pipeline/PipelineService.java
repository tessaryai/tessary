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

/** Per-project pipeline access. The DB is the source of truth; the {@code .tessary/} bundle is the import format. */
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

    /** No-op when the bundle didn't declare a commit. */
    public void stampVersion(
            String projectId, @Nullable String commitSha, @Nullable String repoOwner, @Nullable String repoName) {
        if (commitSha == null || commitSha.isBlank()) return;
        pipelines.stampSyncedCommit(projectId, commitSha, repoOwner, repoName);
        versions.reasonPipelineSync(projectId, commitSha);
    }

    public Pipeline getPipeline(String projectId) {
        return pipelines.load(projectId);
    }

    /** Loaded once per ingest batch. */
    @Override
    public Set<String> callSiteIds(String projectId) {
        return pipelines.callSiteIds(projectId);
    }

    /**
     * Call sites emerge from traffic, so plain-OTLP telemetry with an explicit {@code tessary.call_site_id} is visible
     * without a plugin pipeline. Idempotent.
     */
    @Override
    public void ensureCallSite(String projectId, String id) {
        pipelines.ensureCallSite(projectId, id);
    }

    /** Full sync: every entity not in the upload is deleted. */
    public PipelineRepository.Diff replace(String projectId, Pipeline pipeline) {
        Map<String, String> beforeShapes = pipelines.callSiteShapes(projectId);
        Map<String, String> beforeSchemas = pipelines.callSiteOutputSchemas(projectId);
        PipelineRepository.Diff diff = pipelines.replace(projectId, pipeline);
        publishCallSiteFactChanges(projectId, beforeShapes, beforeSchemas, pipeline.callSites());
        return diff;
    }

    /** Never deletes. */
    public PipelineRepository.Diff upsert(String projectId, Pipeline pipeline) {
        Map<String, String> beforeShapes = pipelines.callSiteShapes(projectId);
        Map<String, String> beforeSchemas = pipelines.callSiteOutputSchemas(projectId);
        PipelineRepository.Diff diff = pipelines.upsert(projectId, pipeline);
        publishCallSiteFactChanges(projectId, beforeShapes, beforeSchemas, pipeline.callSites());
        return diff;
    }

    /** A re-declared schema must compare byte-for-byte with the stored one. */
    private String canonicalJson(JsonNode json) {
        return CanonicalJson.of(mapper, json);
    }

    /**
     * Diffed here because {@code replace} deletes every call site before re-inserting, so a later diff would see every
     * import as a first capture. Deleted call sites are not reported, and a bundle silent on {@code output_schema}
     * reports no schema change.
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
