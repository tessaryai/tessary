# tessary

An agent-reliability platform: ingest every trace, filter it with cheap classifiers, group the
survivors into cases, and explain a case with an agentic root-cause run grounded in the customer's
own repository. The `.tessary/` pipeline bundle authored by the **evals plugin** (a Claude Code
plugin in a separate repo) is an INPUT — it names call sites, failure modes and intent, and Layer-2
triage rules against it. Nothing in this tree synthesises, runs or scores a grader: this repo has
no graders, datasets, experiments, review queues, or observer. Details:
[`devdocs/reference/architecture.md`](./devdocs/reference/architecture.md).

The product thesis lives in Tessary's internal *Agent Reliability* document,
not in this repo — see *Strategic context* at the bottom for the working summary.
[`devdocs/reference/principles.md`](./devdocs/reference/principles.md) carries the standing
engineering constraints, and [`devdocs/README.md`](./devdocs/README.md) maps the rest of the docs.

## Architecture

```
                     ┌──────────────────────────────┐
                     │ Caddy (:8000) — reverse proxy │
                     └──────┬─────────────────┬─────┘
                  /api/*    │                 │   /
                            ▼                 ▼
              ┌──────────────────┐   ┌──────────────────┐
              │ Spring Boot      │   │ Vite dev (:5173) │
              │ backend (:8080)  │   │ or static build  │
              │ — JVM + Loom     │   │ React + TS       │
              └────┬─────────────┘   └──────────────────┘
                   │ reads / writes            + classify-service (encoder
                   ▼                             /classify heads; separate deploy)
                Postgres (pgvector; per-project pipeline, substrate, findings, cases)
```

| Layer | Tech |
|---|---|
| Reverse proxy | Caddy (`Caddyfile`) — `/api/*`, `/auth/*`, `/mcp` → backend; rest → Vite/static |
| Backend | Spring Boot 4.0.x, Java 25 + Loom virtual threads, an eleven-module Maven reactor (layering in [`devdocs/modules.md`](./devdocs/modules.md)), LangChain4j, Postgres via JdbcClient + Liquibase |
| Frontend | React 19, Vite, TypeScript, TanStack Query, react-router-dom 7, Tailwind v4 (token-driven design system) |
| Auth | WorkOS AuthKit (sealed cookie session); per-project bearer tokens / API keys for MCP + headless ([`devdocs/reference/auth-and-mcp.md`](./devdocs/reference/auth-and-mcp.md)) |

## Repo map

| Path | What it is |
|---|---|
| [`backend/`](./backend/) | The Java backend — conventions in [`backend/AGENTS.md`](./backend/AGENTS.md), inventory in [`devdocs/reference/architecture.md`](./devdocs/reference/architecture.md), module layering in [`devdocs/modules.md`](./devdocs/modules.md) |
| [`frontend/`](./frontend/) | The React app — conventions in [`frontend/AGENTS.md`](./frontend/AGENTS.md) |
| [`classify-service/`](./classify-service/) | Standalone encoder `/classify` service (ECS Fargate) — see its README |
| [`sandbox-runner/`](./sandbox-runner/) | The launcher that runs every agentic lane (RCA, Layer-2 triage) in a fresh E2B microVM — see its README |
| [`classifiers/`](./classifiers/) | The Python classifier tree: the shared eval framework, the `tool_error` and `metric_drift` rigs that check the open Java detectors, and the corpus emitters |
| [`contract/`](./contract/) | Vendored evals-synth output contract (`scripts/sync-evals-contract.sh`). Files are verbatim copies; `contract/tests/` is OURS — the gate for the vendored validator, since the plugin repo is public and runs no CI |
| [`claude-skill/`](./claude-skill/) | Claude Code integration helpers (the MCP skill + prompt-craft reference) |
| [`docs/`](./docs/) | Reference, concepts, guides — start at [`devdocs/README.md`](./devdocs/README.md) |
| `scripts/`, `observability/` | The shared check and deploy scripts; Grafana dashboards |

**Before editing under `backend/` or `frontend/`, read that directory's `AGENTS.md`.** Running
the stack locally: [`devdocs/guides/local-dev.md`](./devdocs/guides/local-dev.md). Tessary's own
hosted deployment is documented privately and is not part of the public export. Cross-stack recipes (schema
change, new classifier): [`devdocs/guides/common-tasks.md`](./devdocs/guides/common-tasks.md).
Config keys: [`devdocs/reference/config-keys.md`](./devdocs/reference/config-keys.md).

## Universal rules

- **No comments in code changes** unless they explain a non-obvious invariant, a workaround
  for a specific bug, or behavior that would surprise a reader. Well-named identifiers are the
  documentation.
- **NEVER keep old implementation as fallback.** Delete dead code.
- **No example files or demo code** unless explicitly requested.
- **User-visible copy follows [`handbook/voice-and-tone.md`](./handbook/voice-and-tone.md).**
  Plain words, active voice, no em-dashes, no hedging. Applies to UI strings, errors, Slack, CLI
  output and docs prose; not to code comments.
- **Do not add or modify tests** unless explicitly requested.
- **The schema is the source of truth.** The evals plugin owns the bundle schema; absorb changes
  in order: `contract/` → backend records → frontend types → views. The plugin still emits grader
  and quality-dimension shards this tree has nothing to run; `BundleAssembler` routes them to
  `Shard.IGNORE` rather than rejecting the bundle, and that is deliberate.
- **Python uses uv, never pip or poetry.** `classifiers/` owns a `pyproject.toml` + `uv.lock`;
  the gate runs `uv sync --frozen`, so a stale lockfile is a failure rather than a silent
  re-resolve.
- **Node packages use pnpm, never npm.** Each has its own `pnpm-lock.yaml`; they are deliberately
  NOT a workspace, so every Dockerfile can build from its own directory. Two consequences worth
  knowing before you touch one: pnpm 11 keeps settings in `pnpm-workspace.yaml` rather than the
  `pnpm` field in `package.json` (which it ignores silently), and dependency build scripts are
  blocked unless allowlisted there under `allowBuilds`. The single exception is the in-sandbox
  `npm install` in `sandbox-runner/agent-sandbox/template.ts`, which is intentional.

## Working loop

- Batch independent tool calls; don't batch dependent find→read chains.
- Prefer `Read`/`Grep` over dumping files through the shell; edit only after reading constraints in this file + the scoped `AGENTS.md`.
- Iterate on `task check -- <slices>`; gate on bare `task check`.

## Validation

Run the slices your change touches (`task check -- rca,metering` or `task check -- frontend`),
and the bare `task check` before merging. **CI runs the same gate on every pull request** —
`.github/workflows/check.yml` calls `scripts/check.sh`, the same manifest `task check` runs, so local
green means CI green by construction; `secret-scan.yml` is armed alongside it. Those two are the only
workflows that run on their own; everything else is `workflow_dispatch:` only, with no cron anywhere.
Nothing is merge-blocking (branch protection is plan-gated on this tier), so a red check still has to
be respected by a human. Docker is required for any backend slice. Full cost model and recount commands:
[`devdocs/reference/test-suite.md`](./devdocs/reference/test-suite.md).

## Documentation policy

Where a new piece of documentation goes, by kind:

1. **Imperative rule an agent must follow while editing code under a directory** → that
   directory's `AGENTS.md` (`backend/`, `frontend/`); this root file only for rules that apply
   repo-wide.
2. **Lookup / reference material** (tables, inventories, schemas, topology) →
   `devdocs/reference/`.
3. **Step-by-step runbook or cross-stack recipe** → `devdocs/guides/`.
4. **Why-explanations of a subsystem** → `devdocs/concepts/`.
5. **Durable engineering constraint** → `devdocs/reference/principles.md`. **Product thesis,
   positioning, and market framing do NOT live in this repo** — they live in Notion, and a PR
   that adds a strategy doc here is adding a second source of truth that will go stale.
6. **Deferred or planned work** → the roadmap, which is maintained privately and is not part of the
   public export.

Rules that keep this working:

- **One home per fact.** Never duplicate a rule across files — restate at most one line and
  link to the owning doc.
- **Same-PR co-update.** A code change that invalidates any of these docs updates the doc in
  the same PR (schema changes update `devdocs/reference/data-model.md`; package-set changes
  update the architecture inventory; controller/DTO changes regenerate the OpenAPI spec). The
  classifier-quality reference page lives outside this tree; its gate
  `scripts/check-classifier-quality-doc.sh` stays here and skips with a named reason wherever the
  page is absent.
- **Size budgets.** This file stays ≤ ~150 lines; a scoped `AGENTS.md` ≤ ~250. When a budget
  is blown, extract reference material to `devdocs/reference/` instead of growing the guide.
- **New top-level code directory** → gets a `README.md`; add an `AGENTS.md` only once it
  accrues agent-imperative conventions (the `frontend/` precedent).
- After moving or renaming a doc, `grep -rn` for the old path and retarget every link in the
  same PR.

## Strategic context

Enough to make a design call without leaving the repo; the full thesis is Tessary's internal *Agent Reliability*
document, which is authoritative and the only place it is maintained.

We watch **every** production trace — not a sample, because once an agent is mature every
failure is a low-percentage failure and sampling structurally cannot find them. A cascade of
**cheap** classifiers filters the potentially-bad ones, which are grouped into failure modes
and root-caused against the customer's repo. We sell the cause, not the chart.

The moat is that cheap filter, which yields two rules for code in this repo:

- **Anything that scales per-event LLM cost with ingest volume attacks the product directly.**
  LLM work is the *escalation*, applied to what a cheap filter already flagged — never the
  default detection path.
- **An unmeasured detector is a liability, not a feature.** A filter's false-positive rate at a
  stated alert budget is the asset.

If a choice in the codebase doesn't fit that framing, surface it before implementing.

[`devdocs/reference/principles.md`](./devdocs/reference/principles.md) § *Product & positioning* now
carries only the engineering constraints that fall out of this framing; the framing itself is
maintained in Notion and summarised above.
