# evals-mcp — remote MCP hosted in the tessary backend

The Java backend speaks the **Model Context Protocol** directly at `POST /mcp`. There is no
separate process to install — Claude Code (or any MCP client) connects to the same Spring Boot
service that already hosts the REST API and the curation UI.

Platform reference (auth model, key store, code map):
[`devdocs/reference/auth-and-mcp.md`](../../devdocs/reference/auth-and-mcp.md).

```
┌──────────────────────────┐                ┌──────────────────────────────────┐
│ Claude Code (in repo)    │                │ tessary Spring Boot       │
│                          │                │                                  │
│  read cases / findings / │                │  /api/* (REST — UI, run, etc.)   │
│  traces / query ─────────┼──── HTTPS ────►│  /mcp  ────►  McpController      │
│                          │  Bearer token  │              McpDispatcher       │
│                          │                │              McpToolRegistry     │
└──────────────────────────┘                │                                  │
                                            │  Case / Query / Substrate /      │
                                            │  Curation / Pipeline services    │
                                            └──────────────────────────────────┘
```

Tools delegate to the same services that REST controllers use. Same source of truth, two
transports. **The MCP surface is read-only**: no tool writes a row, spends a token, or starts an
agent run — `initialize` states that to every client on connect.

## Why hosted (not a local stdio process)

- **No second install.** Paste a URL + token into `~/.claude/mcp.json`.
- **Per-project tokens, DB-issued.** Each token authorises exactly one project. Minted in
  Settings → MCP tokens (or the plugin device-link handshake), shown once, bcrypt-hashed at rest
  in the shared `api_key` table (`ApiKeyService`).
- **One auth surface.** Cookie session for the browser, bearer for MCP — both produce the same
  `TenantContext` via `AuthFilter` / `BearerTokenAuthenticator`.
- **No version skew.** The MCP surface ships with the backend.

## Configuration

### Backend

No env var for the MCP token. Tokens are admin-scoped `api_key` rows (`tsy_a_…`) minted per
project via the UI (`POST /api/orgs/{slug}/projects/{slug}/mcp-tokens`) or device-link. The MCP
endpoint is always live; an unauthenticated request gets 401.

WorkOS wiring: see the project-root `.env.example`.

### Claude Code (or any MCP client)

In the UI: pick a project → **Settings → MCP tokens** → **Create token** → copy the
`tsy_a_…` string it shows once. Then add to `~/.claude/mcp.json`:

```json
{
  "mcpServers": {
    "evals-mcp": {
      "type": "http",
      "url": "http://localhost:8000/mcp",
      "headers": {
        "Authorization": "Bearer tsy_a_xxxxxxxxxxxxxxxxxxxxxx"
      }
    }
  }
}
```

For prod: replace `http://localhost:8000` with your platform URL. Restart Claude Code. The
token binds the session to one project; every tool reads `ctx.projectId()`.

## Tools shipped today

**19 tools, all open.** All delegate to the same services REST uses. **Authoritative names +
schemas:** `backend/.../mcp/McpToolRegistry.java` — the argument column below names each tool's filters so
you can see the shape of the surface; it is not a schema copy, and it is not the contract.

Conventions the tools share, stated here once rather than per row:

- **Read-only.** Nothing writes, spends, or starts an agent run.
- **Project-scoped by the token.** No tool takes a project argument.
- **Paging** on the four substrate/case readers (`list_cases`, `list_traces`, `list_spans`,
  `list_sessions`): `limit` (default 50, capped 100) + `cursor` in, `next_cursor` out. An unreadable or
  stale cursor restarts at the newest page rather than erroring. `query_search` keeps its own older
  limits (default 100, capped 1000), and `get_finding_evidence` pages at the same wider bound — its rows
  are ids rather than prose, and the set it pages is a whole measured population.
- **Lists find, gets read.** List rows carry typed columns and the stored `input_preview`/`output_preview`
  plus `payload_available`, never the full payload. Raw text comes from `get_span`, or from `get_trace` /
  `list_spans` with `fields: ["payload"]` on a page already scoped to one trace or ≤ 24h.
- Timestamps ISO-8601; the snake_case wire shape is identical to REST.

| Tool | Args (`limit`/`cursor` omitted — see above) | Returns | Gate |
|---|---|---|---|
| `get_project` | — | project identity, counts, packs, judge runtime, **`watching`** (classifiers enabled, call sites swept, `traces_last_day`) | open |
| `list_call_sites` | — | call sites + observed stats | open |
| `list_failure_modes` | `call_site_id`, `chain_id`, `scope`, `severity`, `layer`, `pack_id`, `compliance_tag` | taxonomy rows | open |
| `list_cases` | `state` (open\|muted\|resolved), `detector`, `call_site_id` | paged case rows; open is worst-first, resolved is newest-closure-first | open |
| `get_case` | `id` (stored id or `C-118`) | case + activity trail + `finding_id` + exemplars + the **RCA report inline in `rca`** when one has finished | open |
| `list_findings` | `status`, `call_site_id`, `detector`, `include` | headline finding rows (no evidence blob) + withheld count | open |
| `get_finding` | `id` | finding + parsed evidence | open |
| `get_finding_evidence` | `finding_id`, `role` (exemplar\|member\|baseline\|witness\|changepoint), `count_only` | paged refs `{role, grain, session_id?, trace_id?, span_id?, rank?}` into the population the detector measured, + live and as-written per-role counts | open |
| `list_traces` | `model`, `kind`, `call_site_id`, `status`, `range`, `q` | paged trace rollup rows + previews | open |
| `get_trace` | `trace_id`, `fields` | rollup + spans, oldest-first, capped 200 + `spans_truncated`; skeleton rows (typed columns + previews + `payload_available`) unless `fields: ["payload"]` | open |
| `list_spans` | `trace_id`, `call_site_id`, `kind`, `name`, `status`, `model_id`, `session_id`, `range`, `q`, `mode` (keyword\|semantic), `fields` | paged compact span rows; full payloads only when scoped | open |
| `get_span` | `trace_id` **and** `span_id` | one span, full payload | open |
| `list_sessions` | — | paged sessions, most recently active first (identity only — no rollup) | open |
| `get_session` | `id` | session + totals summed from its traces + those traces, capped 1000 + `traces_truncated` | open |
| `describe_dataset` | `dataset` (omit for all) | per dataset: facetable fields, filterable fields, `searchable`, time column, measure | open |
| `query_count` | `dataset`, `range`, `filters` | count, or the summed measure on `metric_rollups` | open |
| `query_timeseries` | `dataset`, `interval`, `range`, `filters` | buckets | open |
| `query_facets` | `dataset`, `field`, `range`, `filters`, `top_n` | top-N breakdown | open |
| `query_search` | `dataset` (**`tool_calls` \| `classifier_events`**), `q`, `mode`, `range`, `filters` | paged rows | open |

**Gate** is the capability an org must hold to be *offered* the tool. **Nothing on this surface is gated
today**: all 19 rows are open, so a launch partner's `tools/list` is the whole catalogue. It was 22 with two
gated on `GRADERS` until grading was deleted — the two grader reads and `list_quality_dimensions` went
with it, and `Capability.GRADERS` itself no longer exists. The per-tool mechanism stays (`McpTool.capability`,
null on every tool today), because a paid classifier's own reads are the obvious next thing to want it.
**`Capability.RCA` gates nothing here any more** — a case
carries its own report, so an org without RCA simply has no report rows to inline. Where the line falls, and
why, is argued in `McpTool`'s javadoc. Never maintain a second copy of this table anywhere: `tools/list` is
the truth, and a client should render that rather than prose.

### Removed

Six tools left in one breaking release, alongside `describe_dataset` and the plural readers. Each now reads
as `unknown tool`; there is no alias and no shim. Three more were removed along with grading, listed after them.

- **`propose_grader_edit`** — the last write. It filled a human curator's queue from a model's reasoning,
  and the grader body is the ruler the whole product measures against. Authoring stays in the UI, where the
  edit and the accept are one person's decision.
- **`run_triage`** — started a platform-paid agent session. Spend on the user's behalf is a UI action.
- **`get_triage`** / **`latest_triage`** — pollers for the run above; with no way to start one they answered
  a question nobody could ask.
- **`list_rca_reports`** / **`get_rca_report`** — the report is the answer to "why is this case open", so it
  reaches MCP inlined on the case that owns it (`get_case.rca`) rather than through a second gated tool.

Grading was removed from the platform, and three more tools went with it — there is no curated set to
read and no rubric behind a score:

- **`list_graders`** / **`get_grader`** — the two `GRADERS`-gated reads. The capability constant is gone too,
  so this is not a tool withheld from an org; it is a tool that does not exist for anyone.
- **`list_quality_dimensions`** — the scoring axes, each of which named the `grader_id` that scored it.

`McpCapabilityGateTest.REMOVED_TOOLS` pins all nine by name, so re-adding one under its old name fails a test.

Earlier removals, kept here because clients still ask about them:

- **`reload_pipeline`** (was: "re-read evals.yaml from disk"). A no-op — pipeline content lives
  in the DB — and it answered `valid: true` with pre-push counts, so an agent that pushed a bundle and probed
  it read the shim as proof the import landed. `POST .../import` reports its own result.
- **`list_pipelines`** → **`get_project`**. A token binds to exactly one project, so the old name promised a
  collection and the handler wrapped one row in a one-element list to keep the promise. Also ungated: "what am
  I connected to?" is not calibrate-half detail.

### There is no write tool, and adding one is not a registration

Every write the product has — resolving, absorbing or muting a case, accepting a grader edit, triggering an
RCA — either records a human judgement or spends the platform's money, and each is reachable over REST from
the UI where a person is the one asking. So a new tool that writes is not one more `add(...)` call: it is a
decision that an agent may act on a customer's project, and it invalidates a sentence this server tells every
client on connect. `McpCapabilityGateTest` pins that invariant by name.

## Relationship to synthesis

This MCP server is **not** part of evals-synth. Synthesis runs in the plugin's Claude Code
session and writes a `.tessary/` bundle on disk. MCP is a **post-import** read surface: inspect the
imported pipeline, read the cases and findings raised on the project's traffic, page and read its
traces and spans, and query the aggregates. Details on the two synthesis paths:
[`devdocs/reference/architecture.md`](../../devdocs/reference/architecture.md) § *Synthesis (two paths)*.

## Wire details

- **Transport**: MCP Streamable HTTP; replies `application/json` (synchronous tools).
- **Protocol**: JSON-RPC 2.0. Batched requests supported; notifications (`id: null`) dropped.
  Protocol version `2025-06-18`.
- **Methods**: `initialize`, `ping`, `tools/list`, `tools/call`.
- **Tool errors** use `result.isError = true` with `content[0].text` — not JSON-RPC errors
  (those are for protocol failures).

## Out of scope (deferred)

- Whole-pipeline push/pull/diff over MCP (import stays on `POST .../import`).
- SSE streaming until a long-running tool exists.
- Server → client `tools/list_changed` notifications (tool list is immutable per deploy).

## Code map

| Path | Role |
|---|---|
| `backend/.../mcp/McpController.java` | `POST /mcp`; JSON-RPC envelope. Auth is upstream in `AuthFilter`. |
| `backend/.../mcp/McpDispatcher.java` | `initialize` / `ping` / `tools/list` / `tools/call`. |
| `backend/.../mcp/McpToolRegistry.java` | Tool registration; each scopes by `TenantContext.projectId()`. |
| `backend/.../mcp/McpTool.java` | Per-tool descriptor. |
| `backend/.../mcp/JsonRpc.java` | Wire records. |
| `backend/.../auth/AuthFilter.java` | Bearer-only on `/mcp`; cookies ignored. |
| `backend/.../auth/BearerTokenAuthenticator.java` | Verifies bearer via `ApiKeyService`. |
| `backend/.../tenant/ApiKeyService.java` | Issues + verifies keys; bcrypt hash, prefix lookup. |
| `backend/.../tenant/McpTokenController.java` | Settings → MCP tokens (admin mint). |
| `backend/.../test/.../mcp/*` | Dispatcher / tools / MockMvc coverage incl. cross-tenant rejection. |
