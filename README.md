# Tessary

Tessary is an agent-reliability platform: it ingests every trace an agent produces, filters the ones that look bad with cheap classifiers, groups related failures into one case, and explains a case with an agentic RCA (root-cause analysis) run grounded in your own repository.

Agents ship fast and change often, and when one degrades the team usually finds out late and still can't say why. Sampling a few percent of traces catches the big regressions; it structurally cannot catch the small ones, and once an agent is mature, every issue is a small one.

That's why Tessary watches every trace instead of a sample. A cascade of cheap classifiers filters the potentially bad ones at production volume, similar failures group into one investigable case, and RCA explains that case against the trace evidence and your codebase, then proposes the fix as a pull request. The filter has to stay cheap enough to run unsampled: LLM work is the escalation, applied only to what the filter already flagged, never the default path.

The open edition of Tessary is open source under the [Apache License 2.0](./LICENSE); the name and logo are trademarks, see the [trademark policy](./docs/trademarks.mdx). Self-hosting is free; hosted pricing is published at [tessary.ai/#pricing](https://tessary.ai/#pricing).

## Status

- OTLP (OpenTelemetry Protocol) and SDK trace ingest, on an agent-native trace substrate
- Built-in classifiers running over live traffic
- Cases, triage, and agentic RCA (repo-grounded, running in an E2B microVM)
- Vitals (spend, p95 latency)
- Alerting
- Multi-tenant orgs, projects, RBAC (role-based access control), metering, and billing
- An MCP (Model Context Protocol) server

See [What this does today](#what-this-does-today) for how these fit together. The hosted version at [app.tessary.ai](https://app.tessary.ai) runs this code, behind WorkOS AuthKit; a small paid overlay adds hosted-only features and isn't part of this repository.

This tree no longer includes graders, datasets, experiments, or a CI merge gate: an earlier evaluation half was removed outright, not deferred behind a flag. See [What this does not do (yet)](#what-this-does-not-do-yet) for what that leaves out.

Self-hosting? The published documentation starts at [`docs/index.mdx`](./docs/index.mdx) and the walkthrough is [Set up Tessary](./docs/self-hosting/setup.mdx). Developing on the repository? Start with [the documentation map](./devdocs/README.md) and [the architecture reference](./devdocs/reference/architecture.md).

## Architecture

| Layer | Tech |
|---|---|
| Reverse proxy | Caddy 2 (TLS via Let's Encrypt, your own certificate, or a terminator you already run; see [Custom domain](docs/self-hosting/custom-domain.mdx)) |
| Backend | Spring Boot 4.0.x, Java 25 LTS (long-term support), Maven, running on Project Loom virtual threads |
| Frontend | React 19, Vite, TypeScript, TanStack Query, Tailwind |
| Persistence | Postgres 16 with pgvector, migrated with Liquibase. Dev and production run the `pgvector/pgvector:pg16` container; tests use a `pgvector/pgvector:pg16` Testcontainers instance (Docker required). |
| Auth | Email and password in the open edition (a sealed cookie session; no identity provider to configure). Tessary Cloud runs the same code behind WorkOS AuthKit. Per-project bearer tokens and API keys for MCP and headless use. See [auth and MCP reference](./devdocs/reference/auth-and-mcp.md). |
| Telemetry | OpenTelemetry to Grafana Alloy to Grafana Cloud (traces, logs, metrics) |
| Ingest | Direct OTLP (HTTP and gRPC) and the substrate `sdk` push source, no vendor pull |
| Detection | Deterministic and encoder classifiers over a continuous cursor sweep of the substrate, strictly off the ingest hot path |
| Agentic lanes | OpenCode running in an E2B microVM (RCA and Layer-2 triage), driven by the `sandbox-runner` launcher |

Agent work runs out of process by design: every agentic lane gets a fresh microVM, and nothing customer-authored ever executes inside the JVM (Java virtual machine). For the full topology, see [`AGENTS.md`](./AGENTS.md) under *Architecture*, or [`devdocs/guides/local-dev.md`](./devdocs/guides/local-dev.md) for how to run it.

## Quickstart

Just want to self-host it, not develop on it? One command, nothing cloned, nothing to configure:

```bash
docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
```

That reference is the Compose configuration itself, published to Docker Hub as an artifact beside the images. Compose downloads it and runs it, so there is no clone, no `.env` and no editing before first boot. Open <http://localhost> when `docker compose -p tessary ps` reports every service healthy.

Every credential has a working default. Two of them are placeholders published in this repository, so a localhost test drive is all they are for: set `SITE_DOMAIN` with one still in place and the backend refuses to start rather than serving a reachable host on a key anyone can read.

**[`setup.md`](./setup.md) is what to hand a coding agent.** It is written as instructions to one: install, bring every service up, verify the frontend, hand back the URL, stop. It configures nothing inside Tessary, because the product's own setup flow takes over from there. Everything past that point — creating the first account, connecting your traces, replacing the two placeholder keys, pinning a version — is [`docs/self-hosting/setup.mdx`](./docs/self-hosting/setup.mdx).

**[`instrument.md`](./instrument.md) is its counterpart for the other repository** — the one whose traces you want here. Also written as instructions to an agent, but as a workflow rather than a recipe, because it runs against a repository nobody here has seen: read the repository and its own contributor instructions, propose the agent call sites it found and wait for a human to confirm them, extend whatever observability is already there instead of adding a second stack, stamp every model call's span with the `tessary.call_site.id` attribute Tessary needs to attribute a span to a call site, and finish by naming the exact files and variables the endpoint and token belong in. The connect screen's prompt is a single line pointing at it.

The first account is an email and a password; the open edition has no sign-in provider to set up.

From a clone, `docker compose up -d` runs the same stack from the file in this repository, and `bash scripts/quickstart.sh` does it with a readiness check instead of a fixed sleep.

### What a self-hosted instance sends home, and the one switch that stops it

A default install sends one anonymous heartbeat to `home.tessary.ai`, once when the backend starts and then every 24 hours. It carries a schema version and a timestamp, an install id (a random UUID minted at first boot and kept in the database), the edition, the app version, the host OS family and CPU architecture, and coarse bucketed counts of orgs, projects and daily trace volume. It never carries trace or prompt content, an email address, an org or project name, a hostname, or a retained IP address. That heartbeat is the only outbound destination a default install has.

```bash
EVALS_TELEMETRY_ENABLED=false
```

Set that in `.env` and the instance makes no outbound call to that host, DNS lookups included, and loses nothing: no feature, license check or in-app behaviour depends on the heartbeat reaching us. Because the install id lives in the database, a reinstall on a fresh volume counts as a new install on our side; that is the extent of what we can tell apart. The field-by-field contract is [`devdocs/reference/telemetry-contract.md`](./devdocs/reference/telemetry-contract.md), and the self-hoster's explanation is the Telemetry section of [Configuration](./docs/self-hosting/configuration.mdx).

The rest of this section is the **contributor** dev loop — running the repo itself with hot reload, not the packaged self-host path above.

### 1. Run the stack

**Docker (recommended).** You need Docker, `tmux`, and `task` ([Task](https://taskfile.dev)).

```bash
task dev
```

This starts the dev stack in Docker (Postgres, backend, frontend, and Caddy) and opens a tmux session with 4 tabbed windows: `[0] shell` (a cheat sheet, you land here), `[1] backend`, `[2] frontend`, `[3] caddy`. The frontend has Vite HMR (hot module replacement). Rebuild the backend after a Java change with `task dev:reload-backend` from the shell window, or restart fully with `task rb`. Tear down with `task dev:stop`.

Low on Docker memory, or missing HuggingFace credentials? `task dev:slim` skips the classify and compile services. Full details: [local dev guide](./devdocs/guides/local-dev.md).

Open <http://localhost:8000> and create the first account with an email and password — the open edition signs up and signs in locally by default, no WorkOS or SSO configuration required (that applies to Tessary Cloud, not this repo). The account's org and default project are created for you; the connect gate that follows walks you through pointing an exporter at Tessary.

For production (a Spring Boot JVM layered bootJar on a Temurin JRE, with Caddy serving the static build):

```bash
task prod:build       # fast JVM build; dependency layers cache across rebuilds
task prod:up
```

Configuration and secrets for both paths come from environment variables; see [`.env.example`](./.env.example) for the full list.

**Bare metal**, if you'd rather skip Docker. This covers the dev loop only: the three tasks below run in the foreground with no process supervision, so use the Docker path above for anything you plan to keep running.

```bash
brew install maven caddy
curl -s "https://get.sdkman.io" | bash && source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem

# Node packages use pnpm. Each package.json pins the version via `packageManager`, so
# enabling corepack once is all the setup there is; it fetches that exact version on demand.
corepack enable

task node:install     # every Node package; `task frontend:install` for just the frontend

# The backend needs a Postgres 16 with pgvector reachable via EVALS_JDBC_URL, e.g. a local
# `pgvector/pgvector:pg16` container, or `task dev:up` to run just the compose Postgres.
# (The backend test suite always requires Docker: it uses a pgvector Testcontainers database.)
export EVALS_JDBC_URL=jdbc:postgresql://localhost:5433/evals
export EVALS_DB_USERNAME=evals EVALS_DB_PASSWORD=evals

# three terminals:
task backend       # :8080 (connects to EVALS_JDBC_URL)
task frontend      # :5173
task caddy         # :8000
```

`task check` runs the contract check, the backend `mvn verify`, frontend lint and build, and Caddyfile validation. Your local run is the gate: CI runs the same scripts, but every workflow is start-it-by-hand only ahead of the public repository cutover, so nothing runs per pull request or on a schedule. Because both run the same scripts, a green local check means CI would be green too. See [`CLAUDE.md`](./CLAUDE.md).

### 2. Point your agent's traces at it

Send OTLP to `/v1/traces` with a `write`-scoped API key (the connect gate mints one at sign-up; later, **Settings → Sources**). Any exporter aware of OTel-GenAI conventions works; other dialects (OpenInference, Traceloop) normalize to `gen_ai.*` at the ingest edge. Tag spans with the `tessary.call_site.id` attribute to attribute them to a call site (a place in your code that invokes a model): it's a plain span attribute, no SDK required. See the [ingestion contract reference](./devdocs/reference/ingestion-contract/README.md).

Once ingest succeeds, traces begin appearing in the project. Detection itself needs history first: classifiers fit a baseline per call site before they can say anything moved, so a new project stays quiet for a while by design.

### 3. Connect the repo

This is an upgrade, not a setup step. Layer-2 triage runs either way: with a repo connected it rules against the committed `.tessary` bundle and cites files; without one it rules on the finding's own evidence and cites the numbers. Connecting a repo also grounds RCA in your actual code.

The bundle is authored by the evals plugin for Claude Code, from [`tessaryai/plugins`](https://github.com/tessaryai/plugins) (`/plugin marketplace add tessaryai/plugins`), and imported under **Settings → Import**. A successful import shows the pipeline's call sites and failure modes on the Import page; the bundle's grader and quality-dimension shards are ignored, since this tree has nothing to run them with.

## What this does today

The reliability path: watch, filter, group, explain, fix.

1. **Ingest everything.** Direct OTLP (HTTP and gRPC) and SDK push land in an agent-native substrate: 3 fixed levels, `session` → `trace` (one turn) → `span` (one step), each keyed on the producer's own ID, plus the span's payload, tool calls, retrieved documents, and media. Everything normalizes to the OTel `gen_ai.*` conventions at the edge, so one shape reads downstream. PII (personally identifiable information) redaction runs on the OTLP write path, before those spans are stored. It is pattern matching on one of four ingest paths; [`devdocs/concepts/pii-redaction.md`](devdocs/concepts/pii-redaction.md) § *The redaction boundary* states which paths it skips and which classes it does not catch.
2. **Filter cheaply.** Built-in classifiers sweep the substrate continuously (a leased cursor job, strictly off the ingest hot path) and record detections: 2 are deterministic (`secret_leak`, `malformed_output`), 2 are shared ONNX (Open Neural Network Exchange) encoder heads served CPU-side by the standalone classify-service (`frustration`, `groundedness`), 1 profiles behavior drift by n-gram, and 2 watch a call site's latency and cost against its own past. Model cost is paid at train and serve time, not per event.
3. **Group into cases.** Related findings collapse into one investigable case with a lifecycle, surfaced on **Triage**. A finding becomes a case only after an LLM triage step rules it a real deviation rather than a legitimate change.
4. **Explain it.** RCA runs an agentic session (OpenCode in an E2B microVM) over the failing cohort with the trace evidence, a dossier, and (when a repo is connected) the code itself, and returns grounded hypotheses plus the checks it ruled out.
5. **Route it to a human.** Alerts (Slack, webhook, Sentry, Linear, PagerDuty) carry a case to whoever owns it. The platform explains and hands off; it doesn't open the fix itself.

## What this does not do (yet)

- **The metric drift detectors are uncalibrated.** `duration_drift` and `cost_drift` have their alert budget still a guess, and have never fired in production. The false-alarm bar and the measured-versus-assumed split are in [deviation math](./devdocs/concepts/deviation-math.md).
- **`tool_error` is a classifier again**, rebuilt as a rate check over each tool's hourly failure rate rather than a detection per failing call. Vitals no longer reports tool errors: one number, in one place.
- **There is no eval half.** No grader, no judge run, no dataset, no experiment, no golden label, no review queue, and no CI merge gate. Removing them was a decision, not a gap to fill in later.

## Design

The product thesis and competitive frame live in Tessary's internal *Agent Reliability* document, maintained outside this repo. Standing engineering constraints are in [principles](./devdocs/reference/principles.md), and the working summary an engineer needs mid-change is in [`AGENTS.md`](./AGENTS.md) under *Strategic context*. Read those before contributing.

## Contributing

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for how to open a pull request or file an issue, and [`SECURITY.md`](./SECURITY.md) to report a security issue privately.

## Transparency

Every classifier's threshold is human-readable, runnable on your own provider tokens, and yours to take if you leave. The cheap filter is what makes watching every trace affordable; a classifier's false-positive rate at a stated alert budget is shown, not implied, because an unmeasured detector is a liability, not a feature.

Traces are never used to train a shared model, for any customer. See [principles](./devdocs/reference/principles.md#product--positioning) for how that guarantee is enforced.