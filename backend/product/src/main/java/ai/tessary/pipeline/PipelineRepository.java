// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import ai.tessary.db.Upsert;
import ai.tessary.db.schema.CallSiteColumns;
import ai.tessary.db.schema.ChainColumns;
import ai.tessary.db.schema.GraderFailureModeColumns;
import ai.tessary.db.schema.PipelineMetaColumns;
import ai.tessary.model.CallSite;
import ai.tessary.model.Capability;
import ai.tessary.model.Chain;
import ai.tessary.model.Constraint;
import ai.tessary.model.FailureMode;
import ai.tessary.model.ImplicitInvariant;
import ai.tessary.model.InvariantCoverage;
import ai.tessary.model.Observed;
import ai.tessary.model.Pack;
import ai.tessary.model.Pipeline;
import ai.tessary.model.ProductProfile;
import ai.tessary.model.Progress;
import ai.tessary.model.Runtime;
import ai.tessary.model.SourceSpan;
import ai.tessary.model.TaxonomyNode;
import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code pipeline.yaml} is the import format; the DB is the runtime source of truth. Call sites, chains and failure
 * modes are relational; compound fields are JSON in TEXT columns, consumed whole by the UI.
 */
@Repository
public class PipelineRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PipelineRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Pipeline load(String projectId) {
        Optional<MetaRow> meta =
                jdbc.sql("""
            SELECT version, product_hint, product_profile_json, invariants_json,
                   invariant_coverage_json,
                   runtime_json, packs_json, taxonomy_json, progress_json, capabilities_json
            FROM pipeline_meta WHERE project_id = :pid
            """).param("pid", projectId).query(MetaRow::map).optional();
        if (meta.isEmpty()) return Pipeline.empty();

        MetaRow m = meta.get();
        return new Pipeline(
                m.version,
                m.productHint,
                readJsonList(m.packsJson, Pack.class),
                readJson(m.productProfileJson, ProductProfile.class),
                readJsonList(m.invariantsJson, ImplicitInvariant.class),
                readJsonList(m.invariantCoverageJson, InvariantCoverage.class),
                readJson(m.runtimeJson, Runtime.class),
                loadCallSites(projectId),
                loadChains(projectId),
                loadFailureModes(projectId),
                readJsonList(m.taxonomyJson, TaxonomyNode.class),
                readJson(m.progressJson, Progress.class),
                readJsonList(m.capabilitiesJson, Capability.class));
    }

    /** Kept out of {@link #upsertMeta} so a partial upload never clobbers it. */
    public void stampSyncedCommit(
            String projectId, String commitSha, @Nullable String repoOwner, @Nullable String repoName) {
        jdbc.sql("""
            UPDATE pipeline_meta SET synced_commit_sha = :sha, repo_owner = :owner, repo_name = :name
            WHERE project_id = :pid
            """)
                .param("sha", commitSha)
                .param("owner", repoOwner)
                .param("name", repoName)
                .param("pid", projectId)
                .update();
    }

    /** {@code removed} is only non-zero in {@code replace} mode. */
    public record EntityDiff(int added, int updated, int removed) {}

    public record Diff(EntityDiff callSites, EntityDiff chains, EntityDiff failureModes, boolean metaReplaced) {}

    /** Every entity not in the upload is deleted. */
    @Transactional
    public Diff replace(String projectId, Pipeline pipeline) {
        Set<String> preCs = existingIds(projectId, "call_site");
        Set<String> preCh = existingIds(projectId, "chain");
        Set<String> preFm = existingIds(projectId, "grader_failure_mode");

        // Wipe-and-write drops the column, so a stored schema is restored where the incoming shard is silent; a shard
        // that declares one wins.
        Map<String, String> carried = callSiteOutputSchemas(projectId);
        pipeline.callSites().stream().filter(cs -> cs.outputSchema() != null).forEach(cs -> carried.remove(cs.id()));

        deleteAll(projectId);
        upsertMeta(projectId, pipeline);
        upsertCallSites(projectId, pipeline.callSites());
        restoreCallSiteOutputSchemas(projectId, carried);
        upsertChains(projectId, pipeline.chains());
        upsertFailureModes(projectId, pipeline.failureModes());

        return new Diff(
                diffWithRemovals(
                        preCs, pipeline.callSites().stream().map(CallSite::id).toList()),
                diffWithRemovals(
                        preCh, pipeline.chains().stream().map(Chain::id).toList()),
                diffWithRemovals(
                        preFm,
                        pipeline.failureModes().stream().map(FailureMode::id).toList()),
                true);
    }

    /** Entities not in the upload are left alone. */
    @Transactional
    public Diff upsert(String projectId, Pipeline pipeline) {
        Set<String> preCs = existingIds(projectId, "call_site");
        Set<String> preCh = existingIds(projectId, "chain");
        Set<String> preFm = existingIds(projectId, "grader_failure_mode");

        upsertMeta(projectId, pipeline);
        upsertCallSites(projectId, pipeline.callSites());
        upsertChains(projectId, pipeline.chains());
        upsertFailureModes(projectId, pipeline.failureModes());

        return new Diff(
                diffNoRemovals(
                        preCs, pipeline.callSites().stream().map(CallSite::id).toList()),
                diffNoRemovals(preCh, pipeline.chains().stream().map(Chain::id).toList()),
                diffNoRemovals(
                        preFm,
                        pipeline.failureModes().stream().map(FailureMode::id).toList()),
                true);
    }

    private Set<String> existingIds(String projectId, String table) {
        return new HashSet<>(jdbc.sql("SELECT id FROM " + table + " WHERE project_id = :pid")
                .param("pid", projectId)
                .query(String.class)
                .list());
    }

    private static EntityDiff diffNoRemovals(Set<String> existing, List<String> incoming) {
        int added = 0;
        int updated = 0;
        for (String id : incoming) {
            if (existing.contains(id)) updated++;
            else added++;
        }
        return new EntityDiff(added, updated, 0);
    }

    private static EntityDiff diffWithRemovals(Set<String> existing, List<String> incoming) {
        Set<String> incomingSet = new HashSet<>(incoming);
        int added = 0;
        int updated = 0;
        for (String id : incoming) {
            if (existing.contains(id)) updated++;
            else added++;
        }
        int removed = 0;
        for (String id : existing) if (!incomingSet.contains(id)) removed++;
        return new EntityDiff(added, updated, removed);
    }

    private void deleteAll(String projectId) {
        jdbc.sql("DELETE FROM grader_failure_mode WHERE project_id = :pid")
                .param("pid", projectId)
                .update();
        jdbc.sql("DELETE FROM chain WHERE project_id = :pid")
                .param("pid", projectId)
                .update();
        jdbc.sql("DELETE FROM call_site WHERE project_id = :pid")
                .param("pid", projectId)
                .update();
        jdbc.sql("DELETE FROM pipeline_meta WHERE project_id = :pid")
                .param("pid", projectId)
                .update();
    }

    private void upsertMeta(String projectId, Pipeline p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PipelineMetaColumns.PROJECT_ID, projectId);
        values.put(PipelineMetaColumns.VERSION, p.version());
        values.put(PipelineMetaColumns.PRODUCT_HINT, p.productHint());
        values.put(PipelineMetaColumns.PRODUCT_PROFILE_JSON, writeJson(p.productProfile()));
        values.put(PipelineMetaColumns.INVARIANTS_JSON, writeJson(p.implicitInvariants()));
        values.put(PipelineMetaColumns.INVARIANT_COVERAGE_JSON, writeJson(p.invariantCoverage()));
        values.put(PipelineMetaColumns.TAXONOMY_JSON, writeJson(p.taxonomy()));
        values.put(PipelineMetaColumns.PACKS_JSON, writeJson(p.packs()));
        values.put(PipelineMetaColumns.RUNTIME_JSON, writeJson(p.runtime()));
        values.put(PipelineMetaColumns.PROGRESS_JSON, writeJson(p.progress()));
        values.put(PipelineMetaColumns.CAPABILITIES_JSON, writeJson(p.capabilities()));
        values.put(PipelineMetaColumns.UPDATED_AT, Instant.now().toString());

        Upsert up = Upsert.into(PipelineMetaColumns.TABLE, values, List.of(PipelineMetaColumns.PROJECT_ID));
        jdbc.sql(up.sql()).params(up.params()).update();
    }

    public Set<String> callSiteIds(String projectId) {
        return existingIds(projectId, "call_site");
    }

    /** Call sites emerge from traffic (devdocs/reference/ingestion-contract/README.md). Idempotent. */
    public void ensureCallSite(String projectId, String id) {
        jdbc.sql("INSERT INTO call_site (project_id, id) VALUES (:pid, :id) "
                        + "ON CONFLICT (project_id, id) DO NOTHING")
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    Map<String, String> callSiteOutputSchemas(String projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, output_schema FROM call_site WHERE project_id = :pid AND output_schema IS NOT NULL")
                .param("pid", projectId)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("output_schema")))
                .list();
        return out;
    }

    private void restoreCallSiteOutputSchemas(String projectId, Map<String, String> captured) {
        for (Map.Entry<String, String> e : captured.entrySet()) {
            // Scoped by id, so a call site the import deleted stays gone.
            jdbc.sql("UPDATE call_site SET output_schema = :schema WHERE project_id = :pid AND id = :id")
                    .param("schema", e.getValue())
                    .param("pid", projectId)
                    .param("id", e.getKey())
                    .update();
        }
    }

    /** Read before an import to diff shapes, since the bulk upsert path cannot report a per-column change. */
    public Map<String, String> callSiteShapes(String projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, shape FROM call_site WHERE project_id = :pid AND shape IS NOT NULL")
                .param("pid", projectId)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("shape")))
                .list();
        return out;
    }

    private List<CallSite> loadCallSites(String projectId) {
        return jdbc.sql("""
            SELECT id, use_case, invocation, provider, model, system_prompt, prompt_text, surrounding_code,
                   shape, shape_confidence, intent, constraints_json, sample_count,
                   file_hint, line_hint,
                   source_spans_json, dataset_path, observed_json, expected_spans_json,
                   output_schema, tools_json
            FROM call_site WHERE project_id = :pid
            """).param("pid", projectId).query(this::mapCallSite).list();
    }

    /** Null for an explicit YAML null ("no structured output"). Canonicalized so key order doesn't change the bytes. */
    private @Nullable String declaredSchemaText(CallSite cs) {
        JsonNode declared = cs.outputSchema();
        if (declared == null || declared.isNull()) return null;
        return CanonicalJson.of(mapper, declared);
    }

    private CallSite mapCallSite(ResultSet rs, int n) throws SQLException {
        List<Constraint> constraints = readJsonList(rs.getString("constraints_json"), Constraint.class);
        List<SourceSpan> sourceSpans = readJsonList(rs.getString("source_spans_json"), SourceSpan.class);
        Observed observed = readJson(rs.getString("observed_json"), Observed.class);
        List<CallSite.ExpectedSpan> expectedSpans =
                readJsonList(rs.getString("expected_spans_json"), CallSite.ExpectedSpan.class);
        return new CallSite(
                rs.getString("id"),
                rs.getString("use_case"),
                rs.getString("invocation"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("system_prompt"),
                rs.getString("prompt_text"),
                rs.getString("surrounding_code"),
                rs.getString("file_hint"),
                (Integer) rs.getObject("line_hint"),
                rs.getString("shape"),
                rs.getString("shape_confidence"),
                rs.getString("intent"),
                constraints,
                (Integer) rs.getObject("sample_count"),
                sourceSpans,
                rs.getString("dataset_path"),
                observed,
                expectedSpans,
                readJsonTree(rs.getString("output_schema")),
                readJsonList(rs.getString("tools_json"), CallSite.ToolSpec.class));
    }

    private void upsertCallSites(String projectId, List<CallSite> sites) {
        for (CallSite cs : sites) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(CallSiteColumns.PROJECT_ID, projectId);
            values.put(CallSiteColumns.ID, cs.id());
            values.put(CallSiteColumns.USE_CASE, cs.useCase());
            values.put(CallSiteColumns.INVOCATION, cs.invocation());
            values.put(CallSiteColumns.PROVIDER, cs.provider());
            values.put(CallSiteColumns.MODEL, cs.model());
            values.put(CallSiteColumns.SYSTEM_PROMPT, cs.systemPrompt());
            values.put(CallSiteColumns.PROMPT_TEXT, cs.promptText());
            values.put(CallSiteColumns.SURROUNDING_CODE, cs.surroundingCode());
            values.put(CallSiteColumns.SHAPE, cs.shape());
            values.put(CallSiteColumns.SHAPE_CONFIDENCE, cs.shapeConfidence());
            values.put(CallSiteColumns.INTENT, cs.intent());
            values.put(CallSiteColumns.CONSTRAINTS_JSON, writeJson(cs.constraints()));
            values.put(CallSiteColumns.SAMPLE_COUNT, cs.sampleCount());
            values.put(CallSiteColumns.FILE_HINT, cs.fileHint());
            values.put(CallSiteColumns.LINE_HINT, cs.lineHint());
            values.put(CallSiteColumns.SOURCE_SPANS_JSON, writeJson(cs.sourceSpans()));
            values.put(CallSiteColumns.DATASET_PATH, cs.datasetPath());
            values.put(CallSiteColumns.OBSERVED_JSON, writeJson(cs.observed()));
            values.put(CallSiteColumns.EXPECTED_SPANS_JSON, writeJson(cs.expectedSpans()));
            // Written unconditionally: bundle silence on `tools` means "no tools". output_schema keeps "absent"
            // distinct from "declared empty".
            values.put(CallSiteColumns.TOOLS_JSON, writeJson(cs.tools()));
            // Jackson binds an explicit `output_schema: null` to NullNode, so check isNull(); `!= null` would store the
            // string "null", which Malformed Output would read as a schema forever.
            if (cs.outputSchema() != null) {
                values.put(CallSiteColumns.OUTPUT_SCHEMA, declaredSchemaText(cs));
            }

            // Keyed by (project_id, id): the producer-chosen id may recur across projects.
            Upsert up =
                    Upsert.into(CallSiteColumns.TABLE, values, List.of(CallSiteColumns.PROJECT_ID, CallSiteColumns.ID));
            jdbc.sql(up.sql()).params(up.params()).update();
        }
    }

    private List<Chain> loadChains(String projectId) {
        return jdbc.sql("""
            SELECT id, name, detection_method, confidence, rationale,
                   call_site_ids_json, ensemble_span_ids_json
            FROM chain WHERE project_id = :pid
            """).param("pid", projectId).query(this::mapChain).list();
    }

    private Chain mapChain(ResultSet rs, int n) throws SQLException {
        return new Chain(
                rs.getString("id"),
                rs.getString("name"),
                readJsonList(rs.getString("call_site_ids_json"), String.class),
                rs.getString("detection_method"),
                rs.getString("confidence"),
                rs.getString("rationale"),
                readJsonList(rs.getString("ensemble_span_ids_json"), String.class));
    }

    private void upsertChains(String projectId, List<Chain> chains) {
        for (Chain c : chains) {
            if (c.id() == null || c.id().isBlank()) {
                throw new IllegalArgumentException("chain is missing id (project=" + projectId + ")");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(ChainColumns.PROJECT_ID, projectId);
            values.put(ChainColumns.ID, c.id());
            values.put(ChainColumns.NAME, c.name());
            values.put(ChainColumns.DETECTION_METHOD, c.detectionMethod());
            values.put(ChainColumns.CONFIDENCE, c.confidence());
            values.put(ChainColumns.RATIONALE, c.rationale());
            values.put(ChainColumns.CALL_SITE_IDS_JSON, writeJson(c.callSiteIds()));
            values.put(ChainColumns.ENSEMBLE_SPAN_IDS_JSON, writeJson(c.ensembleSpanIds()));

            Upsert up = Upsert.into(ChainColumns.TABLE, values, List.of(ChainColumns.PROJECT_ID, ChainColumns.ID));
            jdbc.sql(up.sql()).params(up.params()).update();
        }
    }

    private List<FailureMode> loadFailureModes(String projectId) {
        return jdbc.sql("""
            SELECT id, scope, call_site_id, chain_id, name, description, severity, layer,
                   taxonomy_node_id, pack_ids_json, compliance_tags_json,
                   grader_deferred, grader_id
            FROM grader_failure_mode WHERE project_id = :pid
            """).param("pid", projectId).query(this::mapFailureMode).list();
    }

    private FailureMode mapFailureMode(ResultSet rs, int n) throws SQLException {
        return new FailureMode(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("severity"),
                rs.getString("scope"),
                rs.getString("call_site_id"),
                rs.getString("chain_id"),
                rs.getString("layer"),
                readJsonList(rs.getString("pack_ids_json"), String.class),
                readJsonList(rs.getString("compliance_tags_json"), String.class),
                rs.getString("taxonomy_node_id"),
                (Boolean) rs.getObject("grader_deferred"),
                rs.getString("grader_id"));
    }

    private void upsertFailureModes(String projectId, List<FailureMode> modes) {
        for (FailureMode fm : modes) {
            if (fm.id() == null || fm.id().isBlank()) {
                throw new IllegalArgumentException("grader_failure_mode is missing id (project=" + projectId + ")");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(GraderFailureModeColumns.PROJECT_ID, projectId);
            values.put(GraderFailureModeColumns.ID, fm.id());
            values.put(GraderFailureModeColumns.SCOPE, fm.scope());
            values.put(GraderFailureModeColumns.CALL_SITE_ID, fm.callSiteId());
            values.put(GraderFailureModeColumns.CHAIN_ID, fm.chainId());
            values.put(GraderFailureModeColumns.NAME, fm.name());
            values.put(GraderFailureModeColumns.DESCRIPTION, fm.description());
            values.put(GraderFailureModeColumns.SEVERITY, fm.severity());
            values.put(GraderFailureModeColumns.LAYER, fm.layer());
            values.put(GraderFailureModeColumns.TAXONOMY_NODE_ID, fm.taxonomyNodeId());
            values.put(GraderFailureModeColumns.PACK_IDS_JSON, writeJson(fm.packIds()));
            values.put(GraderFailureModeColumns.COMPLIANCE_TAGS_JSON, writeJson(fm.complianceTags()));
            values.put(GraderFailureModeColumns.GRADER_DEFERRED, fm.graderDeferred());
            values.put(GraderFailureModeColumns.GRADER_ID, fm.graderId());

            Upsert up = Upsert.into(
                    GraderFailureModeColumns.TABLE,
                    values,
                    List.of(GraderFailureModeColumns.PROJECT_ID, GraderFailureModeColumns.ID));
            jdbc.sql(up.sql()).params(up.params()).update();
        }
    }

    private @Nullable String writeJson(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof List<?> l && l.isEmpty()) return null;
        return serialise(value);
    }

    @ExcludeFromJacocoGeneratedReport(
            "defensive: the mapper is the app's own, and every value here is a model record it read from the"
                    + " bundle")
    private String serialise(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("PipelineRepository: failed to serialise JSON for " + value, e);
        }
    }

    /** A raw tree, because a declared output_schema is any JSON Schema. */
    private @Nullable JsonNode readJsonTree(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> @Nullable T readJson(@Nullable String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("PipelineRepository: malformed JSON for " + type.getSimpleName(), e);
        }
    }

    private <T> List<T> readJsonList(String json, Class<T> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            throw new IllegalStateException("PipelineRepository: malformed JSON list for " + type.getSimpleName(), e);
        }
    }

    private record MetaRow(
            String version,
            String productHint,
            String productProfileJson,
            String invariantsJson,
            String invariantCoverageJson,
            String taxonomyJson,
            String packsJson,
            String runtimeJson,
            String progressJson,
            String capabilitiesJson) {
        static MetaRow map(ResultSet rs, int n) throws SQLException {
            return new MetaRow(
                    rs.getString("version"),
                    rs.getString("product_hint"),
                    rs.getString("product_profile_json"),
                    rs.getString("invariants_json"),
                    rs.getString("invariant_coverage_json"),
                    rs.getString("taxonomy_json"),
                    rs.getString("packs_json"),
                    rs.getString("runtime_json"),
                    rs.getString("progress_json"),
                    rs.getString("capabilities_json"));
        }
    }
}
