// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CasesPage;
import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.detector.GroundingEvidenceReads.SpanRef;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence;
import ai.tessary.classifier.finding.BehaviorDtos;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingsView;
import ai.tessary.classifier.finding.ClassifierDetail;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.frustration.FrustrationEvidence;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.EarlierMessage;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.classifier.secretleak.SecretLeakEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.model.CallSite;
import ai.tessary.model.FailureMode;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.pipeline.PipelineService;
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
import ai.tessary.storage.TraceDetectionRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionDtos;
import ai.tessary.traces.SessionReadService;
import ai.tessary.traces.TraceDtos;
import ai.tessary.traces.TracePageCodec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
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
 * Catalogue of MCP tools exposed by this server. Every tool reads: each takes the bound project from
 * {@link TenantContext#projectId()} and delegates to the same services the REST controllers use. Nothing here writes a
 * row, spends a token, or starts an agent run.
 */
@Component
public class McpToolRegistry {

    private static final int LIST_DEFAULT_LIMIT = 50;

    private static final int LIST_MAX_LIMIT = 100;

    /**
     * Ten times the substrate lists' bounds: an evidence ref carries only a role, a grain and two ids, and one
     * finding's population can run to six figures.
     */
    private static final int EVIDENCE_DEFAULT_LIMIT = 100;

    private static final int EVIDENCE_MAX_LIMIT = 1000;

    /** Per text field of an exported row; the page itself is bounded by {@link FindingService#EXPORT_TEXT_BUDGET}. */
    private static final int EXPORT_DEFAULT_TEXT_CHARS = 4000;

    private static final int EXPORT_MIN_TEXT_CHARS = 200;

    private static final int EXPORT_MAX_TEXT_CHARS = 20_000;

    /** The widest window a payload-bearing {@code list_spans} page may cover when it is not pinned to one trace. */
    private static final Duration SPAN_PAYLOAD_SCOPE_WINDOW = Duration.ofHours(24);

    private static final String PAYLOAD_FIELD = "payload";

    /**
     * The most spans {@code get_trace} renders. The cap keeps the OLDEST spans: a trace's head is what every later span
     * is only intelligible against.
     */
    private static final int TRACE_SPAN_CAP = 200;

    /** Declared once so the schema an agent reads and the check the handler runs cannot drift apart. */
    private static final List<String> CASE_STATES = List.of(CaseRow.State.OPEN, CaseRow.State.RESOLVED);

    private final PipelineService pipelineService;
    private final ProjectRepository projects;
    private final QueryService queryService;
    private final SpanRepository spans;
    private final SpanPayloadRepository payloads;
    private final TraceV2Repository traces;
    private final SessionReadService sessions;
    private final TraceDetectionRepository detections;
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
            FindingService behaviorDrift,
            CaseService cases,
            TraceDetectionRepository detections) {
        this.detections = detections;
        this.pipelineService = pipelineService;
        this.projects = projects;
        this.queryService = queryService;
        this.spans = spans;
        this.payloads = payloads;
        this.traces = traces;
        this.sessions = sessions;
        this.behaviorDrift = behaviorDrift;
        this.cases = cases;
        register();
    }

    /**
     * For unit tests with a hand-rolled toolset. Skips {@link #register()}, so the null service deps are never
     * dereferenced.
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
        this.detections = null;
        this.behaviorDrift = null;
        this.cases = null;
        for (McpTool t : toolset) tools.put(t.name(), t);
    }

    public List<McpTool> tools() {
        return List.copyOf(tools.values());
    }

    public @Nullable McpTool tool(String name) {
        return tools.get(name);
    }

    private void register() {
        // A token binds to exactly one project, so this is get_project, not a collection.
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

        registerCaseTools();
        registerQueryTools();
        registerSubstrateListTools();
        registerTraceReadTools();
        registerClassifierFindingTools();
    }

    /**
     * {@code list_cases} and {@code get_case}, through the same {@link CaseService} as the UI. Ungated: cases are what
     * the default-on classifiers produce. Lifecycle writes ({@code resolve}, {@code absorb}) are deliberately not
     * tools: each records a human judgement, and absorb moves the detector's baseline.
     */
    private void registerCaseTools() {
        add(new McpTool(
                "list_cases",
                "List this token's project's cases — what is wrong with it right now. Defaults to state=open,"
                        + " worst-first (severity, then most recently opened), which is the answer to 'what needs"
                        + " me'. Narrow with detector and call_site_id; ask for state=resolved, which pages newest-closure-first instead. Rows are the case itself —"
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
                                                "Optional. Which cases to page; defaults to 'open'."
                                                        + " 'resolved' is closed history, ordered by when it"
                                                        + " closed rather than by severity.",
                                                CASE_STATES)),
                                Map.entry(
                                        "detector",
                                        strField("Restrict to one detector (e.g. 'metric_drift', 'tool_error').")),
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
                "Fetch one case by id, scoped to this token's project: the case, its activity trail, the newest"
                        + " classifier finding it is about (latest_finding_id — pass it to get_finding), the"
                        + " classifier's summary numbers with no trace or span ids (page the finding's rows with"
                        + " get_finding_evidence and latest_finding_id), and the RCA report INLINE in rca when one"
                        + " has finished — its"
                        + " verdict, causes (one shape for every case type), the checks it ruled out, and the"
                        + " agent's full written"
                        + " investigation. rca is null while a report is still running (rca_report_id names it,"
                        + " so poll) and when none has been run; rca_available says whether one could be."
                        + " Accepts either the stored id or the human reference ('C-118'), so a case number"
                        + " quoted by a person resolves.",
                schema(Map.of("id", strField("Case id, or the human reference such as 'C-118'.")), List.of("id")),
                (ctx, args) -> getCase(ctx, requireStr(args, "id"))));
    }

    /**
     * The four queries proxy to {@link QueryService}, so every read is project-scoped and the dataset allow-list is
     * reused, never bypassed. {@code describe_dataset} reads {@link QueryDataset} itself so it cannot drift from the
     * enum.
     */
    private void registerQueryTools() {
        // Registered first: the other four take field names a caller will otherwise guess.
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
     * Each wraps the seam its REST controller reads (cursors via {@link TracePageCodec}), so a list here cannot
     * disagree with the UI. Rows are previews; {@code list_spans} returns full payloads only when scoped, see
     * {@link #listSpans}. A stale cursor restarts at page one.
     */
    private void registerSubstrateListTools() {
        add(new McpTool(
                "list_traces",
                "List this token's project's traces, newest first — one row per producer trace, carrying the"
                        + " ROLLUP columns (span_count, error_count, typed token buckets, costs, is_settled,"
                        + " unpriced_spans) and the stored input_preview/output_preview, NEVER the full payloads."
                        + " Filter by model, kind, call_site_id, status and a started_at range, or"
                        + " search names, ids and span inputs and outputs with q. A null token or cost column means"
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
                                        strField("Optional. Substring of the trace name, session, thread, user"
                                                + " or id; or every word in one span's input or output, as a word"
                                                + " prefix (whole for words under 3 letters).")),
                                Map.entry("limit", limitField()),
                                Map.entry("cursor", cursorField())),
                        List.of()),
                this::listTraces));

        add(new McpTool(
                "list_spans",
                "Find spans in this token's project — the step grain: one LLM call, tool call or sub-agent."
                        + " Newest-first, keyset-paged. Filter by trace_id, call_site_id, kind, name, status,"
                        + " model_id, session_id and a half-open started_at range; match text with"
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

        add(new McpTool(
                "get_conversation",
                "Fetch one conversation by id, scoped to this token's project: its turns (top-level traces)"
                        + " oldest-first as the same rows list_traces returns, capped at "
                        + SessionReadService.SESSION_TRACE_CAP + " with traces_truncated saying so. A"
                        + " conversation is a session: the key a frustration finding's session refs carry. Use"
                        + " get_session for the session's sub-agent traces and totals.",
                schema(
                        Map.of(
                                "id",
                                strField("Session id, e.g. a frustration evidence row's sessionId or a trace's"
                                        + " session_id.")),
                        List.of("id")),
                (ctx, args) -> getConversation(ctx, requireStr(args, "id"))));
    }

    /**
     * {@code get_span} and {@code get_trace} deliberately step outside the aggregation-first query firewall. Both are
     * scoped by primary-key prefix, so a cross-tenant id resolves to not-found. {@code get_span} needs both ids: a
     * producer span id is unique only within its trace.
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
     * Both delegate to {@link FindingService}, which is the project scope and the capability gate, so evidence cannot
     * bypass a withheld classifier.
     */
    private void registerClassifierFindingTools() {
        // Headline rows without the evidence blob: the list half of the list-then-fetch pair.
        add(new McpTool(
                "list_findings",
                "List classifier findings for this token's project — the aggregated causes behind"
                        + " metric-drift and tool-error-rate-drift detections. Returns headline"
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
                                        strField("Restrict to one detector (e.g. 'cost_drift', 'tool_error').")),
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
                        + " behind a detection (many individual firings rolled into one cause), with a complete"
                        + " summary of every number its classifier measured: the shift or rate, its statistic and"
                        + " threshold, and the reference it was judged against. Carries no sample and no trace or"
                        + " span id of any kind — for the population the detector measured, page"
                        + " get_finding_evidence. Distinct from a single firing (see the classifier_events dataset"
                        + " in query_search/query_facets) and from an RCA report, which reads inline on the case"
                        + " that owns it (see get_case). A classifier outside the built-in set reports its rate,"
                        + " reference and tuning under classifierDetail.",
                schema(Map.of("id", strField("Finding id, e.g. from a Classifiers findings page URL.")), List.of("id")),
                (ctx, args) -> getFinding(ctx, requireStr(args, "id"))));

        add(new McpTool(
                "get_finding_evidence",
                "Page the substrate a finding's claim rests on: one row per unit the detector measured, at the"
                        + " grain it measured (role 'member' is the flagged population ENUMERATED, not a sample;"
                        + " 'baseline' is the reference side's rows; 'changepoint' is the first rows from where"
                        + " a shift starts; 'exemplar' / 'witness' are the reading aids). Each row carries what"
                        + " was MEASURED on it, not just a pointer to it: role, rank, sessionId, traceId,"
                        + " spanId, name, kind, status, level, errorType, startedAt, latencyMs, totalTokens,"
                        + " totalCost, models, callSiteId, projectVersionId, userId, inputTokens, outputTokens,"
                        + " cacheReadTokens, cacheWriteTokens, reasoningTokens, traceIndex, sessionTraces,"
                        + " inputBytes, outputBytes, payloadAvailable, attributes. On a whole-run"
                        + " row (no spanId), the tokens and totalCost are the trace's rollup and models lists"
                        + " every model the run called; notRolledUp/partialCost/staleTotals flag a rollup"
                        + " that has not finished, is missing priced spans, or can still change. traceIndex is"
                        + " the turn's 1-based position in its session and sessionTraces the session's turn"
                        + " count, both capped at 10,000; attributes is a short fixed set of deploy, host,"
                        + " instance and model keys, masked. So compare,"
                        + " rank and pick rows from the page itself, and open only the ones you decided to open — the"
                        + " payloads are NOT here, and get_span (trace id plus span id) is where a body comes"
                        + " from. A groundedness finding's witness span rows also carry flaggedSentences: each"
                        + " sentence the model flagged in that answer, as {text, score}, with text null once the"
                        + " answer aged out; the question, answer and documents are in the span body."
                        + " Call it with count_only=true first: that returns the"
                        + " per-role sizes with no rows, so you can decide how much to page before you spend"
                        + " context on it. counts is what SURVIVES and can still be opened; recorded_counts is"
                        + " what the detector wrote at finding-open — counts below recorded means substrate aged"
                        + " out, never a lost write. ZERO under a role is a real answer: a detector with no"
                        + " enumerable reference side writes no baseline rows; read that as 'no reference rows',"
                        + " not as missing evidence. Pages in the detector's own"
                        + " order, stably; rank has gaps and is an order, not an index. There is NO sampling"
                        + " mode — if you want a stride or a random draw, take it yourself and say in your"
                        + " citation that you did and what you took.",
                schema(
                        Map.ofEntries(
                                Map.entry(
                                        "finding_id",
                                        strField("Finding id — a list_findings row's id, or a case's"
                                                + " latest_finding_id.")),
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
                                                + " rowsOmitted says so. This page answers under 'refs' (empty)"
                                                + " rather than 'rows'.")),
                                Map.entry(
                                        "limit",
                                        intField("Optional. Max rows in this page (default " + EVIDENCE_DEFAULT_LIMIT
                                                + ", capped at " + EVIDENCE_MAX_LIMIT + ").")),
                                Map.entry(
                                        "cursor",
                                        strField("Optional. The next_cursor from a previous page; an unreadable"
                                                + " or stale token starts again at the FIRST page of the set,"
                                                + " which for an ordered population is page one, not the newest"
                                                + " rows."))),
                        List.of("finding_id")),
                this::getFindingEvidence));

        add(new McpTool(
                "export_finding_evidence",
                "Every evidence row of a finding, for a script rather than for reading in context: the fields"
                        + " get_finding_evidence measures, plus the row's input and output text cut at text_chars"
                        + " and marked '…[cut]', every payload attribute, and on a frustration finding's witness"
                        + " rows priorMessages, the messages before the flagged turn. Credentials are masked. A page"
                        + " stops at " + FindingService.EXPORT_MAX_ROWS + " rows or about 6 MB of text; page with"
                        + " nextCursor, the same cursor get_finding_evidence uses.",
                schema(
                        Map.ofEntries(
                                Map.entry(
                                        "finding_id",
                                        strField("Finding id — a list_findings row's id, or a case's"
                                                + " latest_finding_id.")),
                                Map.entry(
                                        "role",
                                        enumField(
                                                "Restrict to one evidence role. Omit for the whole set, which"
                                                        + " pages role by role.",
                                                FindingEvidenceRow.Role.ALL)),
                                Map.entry(
                                        "text_chars",
                                        intField("Optional. Characters kept of each text value (default "
                                                + EXPORT_DEFAULT_TEXT_CHARS + ", from " + EXPORT_MIN_TEXT_CHARS
                                                + " to " + EXPORT_MAX_TEXT_CHARS + ").")),
                                Map.entry(
                                        "cursor",
                                        strField("Optional. The nextCursor from a previous page; an unreadable"
                                                + " token starts again at the first page."))),
                        List.of("finding_id")),
                this::exportFindingEvidence));
    }

    private BehaviorFindingsView listFindings(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        // Anything but an explicit "all" means confirmed only, so a typo narrows rather than widening a lead list into
        // an alert list.
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
                    view.lane());
        } catch (TessaryException e) {
            throw toolError(e);
        }
    }

    /**
     * Two firewalls for every agent caller. The triage ruling is removed because RCA must reach its own conclusion and
     * "nothing happened here" is a supported one. Every sample id is removed because an agent handed a few ids reads
     * only those and calls the claim audited; evidence is paged by {@code get_finding_evidence}.
     */
    private static BehaviorFindingDetailView agentView(BehaviorFindingDetailView detail) {
        ToolErrorEvidence.RateDetail toolError = detail.toolError();
        MalformedOutputEvidence.MalformedDetail malformedOutput = detail.malformedOutput();
        SecretLeakEvidence.SecretLeakDetail secretLeak = detail.secretLeak();
        FrustrationEvidence.FrustrationDetail frustration = detail.frustration();
        GroundednessEvidence.GroundednessDetail groundedness = detail.groundedness();
        ClassifierDetail classifierDetail = detail.classifierDetail();
        return new BehaviorFindingDetailView(
                detail.finding().withoutTriage(),
                detail.metric(),
                toolError == null ? null : withoutIds(toolError),
                malformedOutput == null ? null : withoutIds(malformedOutput),
                secretLeak == null ? null : withoutIds(secretLeak),
                detail.armedWindow(),
                frustration == null ? null : frustration.withoutIds(),
                groundedness == null ? null : groundedness.withoutIds(),
                classifierDetail == null ? null : withoutIds(classifierDetail));
    }

    private static ToolErrorEvidence.RateDetail withoutIds(ToolErrorEvidence.RateDetail rate) {
        return new ToolErrorEvidence.RateDetail(
                rate.bucketKey(),
                rate.refRate(),
                rate.curRate(),
                rate.deltaPp(),
                rate.nRef(),
                rate.nCur(),
                rate.failuresCur(),
                rate.patterns(),
                rate.patternsTruncated(),
                List.of(),
                rate.onsetAt(),
                rate.windowOpenedAt(),
                rate.windowClosedAt(),
                rate.direction(),
                rate.statistic(),
                rate.threshold(),
                rate.effectSize(),
                rate.criticality());
    }

    private static ClassifierDetail withoutIds(ClassifierDetail detail) {
        return new ClassifierDetail(
                withoutIds(detail.rate()),
                detail.baselineFlagged(),
                detail.flagLabel(),
                detail.flagThreshold(),
                detail.arlTarget(),
                detail.minDecisionInterval(),
                detail.learningUntil(),
                detail.words());
    }

    private static MalformedOutputEvidence.MalformedDetail withoutIds(MalformedOutputEvidence.MalformedDetail detail) {
        return new MalformedOutputEvidence.MalformedDetail(
                withoutIds(detail.rate()), detail.fields(), detail.notJson(), detail.other());
    }

    private static SecretLeakEvidence.SecretLeakDetail withoutIds(SecretLeakEvidence.SecretLeakDetail detail) {
        return new SecretLeakEvidence.SecretLeakDetail(
                detail.rule(),
                detail.confidence(),
                detail.leakCount(),
                detail.traceCount(),
                detail.firstAt(),
                detail.lastAt(),
                detail.keys(),
                detail.leaks().stream()
                        .map(l -> new SecretLeakEvidence.SecretLeakLeakView(l.at(), l.masked(), l.stored(), null, null))
                        .toList(),
                detail.basis(),
                detail.threshold(),
                detail.windowSeconds(),
                detail.windowStart(),
                detail.windowEnd());
    }

    private BehaviorFindingDetailView getFinding(TenantContext ctx, String id) {
        String projectId = requireProject(ctx).id();
        try {
            return agentView(behaviorDrift.finding(projectId, id));
        } catch (TessaryException e) {
            throw toolError(e);
        }
    }

    /**
     * Measurements rather than bare refs, so an agent can choose which rows to open instead of picking blind. An
     * unrecognised {@code role} is an error, not an empty page that would read as "no evidence".
     */
    private Object getFindingEvidence(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String findingId = requireStr(args, "finding_id");
        String role = strArg(args, "role");
        if (role != null && !FindingEvidenceRow.Role.ALL.contains(role)) {
            throw new McpTool.ToolException("unknown evidence role: " + role + ". get_finding_evidence filters one of "
                    + FindingEvidenceRow.Role.ALL + ".");
        }
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), EVIDENCE_DEFAULT_LIMIT, EVIDENCE_MAX_LIMIT);
        String cursor = strArg(args, "cursor");
        try {
            if (boolArg(args, "count_only")) {
                return behaviorDrift.findingEvidence(projectId, findingId);
            }
            BehaviorDtos.FindingEvidenceSpanPage page =
                    behaviorDrift.findingEvidenceSpans(projectId, findingId, role, pageSize, cursor);
            List<SpanRef> witnessSpans = page.rows().stream()
                    .filter(r -> FindingEvidenceRow.Role.WITNESS.equals(r.role())
                            && r.traceId() != null
                            && r.spanId() != null)
                    .map(r -> new SpanRef(r.traceId(), r.spanId()))
                    .toList();
            Map<SpanRef, List<GroundednessEvidence.FlaggedSentenceText>> sentences = witnessSpans.isEmpty()
                    ? Map.of()
                    : behaviorDrift.flaggedSentences(projectId, findingId, witnessSpans);
            return new EvidenceSpanPage(
                    page.rows().stream()
                            .map(r -> EvidenceSpan.of(r, witnessSentences(r, sentences)))
                            .toList(),
                    page.nextCursor(),
                    page.counts(),
                    page.recordedCounts());
        } catch (TessaryException e) {
            throw toolError(e);
        }
    }

    private ExportPage exportFindingEvidence(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String findingId = requireStr(args, "finding_id");
        String role = strArg(args, "role");
        if (role != null && !FindingEvidenceRow.Role.ALL.contains(role)) {
            throw new McpTool.ToolException("unknown evidence role: " + role
                    + ". export_finding_evidence filters one of " + FindingEvidenceRow.Role.ALL + ".");
        }
        int textChars = Math.max(
                EXPORT_MIN_TEXT_CHARS,
                TracePageCodec.clampLimit(
                        intArg(args, "text_chars"), EXPORT_DEFAULT_TEXT_CHARS, EXPORT_MAX_TEXT_CHARS));
        try {
            BehaviorDtos.EvidenceExportPage page =
                    behaviorDrift.exportEvidence(projectId, findingId, role, textChars, strArg(args, "cursor"));
            return new ExportPage(
                    page.rows().stream().map(ExportRow::of).toList(),
                    page.nextCursor(),
                    page.counts(),
                    page.recordedCounts(),
                    page.textChars());
        } catch (TessaryException e) {
            throw toolError(e);
        }
    }

    // null = not a witness span → the key is omitted
    private static @Nullable List<GroundednessEvidence.FlaggedSentenceText> witnessSentences(
            BehaviorDtos.EvidenceSpanView row, Map<SpanRef, List<GroundednessEvidence.FlaggedSentenceText>> sentences) {
        if (!FindingEvidenceRow.Role.WITNESS.equals(row.role()) || row.traceId() == null || row.spanId() == null) {
            return null;
        }
        return sentences.get(new SpanRef(row.traceId(), row.spanId()));
    }

    /**
     * {@link BehaviorDtos.EvidenceSpanView} without the payload previews: payload text comes from {@code get_span},
     * asked for one span at a time.
     */
    record EvidenceSpan(
            String role,
            @Nullable Integer rank,
            @Nullable String sessionId,
            @Nullable String traceId,
            @Nullable String spanId,
            @Nullable String name,
            @Nullable String kind,
            @Nullable String status,
            @Nullable String level,
            @Nullable String errorType,
            @Nullable String startedAt,
            @Nullable Long latencyMs,
            @Nullable Long totalTokens,
            @Nullable Double totalCost,
            List<String> models,
            /** True on a whole-run row whose trace has not rolled up yet. */
            boolean notRolledUp,
            boolean partialCost,
            boolean staleTotals,
            @Nullable String callSiteId,
            @Nullable String projectVersionId,
            @Nullable String userId,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable Long reasoningTokens,
            @Nullable Integer traceIndex,
            @Nullable Integer sessionTraces,
            @Nullable Long inputBytes,
            @Nullable Long outputBytes,
            boolean payloadAvailable,
            /**
             * The short masked list, {@code {}} when empty, so every page has one key set. Null only inside
             * {@link ExportRow}, which carries every attribute under the same key.
             */
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            Map<String, String> attributes,
            /** The leaked key, already masked. Set only on a secret-leak finding's evidence. */
            @Nullable String secretKey,
            @Nullable String storedAs,
            /** Set only on a malformed-output finding's evidence. */
            @Nullable String violation,
            /** Set only on a groundedness finding's witness span rows. */
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            List<GroundednessEvidence.FlaggedSentenceText> flaggedSentences) {

        static EvidenceSpan of(
                BehaviorDtos.EvidenceSpanView v,
                @Nullable List<GroundednessEvidence.FlaggedSentenceText> flaggedSentences) {
            return of(v, v.attributes(), flaggedSentences);
        }

        static EvidenceSpan of(
                BehaviorDtos.EvidenceSpanView v,
                @Nullable Map<String, String> attributes,
                @Nullable List<GroundednessEvidence.FlaggedSentenceText> flaggedSentences) {
            return new EvidenceSpan(
                    v.role(),
                    v.rank(),
                    v.sessionId(),
                    v.traceId(),
                    v.spanId(),
                    v.name(),
                    v.kind(),
                    v.status(),
                    v.level(),
                    v.errorType(),
                    v.startedAt(),
                    v.latencyMs(),
                    v.totalTokens(),
                    v.totalCost(),
                    v.models(),
                    v.notRolledUp(),
                    v.partialCost(),
                    v.staleTotals(),
                    v.callSiteId(),
                    v.projectVersionId(),
                    v.userId(),
                    v.inputTokens(),
                    v.outputTokens(),
                    v.cacheReadTokens(),
                    v.cacheWriteTokens(),
                    v.reasoningTokens(),
                    v.traceIndex(),
                    v.sessionTraces(),
                    v.inputBytes(),
                    v.outputBytes(),
                    v.payloadAvailable(),
                    attributes,
                    v.secretKey(),
                    v.storedAs(),
                    v.violation(),
                    flaggedSentences);
        }
    }

    record EvidenceSpanPage(
            List<EvidenceSpan> rows,
            @Nullable String nextCursor,
            Map<String, Long> counts,
            Map<String, Long> recordedCounts) {}

    /** Flat on the wire, so a script over the saved page reads one level. */
    record ExportRow(
            @JsonUnwrapped EvidenceSpan measured,
            @Nullable String input,
            @Nullable String output,
            @Nullable Map<String, Object> attributes,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            List<EarlierMessage> priorMessages) {

        static ExportRow of(BehaviorDtos.EvidenceExportRow row) {
            return new ExportRow(
                    // The unwrapped span leaves its short list out; the row's own `attributes` holds every key.
                    EvidenceSpan.of(row.measured(), null, null),
                    row.input(),
                    row.output(),
                    row.attributes(),
                    row.priorMessages());
        }
    }

    record ExportPage(
            List<ExportRow> rows,
            @Nullable String nextCursor,
            Map<String, Long> counts,
            Map<String, Long> recordedCounts,
            int textChars) {}

    private void add(McpTool t) {
        tools.put(t.name(), t);
    }

    /**
     * {@code watching} lives here because it is the only signal separating "nothing is wrong" from "nothing is
     * arriving"; without it an empty {@code list_cases} reads as an all-clear.
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

    /**
     * Defaults to {@code state=open}. An unrecognised {@code state} is an error, not an empty page that would read as
     * "nothing wrong".
     */
    private CasesPage listCases(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        String state = Objects.requireNonNullElse(strArg(args, "state"), CaseRow.State.OPEN);
        if (!CASE_STATES.contains(state)) {
            throw new McpTool.ToolException(
                    "unknown state: " + state + ". list_cases pages one of " + CASE_STATES + ".");
        }
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        return cases.page(
                projectId,
                state,
                strArg(args, "detector"),
                strArg(args, "call_site_id"),
                pageSize,
                strArg(args, "cursor"));
    }

    private CaseDetailView getCase(TenantContext ctx, String id) {
        String projectId = requireProject(ctx).id();
        try {
            CaseDetailView detail = cases.detail(projectId, id);
            // Both of get_finding's firewalls (see agentView). `rca` is deliberately not stripped: an earlier RCA
            // report is this lane's own prior work, not the triage gate it checks.
            ToolErrorEvidence.RateDetail toolError = detail.toolError();
            MalformedOutputEvidence.MalformedDetail malformedOutput = detail.malformedOutput();
            SecretLeakEvidence.SecretLeakDetail secretLeak = detail.secretLeak();
            FrustrationEvidence.FrustrationDetail frustration = detail.frustration();
            GroundednessEvidence.GroundednessDetail groundedness = detail.groundedness();
            ClassifierDetail classifierDetail = detail.classifierDetail();
            return new CaseDetailView(
                    detail.caseView(),
                    detail.events(),
                    detail.latestFindingId(),
                    detail.findings(),
                    detail.worstFindingId(),
                    null,
                    List.of(),
                    detail.rcaReportId(),
                    detail.rca(),
                    detail.metric(),
                    toolError == null ? null : withoutIds(toolError),
                    malformedOutput == null ? null : withoutIds(malformedOutput),
                    secretLeak == null ? null : withoutIds(secretLeak),
                    frustration == null ? null : frustration.withoutIds(),
                    groundedness == null ? null : groundedness.withoutIds(),
                    classifierDetail == null ? null : withoutIds(classifierDetail),
                    detail.rcaAvailable(),
                    detail.absorbAvailable(),
                    detail.detectorAvailable());
        } catch (TessaryException e) {
            throw toolError(e);
        }
    }

    /** No {@link #requireProject}: this reads no rows, so a token whose project was deleted can still introspect. */
    private Map<String, Object> describeDataset(Map<String, Object> args) {
        String wire = strArg(args, "dataset");
        List<QueryDataset> selected;
        try {
            selected = wire == null ? List.of(QueryDataset.values()) : List.of(QueryDataset.fromWire(wire));
        } catch (TessaryException e) {
            throw toolError(e);
        }
        List<Map<String, Object>> described = new ArrayList<>(selected.size());
        for (QueryDataset d : selected) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataset", d.wireName());
            m.put("facet_fields", List.copyOf(d.facetFields()));
            m.put("filter_fields", List.copyOf(d.filterFields()));
            m.put("searchable", d.supportsSearch());
            // Spans search lives on list_spans; `searchable: true` alone sent agents to query_search, which returns a
            // thinner reader.
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
            throw toolError(e);
        }
    }

    private TimeseriesView queryTimeseries(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        TimeseriesRequest req = new TimeseriesRequest(
                requireStr(args, "dataset"), requireStr(args, "interval"), rangeArg(args), filtersArg(args));
        try {
            return TimeseriesView.of(queryService.timeseries(projectId, req));
        } catch (TessaryException e) {
            throw toolError(e);
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
            throw toolError(e);
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
            throw toolError(e);
        }
    }

    /** A {@link McpTool.ToolException} the LLM can correct, not a {@code -32603} internal error. */
    private static McpTool.ToolException toolError(TessaryException e) {
        return new McpTool.ToolException(Objects.requireNonNull(e.getMessage()), e);
    }

    /**
     * {@code sort} is null on purpose: newest-first keeps a cursor minted here readable by the REST decoder, and cost
     * rankings belong to the query tools.
     */
    private TraceDtos.TracesPage listTraces(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        TimeRange range = rangeArg(args);
        var query = new TraceV2Repository.TraceQuery(
                strArg(args, "model"),
                strArg(args, "kind"),
                strArg(args, "call_site_id"),
                null,
                range == null ? null : range.from(),
                range == null ? null : range.to(),
                strArg(args, "status"),
                strArg(args, "q"),
                null);
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        TracePageCodec.Key before = TracePageCodec.decode(strArg(args, "cursor"));
        List<TraceV2Repository.Summary> rows;
        try {
            rows = traces.list(
                    projectId, query, null, pageSize + 1, before.sortValue(), before.startedAt(), before.id());
        } catch (TessaryException e) {
            throw toolError(e);
        }
        TracePageCodec.Page page = TracePageCodec.trim(rows, pageSize, null);
        List<String> ids =
                page.rows().stream().map(TraceV2Repository.Summary::id).toList();
        return new TraceDtos.TracesPage(
                TraceDtos.items(page.rows(), detections.forTraces(projectId, ids)), page.nextCursor());
    }

    /**
     * Retrieval is {@link QueryService#search}; this adds filters, the page bound and rendering. Rows are re-read from
     * {@code span} and re-ordered into the search's ranking, and a span retention removed between the two reads is
     * dropped. Payloads are one query for the page or none.
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
            throw toolError(e);
        }

        List<SpanKey> keys = new ArrayList<>(page.rows().size());
        for (QueryRepository.SearchRow row : page.rows()) {
            String rowTrace = row.fields().get("trace_id");
            String rowSpan = row.fields().get("span_id");
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
     * Any other {@code fields} value is a hard error: a caller that silently got a page without what it asked for would
     * read it as complete.
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
     * Full text only for a page pinned to one trace or a window of at most {@link #SPAN_PAYLOAD_SCOPE_WINDOW}. Fails
     * loudly, since an agent given previews cannot tell it is reading a truncation. A one-sided range does not qualify,
     * so the rule never depends on the server clock.
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

    private static void putFilter(Map<String, String> filters, String field, @Nullable String value) {
        if (value != null) filters.put(field, value);
    }

    /**
     * {@code payload_available} says a payload row exists, not "has text": payloads age out ahead of their spans.
     * {@code started_at} is the producer's clock that the range and keyset run on; {@code created_at} is receipt time.
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
        // Unpriced means we hold no rate, not that the call was free.
        m.put("cost_source", s.costSource());
        m.put("input_preview", s.inputPreview());
        m.put("output_preview", s.outputPreview());
        m.put("payload_available", payloadAvailable);
        if (includePayload) {
            // Keys are always present when requested: an absent key would read as "not asked for".
            m.put("input", payload == null ? null : payload.input());
            m.put("output", payload == null ? null : payload.output());
            m.put("attributes", payload == null ? null : payload.attributes());
            m.put("provided_usage", payload == null ? null : payload.providedUsage());
        }
        return m;
    }

    private SessionDtos.SessionsPage listSessions(TenantContext ctx, Map<String, Object> args) {
        String projectId = requireProject(ctx).id();
        int pageSize = TracePageCodec.clampLimit(intArg(args, "limit"), LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        return sessions.page(projectId, pageSize, strArg(args, "cursor"), false, TraceV2Repository.TraceQuery.NONE);
    }

    /** The project scoping IS the lookup: another tenant's session id resolves to not-found, not forbidden. */
    private SessionDtos.SessionDetail getSession(TenantContext ctx, String id) {
        String projectId = requireProject(ctx).id();
        return sessions.detail(projectId, id, TraceV2Repository.TraceQuery.NONE)
                .orElseThrow(() -> new McpTool.ToolException("session not found: " + id));
    }

    private SessionDtos.ConversationDetail getConversation(TenantContext ctx, String id) {
        String projectId = requireProject(ctx).id();
        return sessions.conversation(projectId, id)
                .orElseThrow(() -> new McpTool.ToolException("conversation not found: " + id));
    }

    /**
     * {@code id} is a synonym for {@code span_id}. A call with no {@code trace_id} fails with the rule rather than
     * not-found, since its id may name a real span.
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
     * Skeleton by default; {@code fields=["payload"]} opts in like {@code list_spans}, because full text for hundreds
     * of spans was most of a run's context. Payloads are one query keyed to the rendered spans, and reading
     * {@code TRACE_SPAN_CAP + 1} rows answers {@code spans_truncated}.
     */
    private Map<String, Object> getTrace(TenantContext ctx, String traceId, boolean withPayloads) {
        String projectId = requireProject(ctx).id();
        var trace = traces.findById(projectId, traceId)
                .orElseThrow(() -> new McpTool.ToolException("trace not found: " + traceId));
        List<SpanRow> all = spans.listByTrace(projectId, traceId, TRACE_SPAN_CAP + 1);
        boolean truncated = all.size() > TRACE_SPAN_CAP;
        // Oldest-first, so the cap keeps the conversation's head.
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
        // A null counter means "not rolled up yet", NOT zero; is_settled says which.
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
        // Spans on unpriced models are counted, not priced at zero, so a low total is never mistaken for a cheap turn.
        out.put("unpriced_spans", trace.unpricedSpans());
        out.put("is_settled", trace.isSettled());
        out.put("call_site_id", trace.callSiteId());
        out.put("spans_truncated", truncated);
        out.put("spans", spanViews);
        return out;
    }

    /**
     * A {@link LinkedHashMap} so nullable columns reach the wire as explicit JSON nulls instead of tripping
     * {@code Map.of}'s NPE. A null cost is not zero (see {@code cost_source}); {@code payload} is absent once a span
     * outlives payload retention.
     */
    private static Map<String, Object> spanView(SpanRow s, @Nullable SpanPayloadRow payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace_id", s.traceId());
        m.put("span_id", s.id());
        m.put("parent_span_id", s.parentSpanId());
        // A null path means ancestry is unresolved, not "this is the root".
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
        // A receipt, never read for arithmetic.
        m.put("provided_usage", payload == null ? null : payload.providedUsage());
        return m;
    }

    /**
     * {@link #spanView} plus previews, with payload columns only when the caller opted in; {@code get_span}'s
     * full-payload contract is untouched.
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

    /**
     * Derived from {@link QueryDataset}: a hand-written list once drifted, and {@code additionalProperties: false} made
     * a real dataset unreachable.
     */
    private static Map<String, Object> datasetField() {
        return enumField("Dataset to query: " + datasetList(false) + ".", datasetNames(false));
    }

    /**
     * {@code spans} is excluded by decision: {@code list_spans} already does that search with richer filters.
     * {@link QueryService} still accepts it.
     */
    private static Map<String, Object> searchableDatasetField() {
        return enumField("Dataset to search: " + datasetList(true) + ".", datasetNames(true));
    }

    /**
     * The one place that knows spans search lives on {@code list_spans}, so introspection and the allowed enum cannot
     * disagree.
     */
    private static @Nullable String searchToolFor(QueryDataset d) {
        if (!d.supportsSearch()) return null;
        return d == QueryDataset.SPANS ? "list_spans" : "query_search";
    }

    private static List<String> datasetNames(boolean searchableOnly) {
        List<String> out = new ArrayList<>();
        for (QueryDataset d : QueryDataset.values()) {
            if (!searchableOnly || (d.supportsSearch() && d != QueryDataset.SPANS)) out.add(d.wireName());
        }
        return List.copyOf(out);
    }

    private static String datasetList(boolean searchableOnly) {
        return String.join(" | ", datasetNames(searchableOnly));
    }

    /**
     * A half-open {@code [from, to)} window on the dataset's own event clock, not necessarily {@code created_at};
     * {@code describe_dataset}'s {@code time_column} names it.
     */
    private static Map<String, Object> rangeField() {
        return rangeField(
                "Optional half-open time window [from, to) on the dataset's own event clock — started_at for"
                        + " spans and tool_calls, subject_started_at for classifier_events, or bucket_start"
                        + " (ingest time) for metric_rollups (ISO-8601). See describe_dataset's time_column.",
                "Optional inclusive lower bound (ISO-8601).",
                "Optional exclusive upper bound (ISO-8601).");
    }

    /**
     * Caller-supplied descriptions, because the query datasets are half-open while the traces list is inclusive at both
     * ends.
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

    private static Map<String, Object> limitField() {
        return intField("Optional. Max rows in this page (default " + LIST_DEFAULT_LIMIT + ", capped at "
                + LIST_MAX_LIMIT + ").");
    }

    /** An unreadable token restarts at page one, as {@code QueryRepository} does. */
    private static Map<String, Object> cursorField() {
        return strField("Optional. The next_cursor from a previous page; an unreadable or stale token starts"
                + " again at the newest page.");
    }

    /** An array, not a boolean, so it can grow into a field-set vocabulary. */
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

    /** Absent is false: every flag on this surface is an opt-in. */
    private static boolean boolArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw new McpTool.ToolException("argument " + key + " must be a boolean");
    }

    private static @Nullable Integer intArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        throw new McpTool.ToolException("argument " + key + " must be an integer");
    }

    /** Either bound may be absent; timeseries completeness is enforced by {@link QueryService}. */
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
