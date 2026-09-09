// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CasesPage;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.BehaviorDtos;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.model.CallSite;
import ai.tessary.model.FailureMode;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.CapabilityService.CapabilitySet;
import ai.tessary.query.QueryDataset;
import ai.tessary.query.QueryDtos.CountRequest;
import ai.tessary.query.QueryDtos.CountView;
import ai.tessary.query.QueryDtos.FacetsRequest;
import ai.tessary.query.QueryDtos.FacetsView;
import ai.tessary.query.QueryDtos.SearchRequest;
import ai.tessary.query.QueryDtos.SearchView;
import ai.tessary.query.QueryDtos.TimeRange;
import ai.tessary.query.QueryDtos.TimeseriesRequest;
import ai.tessary.query.QueryDtos.TimeseriesView;
import ai.tessary.query.QueryRepository;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanKey;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionDtos;
import ai.tessary.traces.SessionReadService;
import ai.tessary.traces.TraceDtos;
import ai.tessary.traces.TracePageCodec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Catalogue of MCP tools exposed by this server. Each tool reads the bound project from
 * {@link TenantContext#projectId()} (populated by AuthFilter after token verification) and
 * delegates to the same services REST controllers use.
 *
 * <p>Every tool here reads. Nothing in this catalogue writes a row, spends a token, or starts an
 * agent run, see {@link McpTool}.
 *
 * <p>The buckets of tools:</p>
 * <ul>
 *   <li><b>Project + taxonomy (read-only, ungated):</b> {@code get_project}, {@code list_call_sites},
 *       {@code list_failure_modes}.</li>
 *   <li><b>Cases (read-only):</b> {@code list_cases} / {@code get_case} read the Triage surface, with
 *       the RCA report inlined on the case that owns it. Lifecycle writes stay in the UI, see
 *       {@link #registerCaseTools()}.</li>
 *   <li><b>Query (read-only):</b> {@code query_count} / {@code query_timeseries} / {@code query_facets} /
 *       {@code query_search} over the aggregation-first datasets, and {@code describe_dataset}.</li>
 *   <li><b>Substrate lists (read-only):</b> {@code list_traces}, {@code list_spans},
 *       {@code list_sessions}, {@code get_session}: rollup rows and previews, paged with
 *       {@code limit}/{@code cursor}. See {@link #registerSubstrateListTools()}.</li>
 *   <li><b>Classifiers (read-only):</b> {@code list_findings} / {@code get_finding} read the
 *       behaviour/metric/tool-error drift findings, and {@code get_finding_evidence} pages the
 *       substrate refs one of them measured.</li>
 * </ul>
 */
@Component
public class McpToolRegistry {

    /** Paging convention for every {@code list_*} reader over the substrate: 50 rows by default, 100 max. */
    private static final int LIST_DEFAULT_LIMIT = 50;

    private static final int LIST_MAX_LIMIT = 100;

    /**
     * {@code get_finding_evidence}'s own page bounds, wider than the substrate lists' by a factor of ten.
     * An evidence ref carries no text, just a role, a grain and two ids, and the population it pages can
     * run to six figures for one finding. There is no sampling mode: the caller takes the stride it wants.
     */
    private static final int EVIDENCE_DEFAULT_LIMIT = 100;

    private static final int EVIDENCE_MAX_LIMIT = 1000;

    /**
     * The widest window a payload-bearing {@code list_spans} page may cover when it is not pinned to one
     * trace. 24h is not a size estimate but a shape: a caller who can name a day can name what happened in
     * it, and one who can't is browsing.
     */
    private static final Duration SPAN_PAYLOAD_SCOPE_WINDOW = Duration.ofHours(24);

    /** The {@code fields} value that opts a {@code list_spans} page into full payloads. */
    private static final String PAYLOAD_FIELD = "payload";

    /**
     * The most spans {@code get_trace} renders. Past a couple hundred, a flagged span's neighbourhood
     * drowns in the rest and can exhaust the caller's context before it's read.
     *
     * <p>The cap keeps the OLDEST spans, not the newest: a trace's head is its instructions and first user
     * turn, the part every later span is only intelligible against. {@code spans_truncated} says the list
     * is partial; {@code span_count} says what it's partial of.
     */
    private static final int TRACE_SPAN_CAP = 200;

    /**
     * The case states {@code list_cases} pages, declared once so the schema an agent reads and the check the
     * handler runs cannot drift apart. The persisted vocabulary itself lives on {@link CaseRow.State}; this is
     * only the subset a page may ask for, which today is all of it.
     */
    private static final List<String> CASE_STATES =
            List.of(CaseRow.State.OPEN, CaseRow.State.MUTED, CaseRow.State.RESOLVED);

    private final PipelineService pipelineService;
    private final ProjectRepository projects;
    private final QueryService queryService;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final TraceV2Repository traces;
    private final SessionReadService sessions;
    private final CapabilityService capabilities;
    private final FindingService behaviorDrift;
    private final CaseService cases;

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public McpToolRegistry(
            PipelineService pipelineService,
            ProjectRepository projects,
            QueryService queryService,
            SpanRepository spans,
            SpanPayloadRepository payloads,
            TraceV2Repository traces,
            SessionReadService sessions,
            CapabilityService capabilities,
            FindingService behaviorDrift,
            CaseService cases) {
        this.pipelineService = pipelineService;
        this.projects = projects;
        this.queryService = queryService;
        this.spans = spans;
        this.payloads = payloads;
        this.traces = traces;
        this.sessions = sessions;
        this.capabilities = capabilities;
        this.behaviorDrift = behaviorDrift;
        this.cases = cases;
        register();
    }

    /**
     * Package-private constructor for unit tests that want to exercise the dispatcher with a
     * hand-rolled toolset. Skips the default {@link #register()} so callers fully control which
     * tools exist. The service/repository deps are left null: no default handler that would use
     * them is registered on this path, so they're never dereferenced.
     */
    @SuppressWarnings("NullAway")
    McpToolRegistry(java.util.Collection<McpTool> toolset) {
        this.pipelineService = null;
        this.projects = null;
        this.queryService = null;
        this.spans = null;
        this.payloads = null;
        this.traces = null;
        this.sessions = null;
        this.capabilities = null;
        this.behaviorDrift = null;
        this.cases = null;
        for (McpTool t : toolset) tools.put(t.name(), t);
    }

    public List<McpTool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * The tools this token's org is offered: every tool whose capability it holds, plus every ungated
     * one. This is what {@code tools/list} answers. Offering a tool to an org that does not hold its
     * capability is worse than a 403: the model will plan around a tool that cannot work and burn a
     * turn discovering it.
     */
    public List<McpTool> availableFor(TenantContext ctx) {
        String orgId = ctx.orgId();
        // Skip resolution when there's nothing to resolve for: keeps the hand-rolled-toolset test
        // constructor's null dependencies undereferenced, and a registry with no gated tool has
        // nothing to ask the capability layer anyway.
        if (orgId == null || tools.values().stream().noneMatch(t -> t.capability() != null)) {
            return List.copyOf(tools.values());
        }
        CapabilitySet resolved = capabilities.resolve(orgId);
        List<McpTool> out = new ArrayList<>(tools.size());
        for (McpTool t : tools.values()) {
            if (t.capability() == null || resolved.isEnabled(t.capability())) out.add(t);
        }
        return List.copyOf(out);
    }

    public @Nullable McpTool get(String name) {
        return tools.get(name);
    }

    /**
     * The tool by name, or null when this org is not offered it. Null rather than a distinct
     * "forbidden" answer, so a withheld tool is indistinguishable from one that does not exist:
     * there is nothing a partner can do about a capability it does not hold.
     */
    public @Nullable McpTool availableTool(String name, TenantContext ctx) {
        McpTool tool = tools.get(name);
        if (tool == null || tool.capability() == null) return tool;
        String orgId = ctx.orgId();
        if (orgId == null) return tool;
        return capabilities.isEnabled(orgId, tool.capability()) ? tool : null;
    }

    // ------------------------------------------------------------------ registration

    private void register() {
        // get_project, not list_pipelines: a token binds to exactly one project, so the old name
        // wrongly promised a collection.
        add(new McpTool(
                "get_project",
                "Identify the project this token is bound to: id, slug, name, org, pipeline version, entity"
                        + " counts, engaged packs, the judge runtime, and the watching block — how many"
                        + " classifiers are enabled, how many call sites they sweep, and how many traces arrived"
                        + " in the last 24h. Start here — every other tool is scoped to this project and takes no"
                        + " project argument. Read watching before you call an empty list_cases an all-clear:"
                        + " traces_last_day = 0 means nothing is ARRIVING, which is a different answer from"
                        + " nothing being wrong.",
                schema(Map.of(), List.of()),
                (ctx, args) -> getProject(ctx)));

        add(new McpTool(
                "list_call_sites",
                "List the LLM call sites in this project. Each entry includes id, intent, shape, model/provider, sample_count.",
                schema(Map.of(), List.of()),
                (ctx, args) -> listCallSites(ctx)));

        add(new McpTool(
                "list_failure_modes",
                "List failure modes; filter by call_site_id, chain_id, scope, severity, layer, pack_id, or compliance_tag.",
                schema(
                        Map.ofEntries(
                                Map.entry("call_site_id", strField("Restrict to one call site.")),
                                Map.entry("chain_id", strField("Restrict to one chain.")),
                                Map.entry(
                                        "scope",
                                        enumField(
                                                "single_call | chain | trace",
                                                List.of("single_call", "chain", "trace"))),
                                Map.entry(
                                        "severity", enumField("low | medium | high", List.of("low", "medium", "high"))),
                                Map.entry(
                                        "layer",
                                        enumField(
                                                "A | B | C  (A=mechanical, B=judgmental, C=adversarial/operational)",
                                                List.of("A", "B", "C"))),
                                Map.entry(
                                        "pack_id",
                                        strField(
                                                "Restrict to failures tagged with this pack id (e.g. 'security', 'quality').")),
                                Map.entry(
                                        "compliance_tag",
                                        strField(
                                                "Restrict to failures carrying this compliance control id (e.g. 'EU-AI-Act.Art-13')."))),
                        List.of()),
                (ctx, args) -> listFailureModes(
                        ctx,
                        strArg(args, "call_site_id"),
                        strArg(args, "chain_id"),
                        strArg(args, "scope"),
                        strArg(args, "severity"),
                        strArg(args, "layer"),
                        strArg(args, "pack_id"),
                        strArg(args, "compliance_tag"))));

        // list_graders, get_grader, list_quality_dimensions and propose_grader_edit are gone: grading
        // is not part of this platform, so there is no curated set to read and no rubric to edit.

        // reload_pipeline is gone. It had been a no-op compat shim, and its only remaining caller read
        // its stale `valid: true` as proof an import landed. POST .../import reports its own result.

        registerCaseTools();
        registerQueryTools();
        registerSubstrateListTools();
        registerTraceReadTools();
        registerClassifierFindingTools();
    }

    /**
     * The case surface exposed read-only: {@code list_cases} pages what is wrong with this project (open,
     * worst-first by default) and {@code get_case} is one case's page. Both go through {@link CaseService},
     * the same seam {@code CaseController} uses, so a case reads identically here and in the UI.
     *
     * <p>{@code list_cases} pages and filters rather than the UI's three fixed buckets, so a caller can ask
     * for "open tool_error cases on this call site" directly instead of filtering the whole project's cases
     * itself. {@link CaseService#triage} still composes the buckets for the UI.
     *
     * <p>Ungated deliberately: cases are what the default-on classifiers produce, and gating this behind a
     * capability that's off for every partner would withhold the product's own output. The RCA report a
     * case owns rides along inline for the same reason, via {@link CaseService#detail}.
     *
     * <p>Lifecycle writes ({@code resolve}, {@code absorb}, {@code mute}, {@code unmute}) stay on the
     * controller and are deliberately not tools: each records a human judgement, and {@code absorb} moves
     * the detector's baseline, which an agent should not do on its own reasoning.
     */
    private void registerCaseTools() {
        add(new McpTool(
                "list_cases",
                "List this token's project's cases — what is wrong with it right now. Defaults to state=open,"
                        + " worst-first (severity, then most recently opened), which is the answer to 'what needs"
                        + " me'. Narrow with detector and call_site_id; ask for state=muted (live but silenced) or"
                        + " state=resolved, which pages newest-closure-first instead. Rows are the case itself —"
                        + " title, the basis sentence in its own detector's terms, severity, the subject and its"
                        + " call site, timestamps. Pass a row's id (or the C-118 reference a person quoted) to"
                        + " get_case for its trail, its finding, and the RCA report when one has been written."
                        + " An EMPTY open page does not mean all-clear on its own: read get_project's watching"
                        + " block, where traces_last_day = 0 means nothing is ARRIVING rather than nothing being"
                        + " wrong. Pass the returned next_cursor back as cursor to page.",
                schema(
                        Map.ofEntries(
                                Map.entry(
                                        "state",
                                        enumField(
                                                "Optional. Which cases to page; defaults to 'open'. 'muted' is"
                                                        + " live-but-silenced and still holds its subject;"
                                                        + " 'resolved' is closed history, ordered by when it"
                                                        + " closed rather than by severity.",
                                                CASE_STATES)),
                                Map.entry(
                                        "detector",
                                        strField("Restrict to one detector (e.g. 'behavior_drift',"
                                                + " 'metric_drift', 'tool_error', 'sop_conformance').")),
                                Map.entry(
                                        "call_site_id",
                                        strField("Restrict to cases about one call site. A case whose subject"
                                                + " is not a call site carries none, so it never matches.")),
                                Map.entry("limit", limitField()),
                                Map.entry("cursor", cursorField())),
                        List.of()),
                this::listCases));

        add(new McpTool(
                "get_case",
                "Fetch one case by id, scoped to this token's project: the case, its activity trail, the"
                        + " classifier finding it is about (finding_id — pass it to get_finding), the ruling, the"
                        + " exemplar traces, and the RCA report INLINE in rca when one has finished — its"
                        + " verdict, hypotheses, the checks it ruled out, and the agent's full written"
                        + " investigation. rca is null while a report is still running (rca_report_id names it,"
                        + " so poll) and when none has been run; rca_available says whether one could be."
                        + " Accepts either the stored id or the human reference ('C-118'), so a case number"
                        + " quoted by a person resolves.",
                schema(Map.of("id", strField("Case id, or the human reference such as 'C-118'.")), List.of("id")),
                (ctx, args) -> getCase(ctx, requireStr(args, "id"))));
    }

    /**
     * The aggregation-first Query API exposed as MCP tools: {@code describe_dataset}, {@code query_count},
     * {@code query_timeseries}, {@code query_facets}, {@code query_search}. The four queries proxy to
     * {@link QueryService}, so every read is project-scoped and the dataset allow-list firewall is reused,
     * never bypassed. {@link TessaryException} is caught in each handler and rethrown as
     * {@link McpTool.ToolException} so the caller gets a correctable tool error, not a {@code -32603}.
     *
     * <p>{@code describe_dataset} reaches no service: the answer is {@link QueryDataset} itself, read off
     * its accessors rather than recited in prose that can drift from the enum (as it once did).
     */
    private void registerQueryTools() {
        // Registered first, because it is what a caller should read first: the other four take field names
        // they will otherwise guess.
        add(new McpTool(
                "describe_dataset",
                "Describe the query datasets the other query tools read — for each one, the fields that may be"
                        + " faceted (grouped by), the fields that may be equality-filtered, whether it supports"
                        + " query_search, its time column (what range/interval apply to), and its measure. Pass"
                        + " one dataset to describe just it; omit to describe all of "
                        + datasetList(false) + ". measure is the column that count/timeseries/facets SUM, or null"
                        + " when the answer is a plain row count. Read this instead of guessing a field name: an"
                        + " unknown field is an error, and the fields differ per dataset.",
                schema(
                        Map.of(
                                "dataset",
                                enumField(
                                        "Optional. One dataset to describe; omit to describe every dataset.",
                                        datasetNames(false))),
                        List.of()),
                (ctx, args) -> describeDataset(args)));

        add(new McpTool(
                "query_count",
                "Count rows in an aggregation-first dataset (trace + signal store), scoped to this token's"
                        + " project. Datasets: " + datasetList(false) + ". Optionally"
                        + " narrow by a half-open time range and equality filters on indexed fields; call"
                        + " describe_dataset for the fields each dataset allows."
                        + " On metric_rollups the answer is the SUM of the rollup's value (the rows are already"
                        + " aggregated), not a row count — always filter granularity there so hour and day grains"
                        + " are not mixed into one bucket.",
                schema(
                        Map.ofEntries(
                                Map.entry("dataset", datasetField()),
                                Map.entry("range", rangeField()),
                                Map.entry("filters", filtersField())),
                        List.of("dataset")),
                this::queryCount));

        add(new McpTool(
                "query_timeseries",
                "Bucketed row counts over time for a dataset, scoped to this token's project. Both range bounds"
                        + " are required (an unbounded timeseries is a full scan). Datasets: "
                        + datasetList(false) + ". Intervals: hour | day | week | month. Filter fields are"
                        + " dataset-specific — call describe_dataset. On metric_rollups each"
                        + " bucket sums the rollup's value rather than counting rows, and granularity should"
                        + " always be filtered so two grains are not summed together.",
                schema(
                        Map.ofEntries(
                                Map.entry("dataset", datasetField()),
                                Map.entry(
                                        "interval",
                                        enumField(
                                                "Bucketing interval (required).",
                                                List.of("hour", "day", "week", "month"))),
                                Map.entry("range", rangeField()),
                                Map.entry("filters", filtersField())),
                        List.of("dataset", "interval", "range")),
                this::queryTimeseries));

        add(new McpTool(
                "query_facets",
                "Top-N breakdown of an allow-listed dimension for a dataset, scoped to this token's project,"
                        + " most-frequent first. Datasets: " + datasetList(false) + ". The"
                        + " facetable field is dataset-specific — call describe_dataset for the fields each one"
                        + " allows; an unknown field returns an error naming the dataset. On metric_rollups each"
                        + " bucket's number is the SUM of the rollup's value, not a row count.",
                schema(
                        Map.ofEntries(
                                Map.entry("dataset", datasetField()),
                                Map.entry("field", strField("Dimension to group by (dataset-specific).")),
                                Map.entry("range", rangeField()),
                                Map.entry("filters", filtersField()),
                                Map.entry(
                                        "top_n",
                                        intField(
                                                "Optional. Max distinct values to return (default 20, capped at 100)."))),
                        List.of("dataset", "field")),
                this::queryFacets));

        add(new McpTool(
                "query_search",
                "Search returning a bounded page of matching rows (not aggregated), scoped to this token's"
                        + " project. Datasets: " + datasetList(true) + ". To search spans, use list_spans — it"
                        + " searches the same rows with more filters and returns full payloads when the call is"
                        + " scoped. metric_rollups is not searchable at all: a rollup row has no text. One mode,"
                        + " 'keyword' (the only supported value): a keyset-paginated newest-first scan matching"
                        + " the dataset's allow-listed text columns case-insensitively. Filter fields are"
                        + " dataset-specific — call describe_dataset. Pass the returned next_cursor back as"
                        + " cursor to page.",
                schema(
                        Map.ofEntries(
                                Map.entry("dataset", searchableDatasetField()),
                                Map.entry("q", strField("Optional; matches the dataset's text columns.")),
                                Map.entry(
                                        "mode",
                                        enumField(
                                                "Optional search mode; 'keyword' is the only supported value.",
                                                List.of("keyword"))),
                                Map.entry("range", rangeField()),
                                Map.entry("filters", filtersField()),
                                Map.entry(
                                        "limit",
                                        intField("Optional. Max rows per page (default 100, capped at 1000).")),
                                Map.entry("cursor", strField("Optional. The next_cursor from a previous page."))),
                        List.of("dataset")),
                this::querySearch));
    }

    /**
     * The plural substrate readers: {@code list_traces}, {@code list_spans}, {@code list_sessions} and
     * {@code get_session}. Each wraps the same seam a REST controller or service already reads (cursors are
     * shared via {@link TracePageCodec}, so a cursor minted here is readable there), so a list here cannot
     * disagree with the same list in the UI.
     *
     * <p>Rows are rollup rows and previews, never full payloads; {@code get_trace} / {@code get_span} are
     * where the raw text lives. {@code list_spans} is the one exception: {@code fields: ["payload"]} opts
     * into full text, but only for a page already narrowed to one trace or to
     * {@link #SPAN_PAYLOAD_SCOPE_WINDOW}, see {@link #listSpans}.
     *
     * <p>Paging is the house convention: {@code limit} (default {@value #LIST_DEFAULT_LIMIT}, capped at
     * {@value #LIST_MAX_LIMIT}), {@code cursor} in, {@code next_cursor} out, and a stale cursor restarts at
     * page one rather than erroring.
     */
    private void registerSubstrateListTools() {
        add(new McpTool(
                "list_traces",
                "List this token's project's traces, newest first — one row per producer trace, carrying the"
                        + " ROLLUP columns (span_count, error_count, typed token buckets, costs, is_settled,"
                        + " unpriced_spans) and the stored input_preview/output_preview, NEVER the full payloads."
                        + " Filter by model, kind, call_site_id, status and a started_at range, or"
                        + " keyword-match the trace name and previews with q. A null token or cost column means"
                        + " one of two different things and the row says which: is_settled=false is 'still"
                        + " receiving spans', settled-with-null is 'no span reported usage', and unpriced_spans >"
                        + " 0 means the total is real but incomplete. Pass a row's id to get_trace to read its"
                        + " spans with their full payloads. Pass the returned next_cursor back as cursor to page.",
                schema(
                        Map.ofEntries(
                                Map.entry(
                                        "model",
                                        strField("Restrict to traces holding a span that ran this"
                                                + " model, as the producer named it.")),
                                Map.entry(
                                        "kind",
                                        strField("Restrict to traces holding a span of this kind"
                                                + " (e.g. 'llm', 'tool', 'retrieval').")),
                                Map.entry(
                                        "call_site_id",
                                        strField(
                                                "Restrict to traces holding a span tagged with"
                                                        + " this call site. An untagged span carries none, so it never matches.")),
                                Map.entry(
                                        "status",
                                        enumField(
                                                "Restrict to errored or clean traces. A trace that has not rolled"
                                                        + " up has no error count and is excluded by either value"
                                                        + " rather than counted as healthy.",
                                                List.of("ok", "error"))),
                                Map.entry(
                                        "range",
                                        rangeField(
                                                "Optional window on the trace's started_at, both bounds"
                                                        + " inclusive (ISO-8601).",
                                                "Optional inclusive lower bound (ISO-8601).",
                                                "Optional inclusive upper bound (ISO-8601).")),
                                Map.entry(
                                        "q",
                                        strField("Optional. Case-insensitive match on the trace name and"
                                                + " the stored previews — not the full payload text.")),
                                Map.entry("limit", limitField()),
                                Map.entry("cursor", cursorField())),
                        List.of()),
                this::listTraces));

        add(new McpTool(
                "list_spans",
                "Find spans in this token's project — the step grain: one LLM call, tool call or sub-agent."
                        + " Newest-first, keyset-paged. Filter by trace_id, call_site_id, kind, name, status,"
                        + " model_id, session_id and a half-open created_at range; match text with"
                        + " q. One mode, 'keyword' (the only supported value): matches the span name and the"
                        + " stored previews case-insensitively and pages with cursor. Rows are COMPACT: typed columns"
                        + " plus input_preview/output_preview and payload_available, which says whether the full"
                        + " text still exists (payloads age out ahead of spans, so a row with no preview and"
                        + " payload_available=false is text we no longer hold, not a call with no input). To read"
                        + " full text: get_span for one span, get_trace for a whole conversation, or pass"
                        + " fields=[\"payload\"] here — allowed only on a page scoped to one trace_id or to a range"
                        + " of at most " + SPAN_PAYLOAD_SCOPE_WINDOW.toHours() + "h, and an error otherwise rather"
                        + " than a silently compact page.",
                schema(
                        Map.ofEntries(
                                Map.entry("trace_id", strField("Restrict to the spans of one trace.")),
                                Map.entry(
                                        "call_site_id",
                                        strField("Restrict to spans tagged with this call site. An"
                                                + " untagged span carries none, so it never matches.")),
                                Map.entry("kind", strField("Restrict to one span kind (e.g. 'llm', 'tool').")),
                                Map.entry("name", strField("Restrict to one exact span name.")),
                                Map.entry("status", strField("Restrict to one span status (e.g. 'ok', 'error').")),
                                Map.entry(
                                        "model_id",
                                        strField("Restrict to one resolved catalogue model key — what we"
                                                + " priced the call as, not the string the producer sent.")),
                                Map.entry("session_id", strField("Restrict to one session.")),
                                Map.entry("range", rangeField()),
                                Map.entry(
                                        "q",
                                        strField("Optional; matches the span name and the stored previews,"
                                                + " NOT the full payload text.")),
                                Map.entry(
                                        "mode",
                                        enumField(
                                                "Optional search mode; 'keyword' is the only supported value.",
                                                List.of("keyword"))),
                                Map.entry("fields", payloadFieldsField()),
                                Map.entry("limit", limitField()),
                                Map.entry("cursor", cursorField())),
                        List.of()),
                this::listSpans));

        add(new McpTool(
                "list_sessions",
                "List this token's project's sessions, most recently active first — a session is one continuous"
                        + " interaction with one user, spanning many traces. Rows are identity only (id, user_id,"
                        + " started_at, last_activity_at): a session carries NO rollup, so its"
                        + " totals cost a read of its traces and live on get_session. There is deliberately no"
                        + " sort argument — ordering sessions by cost or tokens would mean summing every session"
                        + " in the project before this page could be chosen. Pass the returned next_cursor back"
                        + " as cursor to page.",
                schema(Map.of("limit", limitField(), "cursor", cursorField()), List.of()),
                this::listSessions));

        add(new McpTool(
                "get_session",
                "Fetch one session by id, scoped to this token's project: its identity, the totals summed from"
                        + " its traces' already-materialized rollup columns, and those traces oldest-first as the"
                        + " same rows list_traces returns (capped at " + SessionReadService.SESSION_TRACE_CAP
                        + ", with traces_truncated saying so). Read unsettled_traces before the totals: it counts"
                        + " the traces still receiving spans, so a non-zero value makes every total below a lower"
                        + " bound rather than a final figure. unpriced_spans is the same caution one level down —"
                        + " spend we hold no rate for and did not price at zero.",
                schema(
                        Map.of("id", strField("Session id, e.g. a trace row's session or a span's session_id.")),
                        List.of("id")),
                (ctx, args) -> getSession(ctx, requireStr(args, "id"))));
    }

    /**
     * The raw trace/span reads: {@code get_span} (one span's full payload, unconditionally) and
     * {@code get_trace} (a trace's rollup row plus every span, skeleton by default, full payloads on
     * {@code fields=["payload"]}). These deliberately step outside the aggregation-first query firewall:
     * {@code query_search} can never return the raw conversation. Both are project-scoped by primary-key
     * prefix, so a cross-tenant id resolves to not-found rather than leaking, the scoping is the lookup, not
     * a filter applied after one.
     *
     * <p>{@code get_span} requires both ids: a producer span id is unique only within its trace, so there is
     * no index to look one up alone, and guessing would silently return a span from the wrong turn. A call
     * with no {@code trace_id} gets a tool error naming the argument to add, not a misleading not-found.
     */
    private void registerTraceReadTools() {
        add(new McpTool(
                "get_span",
                "Fetch one span with its FULL raw payload — input, output, the attributes bag, typed token"
                        + " and cost columns, timings — scoped to this token's project. A span is identified by"
                        + " BOTH trace_id and span_id (a span id is unique only within its trace). Unlike"
                        + " query_search (which matches name/previews but returns only compact metadata), this"
                        + " returns the raw conversation text a span carried, so you can see exactly what it"
                        + " contained (e.g. why a classifier flagged it). Payloads are redacted at ingest.",
                schema(
                        Map.of(
                                "trace_id",
                                        strField("Trace id the span belongs to, e.g. a classifier event's"
                                                + " subject_trace_id. Required — a span id alone does not identify"
                                                + " a span."),
                                "span_id",
                                        strField("Span id within that trace, e.g. a classifier event's"
                                                + " subject_span_id.")),
                        List.of("trace_id", "span_id")),
                this::getSpan));

        add(new McpTool(
                "get_trace",
                "Fetch a whole trace: its rollup row (span/error counts, typed token buckets, costs,"
                        + " is_settled, unpriced_spans) plus its spans, ordered oldest-first, scoped to this"
                        + " token's project. Skeleton by default: every typed column, timing, cost"
                        + " and the stored input/output PREVIEWS, plus payload_available — the same shape"
                        + " list_spans renders without fields. Pass fields=[\"payload\"] to add each span's"
                        + " FULL raw payload (input, output, attributes, typed usage receipt) when you need to"
                        + " read a whole conversation for context, not just its shape — get_span reads one"
                        + " span's payload more cheaply when you already know which one you need. At most "
                        + TRACE_SPAN_CAP + " spans are returned — the OLDEST, so the conversation's head"
                        + " survives — and spans_truncated says when there were more; span_count is the true"
                        + " total. For a trace past the cap, list_spans with trace_id pages the rest. Payloads"
                        + " are redacted at ingest.",
                schema(
                        Map.of(
                                "trace_id", strField("Producer trace id (a span's trace_id)."),
                                "fields", payloadFieldsField()),
                        List.of("trace_id")),
                (ctx, args) -> getTrace(ctx, requireStr(args, "trace_id"), payloadsRequested(args))));
    }

    /**
     * The Classifiers findings read: {@code get_finding} fetches one {@code behavior_finding} row, the same
     * object {@code FindingController}'s {@code GET /findings/{id}} renders. Delegates to
     * {@link FindingService#finding}, which is both the project scope and the capability gate (a finding
     * whose detector this org does not hold reads not-found too).
     *
     * <p>{@code get_finding_evidence} pages the population a finding's claim rests on, one ref per measured
     * row, through the same service and therefore the same gate, so evidence cannot bypass a withheld
     * classifier. Refs are ids; {@code get_trace} / {@code get_span} / {@code list_spans} read them.
     */
    private void registerClassifierFindingTools() {
        // list_findings closes get_finding's dead end: get_finding shipped alone, so an agent could not
        // reach a finding without a human pasting an id from the UI. Returns headline rows without the
        // evidence blob on purpose, the list-then-fetch pair this surface was missing half of.
        add(new McpTool(
                "list_findings",
                "List classifier findings for this token's project — the aggregated causes behind"
                        + " behaviour-drift, metric-drift and tool-error-rate-drift detections. Returns headline"
                        + " rows without the evidence blob; pass an id to get_finding for the full evidence."
                        + " Confirmed findings only by default; pass include='all' for the raw Layer-1 stream,"
                        + " which is a lead list rather than an alert list. Findings whose detector this org does"
                        + " not hold are not returned.",
                schema(
                        Map.ofEntries(
                                Map.entry("status", strField("Restrict to one finding status.")),
                                Map.entry("call_site_id", strField("Restrict to one call site.")),
                                Map.entry(
                                        "detector",
                                        strField("Restrict to one detector (e.g. 'behavior_drift',"
                                                + " 'tool_error').")),
                                Map.entry(
                                        "include",
                                        enumField(
                                                "'confirmed' (default) or 'all' for unconfirmed leads too.",
                                                List.of("confirmed", "all")))),
                        List.of()),
                this::listFindings));

        add(new McpTool(
                "get_finding",
                "Fetch one classifier finding by id, scoped to this token's project — the aggregated cause"
                        + " behind a behaviour-drift, metric-drift, or tool-error-rate-drift detection (many"
                        + " individual firings rolled into one cause), the same object the Classifiers findings"
                        + " page renders. Distinct from a single firing (see the classifier_events dataset in"
                        + " query_search/query_facets) and from an RCA report, which reads inline on the case"
                        + " that owns it (see get_case). For the rows the detector measured, page"
                        + " get_finding_evidence.",
                schema(Map.of("id", strField("Finding id, e.g. from a Classifiers findings page URL.")), List.of("id")),
                (ctx, args) -> getFinding(ctx, requireStr(args, "id"))));

        add(new McpTool(
                "get_finding_evidence",
                "Page the substrate a finding's claim rests on: one ref per row the detector measured, at the"
                        + " grain it measured (role 'member' is the flagged population ENUMERATED, not a sample;"
                        + " 'baseline' is the reference window's rows; 'exemplar' / 'witness' / 'changepoint' are"
                        + " the reading aids). Refs are IDS, not bodies — follow one with get_trace, get_span"
                        + " (both ids) or list_spans. Call it with count_only=true first: that returns the"
                        + " per-role sizes with no rows, so you can decide how much to page before you spend"
                        + " context on it. counts is what SURVIVES and can still be opened; recorded_counts is"
                        + " what the detector wrote at finding-open — counts below recorded means substrate aged"
                        + " out, never a lost write. ZERO under a role is a real answer: behaviour drift,"
                        + " conformance's windowed drift test and metric drift's rolling-control arm all compare"
                        + " against a fitted model, so they have no baseline rows to point at; read that as 'no"
                        + " enumerable reference side', not as missing evidence. Pages in the detector's own"
                        + " order, stably; rank has gaps and is an order, not an index. There is NO sampling"
                        + " mode — if you want a stride or a random draw, take it yourself and say in your"
                        + " citation that you did and what you took.",
                schema(
                        Map.ofEntries(
                                Map.entry(
                                        "finding_id",
                                        strField("Finding id — a list_findings row's id, or a case's finding_id.")),
                                Map.entry(
                                        "role",
                                        enumField(
                                                "Restrict to one evidence role. Omit for the whole set, which"
                                                        + " pages role by role.",
                                                FindingEvidenceRow.Role.ALL)),
                                Map.entry(
                                        "count_only",
                                        boolField("Optional. Return the per-role counts with no rows — the cheap"
                                                + " first call. Rows are omitted rather than empty, and"
                                                + " rows_omitted says so.")),
                                Map.entry(
                                        "limit",
                                        intField("Optional. Max refs in this page (default " + EVIDENCE_DEFAULT_LIMIT
                                                + ", capped at " + EVIDENCE_MAX_LIMIT + ").")),
                                Map.entry(
                                        "cursor",
                                        strField("Optional. The next_cursor from a previous page; an unreadable"
                                                + " or stale token starts again at the FIRST page of the set,"
                                                + " which for an ordered population is page one, not the newest"
                                                + " rows."))),
                        List.of("finding_id")),
                this::getFindingEvidence));
    }

    private BehaviorFindingsView listFindings(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        // Mirrors the controller's own reading of the flag: anything but an explicit "all" means confirmed
        // only, so a typo narrows rather than silently widening a lead list into an alert list.
        boolean confirmedOnly = !"all".equals(strArg(args, "include"));
        try {
            BehaviorFindingsView view = behaviorDrift.findings(
                    projectId,
                    strArg(args, "status"),
                    strArg(args, "call_site_id"),
                    strArg(args, "detector"),
                    confirmedOnly);
            return new BehaviorFindingsView(
                    view.findings().stream()
                            .map(BehaviorDtos.BehaviorFindingView::withoutTriage)
                            .toList(),
                    view.withheld(),
                    view.lane());
        } catch (TessaryException e) {
            String message = e.getMessage();
            throw new McpTool.ToolException(message == null ? "listing findings failed" : message, e);
        }
    }

    /**
     * A finding detail with the triage ruling removed. RCA receives a finding id and nothing else: no
     * ruling, no summary, no rule-outs, not even the fact that a triage pass happened, because "nothing
     * happened here" is a supported RCA conclusion and the only check on the triage gate. The RCA agent
     * runs with its own finding id against this same tool, so the firewall has to hold here too, not only
     * in the dossier and the prompt, and is applied to every caller since the RCA key family is not
     * distinguishable at this layer.
     */
    private static BehaviorFindingDetailView withoutTriage(BehaviorFindingDetailView detail) {
        return new BehaviorFindingDetailView(
                detail.finding().withoutTriage(), detail.metric(), detail.toolError(), detail.baseline());
    }

    /** See {@link #withoutTriage}: the same firewall applies here. */
    private BehaviorFindingDetailView getFinding(TenantContext ctx, String id) {
        String projectId = requireProject(ctx).id();
        try {
            return withoutTriage(behaviorDrift.finding(projectId, id));
        } catch (TessaryException e) {
            String message = e.getMessage();
            throw new McpTool.ToolException(message == null ? "finding not found: " + id : message, e);
        }
    }

    /**
     * {@code get_finding_evidence}: a page of the population a detector enumerated. Delegates to
     * {@link FindingService#findingEvidence}, which carries the same reachability guard {@code get_finding}
     * does, so the gate lives in one place rather than being restated per tool.
     *
     * <p>An unrecognised {@code role} is an error, not an empty page, same reasoning as
     * {@link #listCases}: a client can send anything regardless of the schema, and an empty page here would
     * read as "no evidence" rather than "bad argument".
     */
    private BehaviorDtos.FindingEvidencePage getFindingEvidence(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String findingId = requireStr(args, "finding_id");
        String role = strArg(args, "role");
        if (role != null && !FindingEvidenceRow.Role.ALL.contains(role)) {
            throw new McpTool.ToolException("unknown evidence role: " + role + ". get_finding_evidence filters one of "
                    + FindingEvidenceRow.Role.ALL + ".");
        }
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), EVIDENCE_DEFAULT_LIMIT, EVIDENCE_MAX_LIMIT);
        try {
            return behaviorDrift.findingEvidence(
                    projectId, findingId, role, pageSize, strArg(args, "cursor"), boolArg(args, "count_only"));
        } catch (TessaryException e) {
            String message = e.getMessage();
            throw new McpTool.ToolException(message == null ? "finding not found: " + findingId : message, e);
        }
    }

    private void add(McpTool t) {
        tools.put(t.name(), t);
    }

    // ------------------------------------------------------------------ handlers

    /**
     * {@code get_project}: the bound project, flat.
     *
     * <p>The {@code watching} block lives here rather than on {@code list_cases}: enabled classifiers, call
     * sites swept, traces in the last 24h, the only signal separating "nothing is wrong" from "nothing is
     * arriving". An agent that reads an empty {@code list_cases} without it would report an all-clear for a
     * project that stopped sending traffic. Uses the same {@link CaseService#watching} call Triage does.
     */
    private Map<String, Object> getProject(TenantContext ctx) {
        Project p = requireProject(ctx);
        var pipe = pipelineService.getPipeline(p.id());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", p.id());
        entry.put("slug", p.slug());
        entry.put("name", p.name());
        entry.put("org_id", p.orgId());
        entry.put("version", pipe.version());
        entry.put("product_hint", pipe.productHint());
        entry.put("call_sites", pipe.callSites().size());
        entry.put("chains", pipe.chains().size());
        entry.put("failure_modes", pipe.failureModes().size());
        entry.put("taxonomy_nodes", pipe.taxonomy().size());
        entry.put("packs", pipe.packs().size());
        entry.put("watching", cases.watching(p.id()));

        // Compact per-pack roll-up so the agent can see which concern bundles
        // are engaged without a separate call.
        List<Map<String, Object>> packs = new ArrayList<>();
        for (var pack : pipe.packs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pack.id());
            row.put("name", pack.name());
            row.put("version", pack.version());
            row.put("tier_hint", pack.tierHint());
            row.put("enabled_by", pack.enabledBy());
            packs.add(row);
        }
        entry.put("packs_detail", packs);

        var runtime = pipe.runtime();
        if (runtime != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("judge_model", runtime.judgeModel());
            r.put("judge_temperature", runtime.judgeTemperature());
            r.put("severity_policy", runtime.severityPolicy());
            r.put("redaction_state", runtime.redactionState());
            entry.put("runtime", r);
        }
        return entry;
    }

    private Map<String, Object> listCallSites(TenantContext ctx) {
        Project p = requireProject(ctx);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CallSite cs : pipelineService.getPipeline(p.id()).callSites()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", cs.id());
            row.put("use_case", cs.useCase());
            row.put("provider", cs.provider());
            row.put("model", cs.model());
            row.put("shape", cs.shape());
            row.put("intent", cs.intent());
            row.put("sample_count", cs.sampleCount());
            row.put("source_spans", cs.sourceSpans().size());
            row.put("dataset_path", cs.datasetPath());
            var observed = cs.observed();
            if (observed != null) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("error_rate", observed.errorRate());
                o.put("refusal_rate", observed.refusalRate());
                o.put("p50_latency_ms", observed.p50LatencyMs());
                o.put("p95_latency_ms", observed.p95LatencyMs());
                o.put("p95_tokens_in", observed.p95TokensIn());
                o.put("p95_tokens_out", observed.p95TokensOut());
                o.put("cost_estimate_usd", observed.costEstimateUsd());
                o.put("redaction_state", observed.redactionState());
                row.put("observed", o);
            }
            out.add(row);
        }
        return Map.of("call_sites", out);
    }

    private Map<String, Object> listFailureModes(
            TenantContext ctx,
            @Nullable String callSiteId,
            @Nullable String chainId,
            @Nullable String scope,
            @Nullable String severity,
            @Nullable String layer,
            @Nullable String packId,
            @Nullable String complianceTag) {
        Project p = requireProject(ctx);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FailureMode fm : pipelineService.getPipeline(p.id()).failureModes()) {
            if (callSiteId != null && !callSiteId.equals(fm.callSiteId())) continue;
            if (chainId != null && !chainId.equals(fm.chainId())) continue;
            if (scope != null && !scope.equals(fm.scope())) continue;
            if (severity != null && !severity.equals(fm.severity())) continue;
            if (layer != null && !layer.equals(fm.layer())) continue;
            if (packId != null && !fm.packIds().contains(packId)) continue;
            if (complianceTag != null && !fm.complianceTags().contains(complianceTag)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fm.id());
            row.put("name", fm.name());
            row.put("description", fm.description());
            row.put("severity", fm.severity());
            row.put("scope", fm.scope());
            row.put("call_site_id", fm.callSiteId());
            row.put("chain_id", fm.chainId());
            row.put("layer", fm.layer());
            row.put("pack_ids", fm.packIds());
            row.put("compliance_tags", fm.complianceTags());
            row.put("taxonomy_node_id", fm.taxonomyNodeId());
            out.add(row);
        }
        return Map.of("failure_modes", out);
    }

    // ------------------------------------------------------------------ case handlers

    /**
     * {@code list_cases}: one filtered keyset page, clamped to this surface's own maximum. Defaults to
     * {@code state=open} since "what is wrong with this project" is not a question about resolved cases.
     *
     * <p>An unrecognised {@code state} is an error, not an empty page: a client can ignore the schema's
     * three values, and a silent empty page for a typo like {@code "closed"} would read as "nothing wrong"
     * rather than as a bad argument.
     */
    private CasesPage listCases(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String state = Objects.requireNonNullElse(strArg(args, "state"), CaseRow.State.OPEN);
        if (!CASE_STATES.contains(state)) {
            throw new McpTool.ToolException(
                    "unknown state: " + state + ". list_cases pages one of " + CASE_STATES + ".");
        }
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        try {
            return cases.page(
                    projectId,
                    state,
                    strArg(args, "detector"),
                    strArg(args, "call_site_id"),
                    pageSize,
                    strArg(args, "cursor"));
        } catch (TessaryException e) {
            String message = e.getMessage();
            throw new McpTool.ToolException(message == null ? "listing cases failed" : message, e);
        }
    }

    private CaseDetailView getCase(TenantContext ctx, String id) {
        String projectId = requireProject(ctx).id();
        try {
            CaseDetailView detail = cases.detail(projectId, id);
            // Same firewall as get_finding: a case's `ruling` is the triage ruling RCA must not read about
            // the finding it's investigating. `rca` is deliberately not stripped: the firewall is about
            // triage, and an earlier RCA report is this lane's own prior work, not the gate it checks.
            return new CaseDetailView(
                    detail.caseView(),
                    detail.events(),
                    detail.findingId(),
                    null,
                    detail.exemplars(),
                    detail.rcaReportId(),
                    detail.rca(),
                    detail.metric(),
                    detail.toolError(),
                    detail.rcaAvailable(),
                    detail.absorbAvailable(),
                    detail.detectorAvailable());
        } catch (TessaryException e) {
            String message = e.getMessage();
            throw new McpTool.ToolException(message == null ? "case not found: " + id : message, e);
        }
    }

    // ------------------------------------------------------------------ query handlers

    /**
     * {@code describe_dataset}: every field is read off {@link QueryDataset}'s accessors, so this cannot
     * disagree with what {@link QueryService} accepts.
     *
     * <p>No {@link #requireProject}: this reads no rows, the answer is identical for every token, and
     * requiring a resolvable project would only fail introspection for a token whose project was deleted.
     */
    private Map<String, Object> describeDataset(Map<String, Object> args) {
        String wire = strArg(args, "dataset");
        List<QueryDataset> selected;
        try {
            selected = wire == null ? List.of(QueryDataset.values()) : List.of(QueryDataset.fromWire(wire));
        } catch (TessaryException e) {
            throw queryError(e);
        }
        List<Map<String, Object>> described = new ArrayList<>(selected.size());
        for (QueryDataset d : selected) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataset", d.wireName());
            m.put("facet_fields", List.copyOf(d.facetFields()));
            m.put("filter_fields", List.copyOf(d.filterFields()));
            m.put("searchable", d.supportsSearch());
            // Which tool searches it, not just whether it's searchable: spans supports search but moved to
            // list_spans, so reporting only `searchable: true` sent agents to query_search(dataset=spans),
            // which succeeds but silently returns the wrong, thinner reader.
            m.put("searched_by", searchToolFor(d));
            m.put("time_column", d.timeColumn());
            m.put("measure", d.measureColumn());
            described.add(m);
        }
        return Map.of("datasets", described);
    }

    private CountView queryCount(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        CountRequest req = new CountRequest(requireStr(args, "dataset"), rangeArg(args), filtersArg(args));
        try {
            return CountView.of(queryService.count(projectId, req));
        } catch (TessaryException e) {
            throw queryError(e);
        }
    }

    private TimeseriesView queryTimeseries(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        TimeseriesRequest req = new TimeseriesRequest(
                requireStr(args, "dataset"), requireStr(args, "interval"), rangeArg(args), filtersArg(args));
        try {
            return TimeseriesView.of(queryService.timeseries(projectId, req));
        } catch (TessaryException e) {
            throw queryError(e);
        }
    }

    private FacetsView queryFacets(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String field = requireStr(args, "field");
        FacetsRequest req = new FacetsRequest(
                requireStr(args, "dataset"), field, rangeArg(args), filtersArg(args), intArg(args, "top_n"));
        try {
            return FacetsView.of(field, queryService.facets(projectId, req));
        } catch (TessaryException e) {
            throw queryError(e);
        }
    }

    private SearchView querySearch(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        SearchRequest req = new SearchRequest(
                requireStr(args, "dataset"),
                strArg(args, "q"),
                strArg(args, "mode"),
                rangeArg(args),
                filtersArg(args),
                intArg(args, "limit"),
                strArg(args, "cursor"));
        try {
            return SearchView.of(queryService.search(projectId, req));
        } catch (TessaryException e) {
            throw queryError(e);
        }
    }

    /**
     * Map a {@link QueryService} validation failure to a {@link McpTool.ToolException} the LLM can
     * correct, not a {@code -32603} internal error.
     */
    private static McpTool.ToolException queryError(TessaryException e) {
        String message = e.getMessage();
        return new McpTool.ToolException(message == null ? "query failed" : message, e);
    }

    // ------------------------------------------------------------------ substrate list handlers

    /**
     * {@code list_traces}: the REST traces list, argument-for-argument, minus the sort. {@code sort} is
     * passed as null on purpose: the REST orderings are a table's affordance for a person scanning columns,
     * whereas "which traces cost the most" is an aggregation the query tools answer more cheaply. Newest-
     * first also keeps a cursor minted here readable by the REST side's cursor decoder.
     */
    private TraceDtos.TracesPage listTraces(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        TimeRange range = rangeArg(args);
        var query = new TraceV2Repository.TraceQuery(
                strArg(args, "model"),
                strArg(args, "kind"),
                strArg(args, "call_site_id"),
                range == null ? null : range.from(),
                range == null ? null : range.to(),
                strArg(args, "status"),
                strArg(args, "q"));
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        TracePageCodec.Key before = TracePageCodec.decode(strArg(args, "cursor"));
        // Over-fetch by one; the codec turns the extra row into next_cursor and drops it from the page.
        List<TraceV2Repository.Summary> rows =
                traces.list(projectId, query, null, pageSize + 1, before.sortValue(), before.startedAt(), before.id());
        TracePageCodec.Page page = TracePageCodec.trim(rows, pageSize, null);
        return new TraceDtos.TracesPage(
                page.rows().stream().map(TraceDtos::item).toList(), page.nextCursor());
    }

    /**
     * {@code list_spans}: the spans dataset's own search, rendered as span rows.
     *
     * <p>Retrieval is {@link QueryService#search}, not a second query, so this handler contributes only
     * filters, the page bound and rendering. The typed filters are lifted out of the generic
     * {@code filters} bag because they are the eight a caller actually reaches for. The page is clamped to
     * this surface's own cap ({@value #LIST_MAX_LIMIT}), tighter than {@link QueryService}'s REST-sized
     * default, since these rows can carry whole conversations.
     *
     * <p>Rendering re-reads the span rows: the search projection is a handle and a few metadata columns, so
     * the page's identities are hydrated from {@code span} in one query, then re-ordered back into the
     * search's ranking (SQL has no order over an id set). A span the retention sweep removed between the
     * two reads is dropped rather than rendered half-present.
     *
     * <p>Payloads are one query for the page or none at all: requested, the whole page comes back in a
     * single keyed read; otherwise {@code payload_available} is an index-only existence probe.
     */
    private Map<String, Object> listSpans(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String traceId = strArg(args, "trace_id");
        TimeRange range = rangeArg(args);
        boolean withPayloads = payloadsRequested(args);
        if (withPayloads) {
            requirePayloadScope(traceId, range);
        }

        Map<String, String> filters = new LinkedHashMap<>();
        putFilter(filters, "trace_id", traceId);
        putFilter(filters, "call_site_id", strArg(args, "call_site_id"));
        putFilter(filters, "kind", strArg(args, "kind"));
        putFilter(filters, "name", strArg(args, "name"));
        putFilter(filters, "status", strArg(args, "status"));
        putFilter(filters, "model_id", strArg(args, "model_id"));
        putFilter(filters, "session_id", strArg(args, "session_id"));

        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        SearchRequest req = new SearchRequest(
                QueryDataset.SPANS.wireName(),
                strArg(args, "q"),
                strArg(args, "mode"),
                range,
                filters,
                pageSize,
                strArg(args, "cursor"));
        QueryRepository.SearchPage page;
        try {
            page = queryService.search(projectId, req);
        } catch (TessaryException e) {
            throw queryError(e);
        }

        List<SpanKey> keys = new ArrayList<>(page.rows().size());
        for (QueryRepository.SearchRow row : page.rows()) {
            String rowTrace = row.fields().get("trace_id");
            String rowSpan = row.fields().get("span_id");
            // A row missing either id means the projection changed under us; there is nothing to render.
            if (rowTrace != null && rowSpan != null) keys.add(new SpanKey(rowTrace, rowSpan));
        }

        Map<String, SpanRow> spanByHandle = new LinkedHashMap<>();
        for (SpanRow row : spans.listByKeys(projectId, keys)) {
            spanByHandle.put(row.traceId() + ':' + row.id(), row);
        }
        Map<String, SpanPayloadRow> payloadByHandle = new LinkedHashMap<>();
        Set<SpanKey> withText = Set.of();
        if (withPayloads) {
            for (SpanPayloadRow row : payloads.listByKeys(projectId, keys)) {
                payloadByHandle.put(row.traceId() + ':' + row.spanId(), row);
            }
        } else {
            withText = payloads.existingKeys(projectId, keys);
        }

        List<Map<String, Object>> rows = new ArrayList<>(keys.size());
        for (SpanKey key : keys) {
            SpanRow span = spanByHandle.get(key.handle());
            if (span == null) continue;
            boolean available = withPayloads ? payloadByHandle.containsKey(key.handle()) : withText.contains(key);
            rows.add(spanListRow(span, available, withPayloads, payloadByHandle.get(key.handle())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spans", rows);
        out.put("next_cursor", page.nextCursor());
        return out;
    }

    /**
     * Whether this call opted into full payloads, and a hard error on any other {@code fields} value: a
     * caller that asked for something and silently got a page without it would read the page as complete.
     *
     * <p>Shared by {@code list_spans} and {@code get_trace}, both use the identical
     * {@code fields=["payload"]} opt-in (see {@link #payloadFieldsField}), validated here for either.
     */
    private static boolean payloadsRequested(Map<String, Object> args) {
        Object v = args.get("fields");
        if (v == null) return false;
        if (!(v instanceof List<?> raw)) {
            throw new McpTool.ToolException(
                    "argument fields must be an array of strings, e.g. [\"" + PAYLOAD_FIELD + "\"]");
        }
        boolean payload = false;
        for (Object entry : raw) {
            if (PAYLOAD_FIELD.equals(entry)) {
                payload = true;
            } else {
                throw new McpTool.ToolException("unknown fields entry: " + entry + ". The only value this tool's"
                        + " fields argument accepts is \"" + PAYLOAD_FIELD + "\" (full input/output/attributes);"
                        + " every other column is already on every row.");
            }
        }
        return payload;
    }

    /**
     * The payload scope rule: full text only for a page pinned to one trace, or to a window of at most
     * {@link #SPAN_PAYLOAD_SCOPE_WINDOW}. It fails loudly rather than silently returning compact rows: an
     * agent that asked for full text and got 200-character previews has no way to know it's reading a
     * truncation, and would reason over a cut-off prompt as if it were whole.
     *
     * <p>A one-sided range does not qualify: {@code {"from": ...}} with no {@code to} means "everything
     * since", unbounded going forward. Reading the open bound as "now" would make the rule depend on the
     * server clock, so it stays decidable from the arguments alone.
     */
    private static void requirePayloadScope(@Nullable String traceId, @Nullable TimeRange range) {
        if (traceId != null) return;
        String from = range == null ? null : range.from();
        String to = range == null ? null : range.to();
        if (from == null || to == null) {
            throw payloadScopeError(
                    range == null
                            ? "the call names no trace_id and no range"
                            : "the call names no trace_id and range is open at one end");
        }
        Duration covered = Duration.between(parseBound("from", from), parseBound("to", to));
        if (covered.compareTo(SPAN_PAYLOAD_SCOPE_WINDOW) > 0) {
            throw payloadScopeError("the range covers " + covered.toHours() + "h");
        }
    }

    private static McpTool.ToolException payloadScopeError(String why) {
        return new McpTool.ToolException("list_spans will not return payloads for an unscoped page: " + why
                + ". fields=[\"" + PAYLOAD_FIELD + "\"] is allowed only when the call names a trace_id, or when"
                + " range.from and range.to are both set and at most " + SPAN_PAYLOAD_SCOPE_WINDOW.toHours()
                + "h apart. Add trace_id, narrow the range, or drop fields and read the previews —"
                + " payload_available says which rows still have full text, and get_span / get_trace return it.");
    }

    /**
     * A range bound as an instant, for measuring the payload window only. The query layer never parses
     * these, so a bound well-formed enough for Postgres but not {@link OffsetDateTime} would reach here;
     * naming the bound in the error tells the caller which one to fix.
     */
    private static Instant parseBound(String which, String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new McpTool.ToolException(
                    "range." + which + " must be an ISO-8601 instant with an offset (e.g."
                            + " 2026-08-17T00:00:00Z) to be checked against the payload scope window; got: " + iso,
                    e);
        }
    }

    /** Add a typed convenience filter under the spans dataset's own field name, when the caller supplied it. */
    private static void putFilter(Map<String, String> filters, String field, @Nullable String value) {
        if (value != null) filters.put(field, value);
    }

    /**
     * One compact span row: the typed columns and the stored previews, plus {@code payload_available} and
     * the payload itself only when the caller passed the scope rule.
     *
     * <p>{@code payload_available} says a payload row exists, not "has text": payloads age out ahead of the
     * spans that own them, so false means "we no longer hold what this call said", while true beside an
     * empty {@code input_preview} means the call genuinely had no input.
     *
     * <p>{@code created_at} rides beside {@code started_at} because they're different clocks: started_at is
     * the producer's own timing, created_at is when we received it and the column this page's {@code range}
     * and keyset actually run on.
     */
    private static Map<String, Object> spanListRow(
            SpanRow s, boolean payloadAvailable, boolean includePayload, @Nullable SpanPayloadRow payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace_id", s.traceId());
        m.put("span_id", s.id());
        m.put("parent_span_id", s.parentSpanId());
        m.put("kind", s.kind());
        m.put("name", s.name());
        m.put("status", s.status());
        m.put("error_type", s.errorType());
        m.put("call_site_id", s.callSiteId());
        m.put("session_id", s.sessionId());
        m.put("user_id", s.userId());
        m.put("provided_model_name", s.providedModelName());
        m.put("model_id", s.modelId());
        m.put("started_at", s.startedAt());
        m.put("created_at", s.createdAt());
        m.put("latency_ms", s.latencyMs());
        m.put("total_tokens", s.totalTokens());
        m.put("total_cost", s.totalCost());
        // A null cost is only readable through cost_source: unpriced means we hold no rate, not that
        // the call was free.
        m.put("cost_source", s.costSource());
        m.put("input_preview", s.inputPreview());
        m.put("output_preview", s.outputPreview());
        m.put("payload_available", payloadAvailable);
        if (includePayload) {
            // Keys are always present when requested: null text beside payload_available=false is honest
            // for an aged-out payload, and an absent key would read as "not asked for".
            m.put("input", payload == null ? null : payload.input());
            m.put("output", payload == null ? null : payload.output());
            m.put("attributes", payload == null ? null : payload.attributes());
            m.put("provided_usage", payload == null ? null : payload.providedUsage());
        }
        return m;
    }

    /**
     * {@code list_sessions}: {@link SessionReadService}'s page verbatim, identity rows, recency order, no
     * totals. A caller wanting a session's totals uses {@code get_session}.
     */
    private SessionDtos.SessionsPage listSessions(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        return sessions.page(projectId, pageSize, strArg(args, "cursor"), false);
    }

    /**
     * {@code get_session}: the session detail the REST endpoint renders, unchanged.
     *
     * <p>Absent means not-found here as it does over HTTP, because the project scoping IS the lookup: a
     * session id belonging to another tenant resolves to nothing rather than to a forbidden.
     */
    private SessionDtos.SessionDetail getSession(TenantContext ctx, String id) {
        String projectId = requireProject(ctx).id();
        return sessions.detail(projectId, id).orElseThrow(() -> new McpTool.ToolException("session not found: " + id));
    }

    // ------------------------------------------------------------------ trace/span read handlers

    /**
     * {@code get_span}: one span by its full identity, payload included.
     *
     * <p>{@code id} is accepted as a synonym for {@code span_id} so a caller that already learned the new
     * {@code trace_id} argument is not broken twice over one release. A call with no {@code trace_id} at
     * all is the pre-cutover shape, and it fails with the rule rather than with a not-found: the id it
     * sent may well name a real span, just not one that can be found without its trace.
     */
    private Map<String, Object> getSpan(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String traceId = strArg(args, "trace_id");
        String spanId = strArg(args, "span_id");
        if (spanId == null) spanId = strArg(args, "id");
        if (traceId == null) {
            throw new McpTool.ToolException("get_span requires trace_id: a span is identified by"
                    + " (trace_id, span_id) — a span id is unique only within its trace, so it cannot be"
                    + " looked up on its own. Pass the trace_id you saw alongside it (a classifier event's"
                    + " subject_trace_id, a search row's trace_id, or the trace_id from get_trace).");
        }
        if (spanId == null) {
            throw new McpTool.ToolException("missing required argument: span_id");
        }
        String resolvedSpanId = spanId;
        SpanRow span = spans.findById(projectId, traceId, resolvedSpanId)
                .orElseThrow(() -> new McpTool.ToolException("span not found: " + traceId + ":" + resolvedSpanId));
        return spanView(span, payloads.find(projectId, traceId, resolvedSpanId).orElse(null));
    }

    /**
     * {@code get_trace}: the trace's rollup row plus its spans, both read by primary-key prefix.
     * {@code is_settled} ships alongside the rollup so an in-flight trace's numbers read as provisional.
     *
     * <p>Skeleton by default: {@code withPayloads} mirrors {@code list_spans}'s {@code fields=["payload"]}
     * opt-in ({@link #payloadsRequested}). A trace investigated for a triage/RCA ruling is routinely
     * hundreds of spans of which an agent cites a handful, so shipping every span's full text by default
     * was most of a run's context spend for text never read. {@code get_span} or
     * {@code fields=["payload"]} here opt back in.
     *
     * <p>Payloads are read in one query for the page, keyed to the spans actually rendered, not per span
     * and not for the whole trace: a six-figure trace would otherwise pull six figures of rows through
     * memory to render {@value #TRACE_SPAN_CAP} of them. The read itself asks for
     * {@code TRACE_SPAN_CAP + 1} rows so the extra row's existence answers {@code spans_truncated} without
     * a second query.
     */
    private Map<String, Object> getTrace(TenantContext ctx, String traceId, boolean withPayloads) {
        String projectId = requireProject(ctx).id();
        var trace = traces.findById(projectId, traceId)
                .orElseThrow(() -> new McpTool.ToolException("trace not found: " + traceId));
        List<SpanRow> all = spans.listByTrace(projectId, traceId, TRACE_SPAN_CAP + 1);
        boolean truncated = all.size() > TRACE_SPAN_CAP;
        // Oldest-first is listByTrace's own order, so the cap keeps the conversation's head, the part
        // later spans are only intelligible against.
        List<SpanRow> rendered = truncated ? all.subList(0, TRACE_SPAN_CAP) : all;
        List<SpanKey> keys = new ArrayList<>(rendered.size());
        for (SpanRow span : rendered) keys.add(new SpanKey(span.traceId(), span.id()));

        List<Map<String, Object>> spanViews = new ArrayList<>(rendered.size());
        if (withPayloads) {
            Map<String, SpanPayloadRow> payloadBySpan = new LinkedHashMap<>();
            for (SpanPayloadRow row : payloads.listByKeys(projectId, keys)) {
                payloadBySpan.put(row.spanId(), row);
            }
            for (SpanRow span : rendered) {
                SpanPayloadRow payload = payloadBySpan.get(span.id());
                spanViews.add(traceSpanView(span, payload != null, true, payload));
            }
        } else {
            // Same skeleton-mode cost as list_spans' compact page: an index-only existence probe, no
            // payload text read at all.
            Set<SpanKey> withText = payloads.existingKeys(projectId, keys);
            for (int i = 0; i < rendered.size(); i++) {
                spanViews.add(traceSpanView(rendered.get(i), withText.contains(keys.get(i)), false, null));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trace_id", trace.id());
        out.put("session_id", trace.sessionId());
        out.put("thread_id", trace.threadId());
        out.put("name", trace.name());
        out.put("user_id", trace.userId());
        out.put("status", trace.status());
        out.put("started_at", trace.startedAt());
        out.put("ended_at", trace.endedAt());
        out.put("latency_ms", trace.latencyMs());
        // The rollup, verbatim. A null counter means "not rolled up yet" and is NOT zero; is_settled is
        // the companion fact that says which of the two a null is.
        out.put("span_count", trace.spanCount());
        out.put("error_count", trace.errorCount());
        out.put("input_tokens", trace.inputTokens());
        out.put("output_tokens", trace.outputTokens());
        out.put("cache_read_tokens", trace.cacheReadTokens());
        out.put("cache_write_tokens", trace.cacheWriteTokens());
        out.put("reasoning_tokens", trace.reasoningTokens());
        out.put("total_tokens", trace.totalTokens());
        out.put("input_cost", trace.inputCost());
        out.put("output_cost", trace.outputCost());
        out.put("total_cost", trace.totalCost());
        // Load-bearing beside the total: spans running models we hold no rate for are counted, not priced
        // at zero, so a low total is never mistaken for a cheap turn.
        out.put("unpriced_spans", trace.unpricedSpans());
        out.put("is_settled", trace.isSettled());
        out.put("call_site_id", trace.callSiteId());
        // span_count above is the total the cap is measured against; no separate span_count_total needed.
        out.put("spans_truncated", truncated);
        out.put("spans", spanViews);
        return out;
    }

    /**
     * The full raw view of a span for {@code get_span}/{@code get_trace}: the typed columns plus the
     * payload's text and jsonb. A {@link LinkedHashMap} (not {@code Map.of}) so the many {@code @Nullable}
     * columns reach the wire as explicit JSON nulls instead of tripping {@code Map.of}'s NPE-on-null.
     *
     * <p>A null cost is not a zero: {@code cost_source} says whether it's {@code provided}, {@code inferred},
     * or {@code unpriced} (we hold no rate for that model). {@code payload} may be entirely absent when a
     * span outlives the payload retention window, a different statement from a span that carried no input.
     */
    private static Map<String, Object> spanView(SpanRow s, @Nullable SpanPayloadRow payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace_id", s.traceId());
        m.put("span_id", s.id());
        m.put("parent_span_id", s.parentSpanId());
        // A null path means ancestry is unresolved, not "this is the root". Root is parent_span_id == null.
        m.put("path", s.path());
        m.put("depth", s.depth());
        m.put("kind", s.kind());
        m.put("name", s.name());
        m.put("is_logical_root", s.isLogicalRoot());
        m.put("session_id", s.sessionId());
        m.put("user_id", s.userId());
        m.put("thread_name", s.traceName());
        m.put("call_site_id", s.callSiteId());
        m.put("project_version_id", s.projectVersionId());
        m.put("status", s.status());
        m.put("level", s.level());
        m.put("error_type", s.errorType());
        m.put("provided_model_name", s.providedModelName());
        m.put("model_id", s.modelId());
        m.put("input_tokens", s.inputTokens());
        m.put("output_tokens", s.outputTokens());
        m.put("cache_read_tokens", s.cacheReadTokens());
        m.put("cache_write_tokens", s.cacheWriteTokens());
        m.put("reasoning_tokens", s.reasoningTokens());
        m.put("total_tokens", s.totalTokens());
        m.put("input_cost", s.inputCost());
        m.put("output_cost", s.outputCost());
        m.put("cache_read_cost", s.cacheReadCost());
        m.put("cache_write_cost", s.cacheWriteCost());
        m.put("total_cost", s.totalCost());
        m.put("cost_source", s.costSource());
        m.put("price_book_version", s.priceBookVersion());
        m.put("latency_ms", s.latencyMs());
        m.put("ttft_ms", s.ttftMs());
        m.put("started_at", s.startedAt());
        m.put("ended_at", s.endedAt());
        m.put("event_ts", s.eventTs());
        m.put("created_at", s.createdAt());
        m.put("is_deleted", s.isDeleted());
        m.put("input", payload == null ? null : payload.input());
        m.put("output", payload == null ? null : payload.output());
        m.put("attributes", payload == null ? null : payload.attributes());
        // The producer's raw usage object, kept as a receipt. Never read for arithmetic; the typed
        // columns above are the numbers, this is what arrived.
        m.put("provided_usage", payload == null ? null : payload.providedUsage());
        return m;
    }

    /**
     * One span for {@code get_trace}, in either of its two modes: every column {@link #spanView} renders,
     * plus {@code payload_available} and the stored previews, but {@code input}/{@code output}/
     * {@code attributes}/{@code provided_usage} only when the caller opted in. Deliberately not a
     * {@code spanView} overload: {@code get_span}'s full-payload contract is untouched by this.
     */
    private static Map<String, Object> traceSpanView(
            SpanRow s, boolean payloadAvailable, boolean includePayload, @Nullable SpanPayloadRow payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace_id", s.traceId());
        m.put("span_id", s.id());
        m.put("parent_span_id", s.parentSpanId());
        m.put("path", s.path());
        m.put("depth", s.depth());
        m.put("kind", s.kind());
        m.put("name", s.name());
        m.put("is_logical_root", s.isLogicalRoot());
        m.put("session_id", s.sessionId());
        m.put("user_id", s.userId());
        m.put("thread_name", s.traceName());
        m.put("call_site_id", s.callSiteId());
        m.put("project_version_id", s.projectVersionId());
        m.put("status", s.status());
        m.put("level", s.level());
        m.put("error_type", s.errorType());
        m.put("provided_model_name", s.providedModelName());
        m.put("model_id", s.modelId());
        m.put("input_tokens", s.inputTokens());
        m.put("output_tokens", s.outputTokens());
        m.put("cache_read_tokens", s.cacheReadTokens());
        m.put("cache_write_tokens", s.cacheWriteTokens());
        m.put("reasoning_tokens", s.reasoningTokens());
        m.put("total_tokens", s.totalTokens());
        m.put("input_cost", s.inputCost());
        m.put("output_cost", s.outputCost());
        m.put("cache_read_cost", s.cacheReadCost());
        m.put("cache_write_cost", s.cacheWriteCost());
        m.put("total_cost", s.totalCost());
        m.put("cost_source", s.costSource());
        m.put("price_book_version", s.priceBookVersion());
        m.put("latency_ms", s.latencyMs());
        m.put("ttft_ms", s.ttftMs());
        m.put("started_at", s.startedAt());
        m.put("ended_at", s.endedAt());
        m.put("event_ts", s.eventTs());
        m.put("created_at", s.createdAt());
        m.put("is_deleted", s.isDeleted());
        m.put("input_preview", s.inputPreview());
        m.put("output_preview", s.outputPreview());
        m.put("payload_available", payloadAvailable);
        if (includePayload) {
            m.put("input", payload == null ? null : payload.input());
            m.put("output", payload == null ? null : payload.output());
            m.put("attributes", payload == null ? null : payload.attributes());
            m.put("provided_usage", payload == null ? null : payload.providedUsage());
        }
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private Project requireProject(TenantContext ctx) {
        if (ctx == null || ctx.projectId() == null) {
            throw new McpTool.ToolException("no project bound to this token");
        }
        return projects.findById(ctx.projectId())
                .orElseThrow(() -> new McpTool.ToolException("token project no longer exists"));
    }

    private static Map<String, Object> schema(Map<String, Map<String, Object>> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (!required.isEmpty()) s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }

    private static Map<String, Object> strField(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> enumField(String description, List<String> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", description);
        m.put("enum", values);
        return m;
    }

    private static Map<String, Object> intField(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> boolField(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    // ---- query-tool schema fragments ----------------------------------------------------

    /**
     * The allow-listed dataset enum, derived from {@link QueryDataset} rather than restated: a hand-written
     * list here once drifted from the enum, so the schema's {@code additionalProperties: false} silently
     * made a real dataset unreachable over MCP until a literal was updated to match.
     */
    private static Map<String, Object> datasetField() {
        return enumField("Dataset to query: " + datasetList(false) + ".", datasetNames(false));
    }

    /**
     * The narrower enum for {@code query_search}: datasets that {@linkplain QueryDataset#supportsSearch
     * support search}, minus {@code spans}. {@code spans} is excluded by decision, not capability:
     * {@code list_spans} already does the same keyword search with a richer filter set, so leaving spans
     * here would give the model two tools answering one question. {@link QueryService} still accepts
     * {@code dataset=spans} on search; only the MCP schema narrows.
     */
    private static Map<String, Object> searchableDatasetField() {
        return enumField("Dataset to search: " + datasetList(true) + ".", datasetNames(true));
    }

    /**
     * Which MCP tool searches this dataset, or null when nothing does. The one place that knows spans
     * search lives on {@code list_spans}, not {@code query_search}; {@code describe_dataset} reports it and
     * {@link #datasetNames(boolean)} enforces it so introspection and the allowed enum cannot disagree.
     */
    private static @Nullable String searchToolFor(QueryDataset d) {
        if (!d.supportsSearch()) return null;
        return d == QueryDataset.SPANS ? "list_spans" : "query_search";
    }

    /**
     * The wire names of every queryable dataset, optionally narrowed to the ones {@code query_search}
     * advertises (see {@link #searchableDatasetField()} for why that is narrower than {@code supportsSearch}).
     */
    private static List<String> datasetNames(boolean searchableOnly) {
        List<String> out = new ArrayList<>();
        for (QueryDataset d : QueryDataset.values()) {
            if (!searchableOnly || (d.supportsSearch() && d != QueryDataset.SPANS)) out.add(d.wireName());
        }
        return List.copyOf(out);
    }

    /** The same names rendered for prose, so a tool's description cannot disagree with its own schema. */
    private static String datasetList(boolean searchableOnly) {
        return String.join(" | ", datasetNames(searchableOnly));
    }

    /**
     * A half-open time window {@code [from, to)} on the dataset's time column (ISO-8601 strings). Named as
     * "the dataset's time column" rather than {@code created_at} because {@code metric_rollups} buckets on
     * {@code bucket_start} instead.
     */
    private static Map<String, Object> rangeField() {
        return rangeField(
                "Optional half-open time window [from, to) on the dataset's time column — created_at,"
                        + " or bucket_start for metric_rollups (ISO-8601).",
                "Optional inclusive lower bound (ISO-8601).",
                "Optional exclusive upper bound (ISO-8601).");
    }

    /**
     * The same {@code {from, to}} object under caller-supplied descriptions: the query datasets take a
     * half-open window, the traces list filters {@code started_at} with both bounds inclusive, and a shared
     * wording would be a false statement about whichever surface it wasn't written for.
     */
    private static Map<String, Object> rangeField(String description, String fromDescription, String toDescription) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("from", strField(fromDescription));
        props.put("to", strField(toDescription));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("description", description);
        m.put("properties", props);
        m.put("additionalProperties", false);
        return m;
    }

    /** The shared {@code limit} argument of every {@code list_*} reader over the substrate. */
    private static Map<String, Object> limitField() {
        return intField("Optional. Max rows in this page (default " + LIST_DEFAULT_LIMIT + ", capped at "
                + LIST_MAX_LIMIT + ").");
    }

    /**
     * The shared {@code cursor} argument. An unreadable token restarts at page one rather than erroring,
     * the posture {@code QueryRepository} already takes.
     */
    private static Map<String, Object> cursorField() {
        return strField("Optional. The next_cursor from a previous page; an unreadable or stale token starts"
                + " again at the newest page.");
    }

    /**
     * The {@code list_spans}/{@code get_trace} payload opt-in. An array, not a boolean, because it is the
     * start of a field-set vocabulary: {@code fields: ["payload"]} says what arrives, where
     * {@code payloads: true} would only say something changed.
     */
    private static Map<String, Object> payloadFieldsField() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put(
                "description",
                "Optional. Pass [\"" + PAYLOAD_FIELD + "\"] to add each row's FULL input, output, attributes and"
                        + " provided_usage instead of just the previews. On list_spans, allowed only on a page"
                        + " scoped to one trace_id, or to a range at most " + SPAN_PAYLOAD_SCOPE_WINDOW.toHours()
                        + "h wide — an unscoped request is an error, never a quietly compact page. get_trace is"
                        + " always scoped to one trace_id, so the same rule is always satisfied there.");
        m.put("items", enumField("The only field set: " + PAYLOAD_FIELD + ".", List.of(PAYLOAD_FIELD)));
        return m;
    }

    /** Equality filters: a flat map of allow-listed field -> value (dataset-specific). */
    private static Map<String, Object> filtersField() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put(
                "description",
                "Optional equality filters as field->value (dataset-specific indexed fields; call "
                        + "describe_dataset for the allow-list); an unknown field returns an error naming "
                        + "the dataset.");
        m.put("additionalProperties", Map.of("type", "string"));
        return m;
    }

    private static @Nullable String strArg(@Nullable Map<String, Object> args, String key) {
        if (args == null) return null;
        Object v = args.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new McpTool.ToolException("argument " + key + " must be a string");
        }
        return s.isBlank() ? null : s;
    }

    private static String requireStr(Map<String, Object> args, String key) {
        String s = strArg(args, key);
        if (s == null) throw new McpTool.ToolException("missing required argument: " + key);
        return s;
    }

    /**
     * Parse an optional boolean argument. Absent is false: every flag on this surface is an opt-in, so
     * "not sent" and "sent false" are the same request.
     */
    private static boolean boolArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw new McpTool.ToolException("argument " + key + " must be a boolean");
    }

    /** Parse an optional integer argument (accepts JSON numbers; rejects other types). */
    private static @Nullable Integer intArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        throw new McpTool.ToolException("argument " + key + " must be an integer");
    }

    /**
     * Parse the optional nested {@code range} object into a {@link TimeRange}. Mirrors the HTTP wire
     * shape {@code {"from": ..., "to": ...}}; either bound may be absent. Returns null when no range
     * is supplied (matching {@code TimeRange == null}); range-completeness for timeseries is enforced
     * downstream by {@link QueryService}.
     */
    private static @Nullable TimeRange rangeArg(Map<String, Object> args) {
        Object v = args.get("range");
        if (v == null) return null;
        if (!(v instanceof Map<?, ?> raw)) {
            throw new McpTool.ToolException("argument range must be an object with optional from/to");
        }
        String from = strFromMap(raw, "from");
        String to = strFromMap(raw, "to");
        return new TimeRange(from, to);
    }

    /**
     * Parse the optional flat {@code filters} object into a string->string map. Returns an empty map
     * when absent (equivalent to "no filters": {@code QueryService} applies an empty filter set as a
     * no-op).
     */
    private static Map<String, String> filtersArg(Map<String, Object> args) {
        Object v = args.get("filters");
        if (v == null) return Map.of();
        if (!(v instanceof Map<?, ?> raw)) {
            throw new McpTool.ToolException("argument filters must be an object of field->value strings");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            Object key = e.getKey();
            Object val = e.getValue();
            if (!(key instanceof String k) || !(val instanceof String s)) {
                throw new McpTool.ToolException("filters entries must be string field->value pairs");
            }
            out.put(k, s);
        }
        return out;
    }

    private static @Nullable String strFromMap(Map<?, ?> raw, String key) {
        Object v = raw.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new McpTool.ToolException("range." + key + " must be a string");
        }
        return s.isBlank() ? null : s;
    }
}
