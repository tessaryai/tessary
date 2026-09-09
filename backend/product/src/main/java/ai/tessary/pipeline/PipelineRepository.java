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
 * Per-project pipeline persistence. The on-disk {@code pipeline.yaml} is the
 * import format; the DB is the runtime source of truth. {@link #load(String)}
 * reconstitutes a {@link Pipeline} for one project; {@link #replace(String,
 * Pipeline)} performs a "wipe and write" inside a transaction so import is
 * atomic.
 *
 * <p>Relational decomposition vs. JSON blobs:</p>
 * <ul>
 *   <li>The three core entities — call sites, chains and failure modes — get
 *       their own rows. Querying by id and filtering by project rely on those
 *       being relational.</li>
 *   <li>Compound fields (product profile, invariants, taxonomy, runtime,
 *       packs, observed stats, source spans, constraints,
 *       pack/compliance tag lists) are stored as
 *       Jackson JSON in TEXT columns. They're consumed as wholes by the UI;
 *       relational decomposition would 4× the schema with no payoff today.</li>
 * </ul>
 */
@Repository
public class PipelineRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PipelineRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Returns {@link Pipeline#empty()} when there's no pipeline_meta row for the project. */
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

    public boolean exists(String projectId) {
        return jdbc.sql("SELECT 1 FROM pipeline_meta WHERE project_id = :pid")
                .param("pid", projectId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** The commit the current pipeline was synced to, if known. */
    public Optional<String> currentSyncedCommit(String projectId) {
        List<String> vals = jdbc.sql("SELECT synced_commit_sha FROM pipeline_meta WHERE project_id = :pid")
                .param("pid", projectId)
                .query((rs, n) -> rs.getString("synced_commit_sha"))
                .list();
        return vals.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(vals.get(0)).filter(s -> !s.isBlank());
    }

    /** The committed knowledge index for the project, if any was imported. */
    public Optional<String> currentKnowledgeIndex(String projectId) {
        List<String> vals = jdbc.sql("SELECT knowledge_index_json FROM pipeline_meta WHERE project_id = :pid")
                .param("pid", projectId)
                .query((rs, n) -> rs.getString("knowledge_index_json"))
                .list();
        return vals.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(vals.get(0)).filter(s -> !s.isBlank());
    }

    /** Store the bundle's knowledge index. Kept out of {@link #upsertMeta} so a partial upload never clobbers it. */
    public void stampKnowledgeIndex(String projectId, String json) {
        jdbc.sql("UPDATE pipeline_meta SET knowledge_index_json = :json WHERE project_id = :pid")
                .param("json", json)
                .param("pid", projectId)
                .update();
    }

    /**
     * Record which commit (and repo) the project's pipeline now corresponds to.
     * Kept out of {@link #upsertMeta} so a partial upload never clobbers it.
     */
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

    /**
     * Per-entity-type counts surfaced to the import API so users can see what
     * actually changed.
     *
     * <ul>
     *   <li>{@code added} — id wasn't in the project before the import</li>
     *   <li>{@code updated} — id already existed; the row was overwritten</li>
     *   <li>{@code removed} — id was in the project but absent from the upload,
     *       so we deleted it. Only non-zero in {@code replace} mode.</li>
     * </ul>
     */
    public record EntityDiff(int added, int updated, int removed) {
        public static EntityDiff zero() {
            return new EntityDiff(0, 0, 0);
        }
    }

    /** What changed across the import. {@code metaReplaced=true} when pipeline.yaml was applied. */
    public record Diff(EntityDiff callSites, EntityDiff chains, EntityDiff failureModes, boolean metaReplaced) {
        public static Diff empty() {
            return new Diff(EntityDiff.zero(), EntityDiff.zero(), EntityDiff.zero(), false);
        }
    }

    /**
     * Atomically replace the project's pipeline. Every entity not in the upload
     * is deleted; every entity in the upload is inserted (fresh). Use this when
     * you want the DB to mirror the upload exactly — e.g. retiring stale call
     * sites after a major rewrite. Pipeline meta is always replaced.
     */
    @Transactional
    public Diff replace(String projectId, Pipeline pipeline) {
        Set<String> preCs = existingIds(projectId, "call_site");
        Set<String> preCh = existingIds(projectId, "chain");
        Set<String> preFm = existingIds(projectId, "grader_failure_mode");

        // output_schema has two writers: the bundle (a call-site shard may declare one) and agentic
        // synthesis, which reads it out of the repo and stamps the column directly. A wipe-and-write
        // import drops the column, so the platform's capture is carried across the delete — for the
        // same reason stampKnowledgeIndex is kept out of upsertMeta: an import must not clobber what it
        // does not carry. Restored only where the incoming bundle is SILENT; a shard that declares a
        // schema is the newer truth and wins, or the repo could never correct a stale capture.
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

    /**
     * Diff-friendly merge. Entities present in the upload are inserted (new) or
     * updated (existing); entities NOT in the upload are left alone. Use this
     * for incremental updates.
     *
     * <p>{@code replaceMeta} controls whether the pipeline meta block (product
     * profile, taxonomy, invariants, runtime, packs) and the entity tables get
     * touched. False means the upload carried no meta: nothing is written. True
     * means "pipeline.yaml is in the upload": upsert the meta + every meta entity
     * by id (without deleting unmentioned ones).</p>
     */
    @Transactional
    public Diff upsert(String projectId, Pipeline pipeline, boolean replaceMeta) {
        Set<String> preCs = existingIds(projectId, "call_site");
        Set<String> preCh = existingIds(projectId, "chain");
        Set<String> preFm = existingIds(projectId, "grader_failure_mode");

        if (replaceMeta) {
            upsertMeta(projectId, pipeline);
            upsertCallSites(projectId, pipeline.callSites());
            upsertChains(projectId, pipeline.chains());
            upsertFailureModes(projectId, pipeline.failureModes());
        }

        return new Diff(
                replaceMeta
                        ? diffNoRemovals(
                                preCs,
                                pipeline.callSites().stream().map(CallSite::id).toList())
                        : EntityDiff.zero(),
                replaceMeta
                        ? diffNoRemovals(
                                preCh, pipeline.chains().stream().map(Chain::id).toList())
                        : EntityDiff.zero(),
                replaceMeta
                        ? diffNoRemovals(
                                preFm,
                                pipeline.failureModes().stream()
                                        .map(FailureMode::id)
                                        .toList())
                        : EntityDiff.zero(),
                replaceMeta);
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

    // ------------------------------------------------------------------ call sites

    /**
     * All call-site ids for a project — loaded once per ingest batch so {@link #ensureCallSite} only fires
     * for a genuinely new id.
     */
    public Set<String> callSiteIds(String projectId) {
        return existingIds(projectId, "call_site");
    }

    /**
     * Materialize a minimal {@code call_site} row (just its slug id) when none exists — so a plain-OTLP
     * observation that resolved an explicit {@code tessary.call_site_id} but has no plugin-published pipeline
     * is not orphaned ("call sites emerge from traffic" — see devdocs/reference/ingestion-contract/README.md).
     * Idempotent: a plugin- or
     * previously-created call site is left untouched.
     */
    public void ensureCallSite(String projectId, String id) {
        jdbc.sql("INSERT INTO call_site (project_id, id) VALUES (:pid, :id) "
                        + "ON CONFLICT (project_id, id) DO NOTHING")
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /**
     * Persist the call site's declared structured-output JSON Schema, captured during agentic
     * synthesis (the agent reads the call site's real code). Last-write-wins: each generation run
     * re-reads the code, so the newest capture is the current truth — including {@code null}, which
     * clears a stale capture when the code no longer declares structured output. The Malformed
     * Output built-in classifier reads this column to validate observation outputs.
     *
     * <p>{@code IS DISTINCT FROM} makes the write a no-op when the capture is unchanged, so the
     * returned flag means "this call site's schema is genuinely different now" — the condition
     * {@link PipelineService} turns into a {@link CallSiteFactChangedEvent}. Null-safe in both
     * directions, so first capture (null → schema) and stale-clear (schema → null) both report a
     * change while a re-run that re-reads the same code reports none.
     *
     * @return whether the stored schema actually changed.
     */
    public boolean setCallSiteOutputSchema(String projectId, String callSiteId, @Nullable String outputSchemaJson) {
        return jdbc.sql("UPDATE call_site SET output_schema = :schema "
                                + "WHERE project_id = :pid AND id = :id AND output_schema IS DISTINCT FROM :schema")
                        .param("schema", outputSchemaJson)
                        .param("pid", projectId)
                        .param("id", callSiteId)
                        .update()
                > 0;
    }

    /**
     * {@code call_site_id → shape} for every call site in the project that has one. Read by {@link
     * PipelineService} immediately before a meta-bearing import so it can diff the incoming shapes
     * against the stored ones — {@code shape} is written through the bulk {@link #upsertCallSites}
     * path, which has no natural place to report a per-column change the way {@link
     * #setCallSiteOutputSchema} does.
     */
    /** {@code call_site_id → output_schema} for every call site in the project that has one. */
    Map<String, String> callSiteOutputSchemas(String projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, output_schema FROM call_site WHERE project_id = :pid AND output_schema IS NOT NULL")
                .param("pid", projectId)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("output_schema")))
                .list();
        return out;
    }

    /** Re-apply captured schemas after a wipe-and-write import, for call sites the upload still carries. */
    private void restoreCallSiteOutputSchemas(String projectId, Map<String, String> captured) {
        for (Map.Entry<String, String> e : captured.entrySet()) {
            // Scoped by id, so a call site the import DELETED stays gone rather than being resurrected
            // by its own schema.
            jdbc.sql("UPDATE call_site SET output_schema = :schema WHERE project_id = :pid AND id = :id")
                    .param("schema", e.getValue())
                    .param("pid", projectId)
                    .param("id", e.getKey())
                    .update();
        }
    }

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

    /**
     * The text a declared {@code output_schema} persists as: canonicalized JSON, or {@code null} when
     * the shard declared an explicit YAML null (which asserts the code declares no structured output).
     * Canonicalized on the way in so a schema carried by the bundle and the same schema captured by
     * synthesis are byte-identical — see {@link CanonicalJson}.
     */
    private @Nullable String declaredSchemaText(CallSite cs) {
        JsonNode declared = cs.outputSchema();
        if (declared == null || declared.isNull()) return null;
        return CanonicalJson.of(mapper, writeJson(declared));
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
            if (cs.id() == null || cs.id().isBlank()) {
                throw new IllegalArgumentException("call_site is missing id (project=" + projectId + ")");
            }
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
            // Written UNCONDITIONALLY, unlike output_schema two lines down. The asymmetry is
            // deliberate and rests on writer count, not on taste: output_schema has TWO writers (the
            // bundle and agentic synthesis), so a silent shard must not clobber the other one's
            // capture and "absent" has to stay distinguishable from "declared empty". `tools` has
            // exactly one writer — the bundle — so bundle silence genuinely means "no tools", and
            // writeJson maps the empty list to SQL NULL accordingly. If a second writer ever appears
            // here, this needs the same absent/declared split (and CallSite.tools would have to stop
            // normalizing null to List.of(), which is what currently erases the distinction).
            values.put(CallSiteColumns.TOOLS_JSON, writeJson(cs.tools()));
            // output_schema has TWO writers — the bundle (here) and agentic synthesis
            // (setCallSiteOutputSchema). The bundle wins when it declares one; when it does not, the
            // column is left for restoreCallSiteOutputSchemas to carry the platform's capture across.
            // A shard that DECLARES the fact writes the column; one that is silent leaves it for
            // restoreCallSiteOutputSchemas to carry the platform's capture across. Declaring an
            // explicit `output_schema: null` is a declaration — "the code has no structured output" —
            // and clears the column. Jackson binds that null to NullNode, not Java null, so the two
            // cases are only distinguishable through isNull(); a bare `!= null` check would store the
            // four-character string "null" and, since that is not SQL NULL, leave the Malformed Output
            // built-in reading it back as a schema forever.
            if (cs.outputSchema() != null) {
                values.put(CallSiteColumns.OUTPUT_SCHEMA, declaredSchemaText(cs));
            }

            // call_site is keyed by (project_id, id) — its id is the producer-chosen tessary.call_site_id
            // string, project-scoped (the same name may recur across projects), not a global ULID.
            Upsert up =
                    Upsert.into(CallSiteColumns.TABLE, values, List.of(CallSiteColumns.PROJECT_ID, CallSiteColumns.ID));
            jdbc.sql(up.sql()).params(up.params()).update();
        }
    }

    // ------------------------------------------------------------------ chains

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

    // ------------------------------------------------------------------ failure modes

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

    // ------------------------------------------------------------------ JSON helpers

    private @Nullable String writeJson(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof List<?> l && l.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("PipelineRepository: failed to serialise JSON for " + value, e);
        }
    }

    /**
     * A stored JSON column back as a raw tree — for fields whose shape is arbitrary by nature (a call
     * site's declared {@code output_schema} is any JSON Schema), where modelling it would be a lie.
     */
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

    /** Internal struct for the single pipeline_meta row. */
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
