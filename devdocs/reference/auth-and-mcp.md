# Auth & MCP

How browser sessions, headless API keys, MCP tokens, and `POST /mcp` fit together. Imperative
rules for agents editing auth code live in
[`../../backend/AGENTS.md`](../../backend/AGENTS.md) § *Auth + tenancy*. Client setup for Claude
Code lives in [`../../claude-skill/evals-mcp/README.md`](../../claude-skill/evals-mcp/README.md).

## Surfaces

| Surface | Credential | Notes |
|---|---|---|
| Browser UI | WorkOS AuthKit (BYO) or built-in email/password (open-edition default, `PasswordAuthProvider`) → local AES-GCM sealed cookie (`tessary-session`) | Cookie preferred on `/api/**`; no JWKS on the hot path |
| Headless REST | `Authorization: Bearer` API key | Managed in Settings → API keys; scopes `write` / `query` / `admin` |
| MCP (`POST /mcp`) | Same bearer store; cookies ignored | Minted in Settings → MCP tokens (admin-scoped) or plugin device-link |
| Device-link | `/auth/link/start\|poll` + browser confirm | Bypasses cookie auth; the `device_code` is the credential until exchange |
| Actuator | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Exactly these three, so orchestrators can poll before anything holds a credential. Every other actuator path requires auth — health groups, the bare `/actuator` index, and anything added to `management.endpoints.web.exposure`. Exposing an endpoint does not publish it |

`AuthFilter` bypasses every request **only when `TESSARY_AUTH_DISABLED=true` is set** —
unconditionally, regardless of which `AuthProvider` (WorkOS or the
always-enabled `PasswordAuthProvider`) is active. Absent that flag, every guarded path fails
CLOSED and answers 401: an unconfigured WorkOS is the normal state of a self-hosted open-edition
instance and must not be read as consent to serve it open, and the open edition always
has a working provider (`PasswordAuthProvider`) anyway, so "no provider configured" is no longer a
real state. The dev stack and the test suite both set the flag explicitly. In the `production`
profile, `AuthRequiredInProdGuard` refuses to boot with no provider at all.

## One key store

All project-scoped bearer tokens live in the `api_key` table and are issued/verified by
`tenant/ApiKeyService`. There is no separate `mcp_token` table or `McpTokenService`.

| Family | Prefix | Typical use |
|---|---|---|
| `write` | `tsy_w_…` | Ingest (`POST /v1/traces`) |
| `query` | `tsy_q_…` | Aggregation-first query API |
| `admin` | `tsy_a_…` | Superset: write + query + `/mcp` tools |

MCP token UI (`McpTokenController`) and the plugin device-link handshake mint **admin**-scoped
keys via `ApiKeyService.issue(...)`. `AuthFilter` / `BearerTokenAuthenticator` verify any live
key and populate `TenantContext`; MCP tools always read `ctx.projectId()`.

**Verification is cached, and every revocation path must evict.** `ApiKeyService.verify` answers a
recently-seen token from `tenant/VerifiedTokenCache` without a query or a bcrypt — bcrypt at cost 10 was
62% of backend CPU on the ingest path, for a credential that never changes between requests. Because a
cache that outlives a revocation is an authentication bypass, three things hold rather than one TTL:
`revoke`/`rotate` evict the key in the same call that writes the revocation; **project deletion**
(`TenantService.deleteProjectAsync`, which revokes a project's whole key set straight at the repository)
calls `invalidateProject`; and every invalidation bumps a generation that a verification already in
flight must still match before its result is cached. `tessary.auth.token-cache.ttl-seconds` is the backstop
for changes that reached the database without going through this class at all, not the revocation
control. **Any new bulk-revocation path has to invalidate too** — the ingest front doors authenticate on
the key alone, so a stale entry there keeps a deleted project writable. Keys and bounds:
[config-keys.md](./config-keys.md).

## MCP server

Hosted in-process: `mcp/McpController` → `McpDispatcher` → `McpToolRegistry`. Streamable HTTP
over JSON-RPC 2.0; tools share the same services as REST (pipeline, cases, query,
substrate, findings). Tool inventory and wire details:
[`claude-skill/evals-mcp/README.md`](../../claude-skill/evals-mcp/README.md).

**The surface is read-only.** No tool writes a row, spends a token, or starts an agent run, and
`initialize` states that to every client on connect as a property of the surface rather than of any
one tool. Case lifecycle (resolve / absorb / mute) and triggering an RCA are both
REST-and-UI actions, because each records a human judgement or spends the platform's money. So
adding a write tool is not one more registration: it is a decision that an agent may act on a
customer's project, and it invalidates that sentence. `McpCapabilityGateTest` pins the invariant —
no registered tool name is write-shaped, and none of the tools removed by the cutover
(`propose_grader_edit`, `run_triage`, `get_triage`, `latest_triage`, `list_rca_reports`,
`get_rca_report`) or by the removal of grading (`list_graders`, `get_grader`, `list_quality_dimensions`) is back.

**`get_finding_evidence` is the door the analysis lanes read through.** A detector enumerates the
population its claim rests on at finding-open — one `finding_evidence` ref per measured row, uncapped —
and this tool pages those refs back out: `{role, grain, session_id?, trace_id?, span_id?, rank?}` +
`next_cursor`, plus `count_only=true` for the per-role sizes with no rows. Refs are ids, not bodies;
the caller follows one with `get_trace` / `get_span` / `list_spans`. That is what lets triage and RCA
ship a dossier of the finding's *claim* alone and fetch the traffic on demand, rather than hydrating
traces into a prompt — the materialize-what-is-finding-specific, read-substrate-over-MCP posture both
lanes take. It is scoped and gated exactly like `get_finding` (same service, same project scope, a
cross-tenant id reads as not-found), and there is deliberately **no server-side sampling mode**: an
agent that wants a stride or a draw takes it and says so in its citation.

Each tool declares the capability an org must hold to be offered it (`McpTool.capability`), and
`tools/list` answers per-token from that — so a partner is never shown a tool that cannot work for
them, and a call to a withheld tool reads as unknown rather than forbidden. The line: the launch
product's own output is open (project, imported taxonomy, cases and the RCA reports they carry,
findings, query, the substrate list/read tools). The full catalogue is **19 tools, and every one of
them is open** — `McpTool.capability` is null on all of them. It was 22 with two gated on `GRADERS`
until grading was deleted and took three with it: `list_graders` and `get_grader` (the gated pair)
plus the open `list_quality_dimensions`, whose axes each named a grader; the mechanism stays, because
a paid classifier's own reads are the obvious next thing to want it. `McpCapabilityGateTest` pins the
count alongside the read-only invariant.

**No MCP tool is RCA-gated.** `Capability.RCA` used to gate five of them, for spend — `run_triage`
started a platform-paid agent session. Now that a finished report reaches MCP inlined on the case
that owns it (`get_case.rca`), an org without RCA simply has no report rows to inline: the gate
moved from the tool to the data, which is where it was always more honestly enforced. The flag
still gates the REST trigger and the UI. The reasoning is in `McpTool`'s javadoc.

**The triage ruling is redacted from three tools.** Layer-3 RCA must receive a finding id and
nothing else, and it reaches this surface with a project-scoped admin key like any other caller, so
`get_finding`, `list_findings` and `get_case` strip the triage ruling before it leaves the server:
`triageVerdict`, `triageAction`, `triageSummary`, `triageCitations` and `triagedAt` on the finding
views, and `ruling` on the case. `BehaviorFindingView.withoutTriage()` is the redaction and
`McpToolRegistry` applies it; `McpFindingToolsTest` pins it. Two deliberate survivals:
`triageStatus`, because it says only whether a ruling exists and the findings list needs it to
render, and the case's inline `rca`, because an earlier RCA report is this lane's own prior work
rather than the gate the firewall exists to check. It applies to **every** MCP caller, not to
RCA-minted keys alone: `TenantContext` carries no marker for the key family and `KeyScope` is too
coarse to tell them apart, and a firewall that depends on identifying its caller is a firewall with
a bypass. What the surface loses is a ruling a human can see one click away in the UI, which reads
these fields through `FindingController` (renamed from `BehaviorController`) and is untouched. Background:
[`architecture.md`](./architecture.md) § *The three analysis layers*.

## Known limitations

- **`list_findings` truncates silently.** Each `TriageSource` (`BehaviorTriageSource`, and
  `ConformanceTriageSource` where the paid conformance classifier is enabled) caps its own page at
  its own `DEFAULT_FINDING_LIMIT = 200` — no `limit`/`cursor` args — and `FindingService` just
  concatenates every source's page, so the total returned scales with the number of registered
  sources (200 per source; up to 400 with conformance enabled). Nothing in the response marks the
  list as partial. A project with more confirmed findings than the effective cap gets that many
  back with no signal that they aren't all of them.
- **The resolved-case page sorts, not seeks.** `list_cases` orders open/muted cases off
  `ix_eval_case_live_rank`, but there is no index on `(project_id, resolved_at DESC)` for resolved
  cases — a `state=resolved` page sorts at query time instead of seeking through an index.
- **`list_spans` and `query_search(dataset=spans)` page on a column with no matching index.** Their
  keyset predicates run on `span.created_at`, but the only time index on `span` is
  `ix_span_project_started (project_id, started_at DESC)` — there is no `created_at` index.
- **`get_trace` reports one span count, not two.** Its response carries the rollup's own
  `span_count` as the true total (even when `spans_truncated` is set); there is no separate
  `span_count_total` field.

## Code map

| Path | Role |
|---|---|
| `auth/AuthFilter` | Cookie + bearer resolution; `/mcp` bearer-only |
| `auth/BearerTokenAuthenticator` | Shared Bearer → `TenantContext` |
| `auth/link/*` | Device-authorization for Claude Code connect |
| `auth/SignupPolicyService` | The sign-up policy gate: `admit` after authentication, before any principal; `update` writes the governing org's `settings.signupPolicy` + an audit row |
| `tenant/SignupPolicy` | The policy record: `open` / `domain` / `invite`, parsed from and written into `organization.settings` |
| `tenant/OrganizationController` `GET`/`PUT …/signup-policy` | Owner/admin read and write of the instance policy; `PATCH …/orgs/{slug}` refuses a differing policy in the raw blob |
| `tenant/ApiKeyService` | Issue / verify / revoke (bcrypt at rest, prefix lookup); verification is served from `VerifiedTokenCache` |
| `tenant/VerifiedTokenCache` | Short-lived memory of verified tokens, so bcrypt is off the per-request path. Every revocation path must evict it |
| `tenant/ApiKeyController` | Managed API keys (scoped) |
| `tenant/McpTokenController` | MCP personal tokens (admin mint shape) |
| `mcp/*` | JSON-RPC MCP endpoint + tools |
