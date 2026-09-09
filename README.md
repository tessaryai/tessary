<h1 align="center">
  <img src="docs/logo/tessary-logo.png" alt="" width="48"><br>
  Tessary
</h1>

<p align="center">Stop your agents from failing silently in production.</p>

<p align="center">
  <a href="https://tessary.ai/docs">Docs</a> ·
  <a href="#get-running">Self-host</a> ·
  <a href="https://github.com/tessaryai/tessary/issues">Report an issue</a>
</p>

<p align="center">
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache 2.0 license"></a>
  <a href="https://github.com/tessaryai/tessary/releases"><img src="https://img.shields.io/github/v/release/tessaryai/tessary" alt="Latest release"></a>
  <a href="https://hub.docker.com/r/tessaryai/tessary"><img src="https://img.shields.io/docker/pulls/tessaryai/tessary" alt="Docker pulls"></a>
  <a href="https://github.com/tessaryai/tessary/actions/workflows/check.yml"><img src="https://github.com/tessaryai/tessary/actions/workflows/check.yml/badge.svg" alt="CI status"></a>
</p>

Tessary is an open-source agent reliability platform for engineering teams running AI agents in production. It watches every trace an agent produces, flags the ones that look wrong with cheap classifiers, groups related findings into a case, and explains the case with an RCA (root-cause analysis) run grounded in your own repository.

Agents develop production issues without crashing or returning an error. A prompt edit shifts how often a tool gets called, a model update changes what a response looks like, and nothing in the logs turns red. Most teams sample a few percent of traffic to look for this. Sampling catches the large regressions and structurally misses the small ones, and once an agent is mature, most issues are small. Tessary reads every trace instead, and keeps the per-trace check cheap enough to afford at production volume.

## Get running

Paste this into your coding agent:

```text
Self-host Tessary for me by following https://github.com/tessaryai/tessary/blob/main/setup.md
```

[`setup.md`](./setup.md) is written as instructions to an agent: install what's missing, bring every service up, verify the frontend, and hand back the URL. It needs Docker Engine 26 or newer and Docker Compose v2.34 or newer on the machine.

Prefer to run it yourself? The same install is one command, with nothing cloned and no `.env` to edit:

```bash
docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
```

Either way, open <http://localhost> when `docker compose -p tessary ps` reports every service healthy, and create the first account with an email and password. Three of the shipped credentials are placeholders published in this repository, so this install is for a localhost test drive until you replace them. [Set up Tessary](./docs/self-hosting/setup.mdx) covers that, along with a custom domain, upgrades, and troubleshooting.

### 1. Point your agent's traces at it

After sign-up, Tessary shows the ingest endpoint (your origin plus `/v1/traces`) and a bearer token. Send OTLP (OpenTelemetry Protocol) traces there. Any exporter that follows the OpenTelemetry GenAI conventions works, and OpenInference and Traceloop spans normalize at the edge.

Tag each span that covers a model call with the `tessary.call_site.id` attribute. It is a plain span attribute, no SDK required, and it is how Tessary attributes a span to a call site (a place in your code that invokes a model). Untagged spans arrive but don't trigger classifiers. [`instrument.md`](./instrument.md) is the workflow a coding agent follows to find your call sites, propose them for your confirmation, and add the attribute to whatever tracing you already have.

Expected result: the connect screen opens on the first tagged span and drops you on **Triage**. A new project then stays quiet for a while by design, because classifiers fit a baseline per call site before they can say anything moved.

### 2. Connect the repo (optional)

Connect a GitHub repository under **Settings > Git integration**. Triage and RCA run without it, ruling on the trace evidence alone. With it, both cite the files involved, and RCA reads the code that produced the failing traces.

## How it works

1. **Watch every trace.** OTLP over HTTP and gRPC, and SDK push, land in an agent-native store: a session holds traces, a trace holds spans, and each span carries its payload, tool calls, retrieved documents, and media. Everything normalizes to the OpenTelemetry `gen_ai.*` conventions at the edge. PII (personally identifiable information) redaction runs on the OTLP write path, before storage.
2. **Filter cheaply.** Classifiers sweep the store continuously, off the ingest hot path, and create a finding when their condition is met. Two are deterministic (`secret_leak`, `malformed_output`), one profiles behavior drift by n-gram, one checks each tool's hourly failure rate, and two compare a call site's latency and cost against its own past. Model cost is paid when a classifier is fit, not per trace.
3. **Group into cases.** Related findings collapse into one case, surfaced on **Triage**. A finding becomes a case only after an LLM triage step rules it a real deviation rather than a legitimate change.
4. **Explain the case.** RCA runs an agentic session over the failing traces with the evidence, a dossier, and, when a repo is connected, the code itself. It returns grounded hypotheses and the checks it ruled out. Every agentic run gets a fresh E2B microVM. Nothing customer-authored executes inside the backend process.
5. **Route it to a human.** Alerts to Slack, a webhook, Sentry, Linear, or PagerDuty carry the case to whoever owns it. Tessary explains and hands off. It doesn't open the fix.

An MCP (Model Context Protocol) server exposes the same cases, traces, and RCA reports to a coding agent. See [the auth and MCP reference](./devdocs/reference/auth-and-mcp.md).

## What it doesn't do yet

- **The latency and cost drift classifiers are uncalibrated.** Their alert budget is a guess, and neither has fired in production. [Deviation math](./devdocs/concepts/deviation-math.md) states which numbers are measured and which are assumed.
- **Two classifiers don't run yet.** The `frustration` and `groundedness` classifiers depend on a separate encoder service whose model weights aren't published, so the Compose configuration doesn't start it. Every other classifier runs out of the box.
- **There is no evaluation half.** No graders, datasets, experiments, golden labels, or CI merge gate. An earlier version had them, and removing them was a decision, not a gap to fill later.

## Stack

| Layer | Tech |
| --- | --- |
| Backend | Spring Boot 4, Java 25 on virtual threads, Maven |
| Frontend | React 19, Vite, TypeScript, TanStack Query, Tailwind |
| Persistence | Postgres 16 with pgvector, migrated with Liquibase |
| Reverse proxy | Caddy 2, with TLS from Let's Encrypt or your own certificate |
| Agentic runs | OpenCode in an E2B microVM, launched by `sandbox-runner` |
| Auth | Email and password; per-project bearer tokens and API keys for ingest and MCP |

The full topology is in [the architecture reference](./devdocs/reference/architecture.md), and the standing engineering constraints are in [principles](./devdocs/reference/principles.md).

## License

Tessary is licensed under the [Apache License 2.0](./LICENSE). The Tessary name and logo are trademarks; see the [trademark policy](./TRADEMARK.md).

Traces are never used to train a shared model, for any customer. Every classifier threshold is human-readable, runs on your own provider tokens, and is yours to take if you leave. [Principles](./devdocs/reference/principles.md#product--positioning) states how that guarantee is enforced.

## Telemetry

A self-hosted instance sends one anonymous heartbeat to `home.tessary.ai` at backend start and every 24 hours after. It carries an install id, the edition and version, the host OS family and CPU architecture, and bucketed counts of orgs, projects, and daily trace volume. It never carries trace or prompt content, an email address, an org or project name, a hostname, or an IP address. That heartbeat is the only outbound destination a default install has.

```bash
TESSARY_TELEMETRY_ENABLED=false
```

Set that in `.env` and the instance makes no call to that host, DNS lookups included, and loses nothing. The field-by-field contract is [the telemetry contract](./devdocs/reference/telemetry-contract.md).

## Contributing

The dev loop needs Docker, `tmux`, and [Task](https://taskfile.dev). `task dev` starts Postgres, the backend, the frontend with hot reload, and Caddy on <http://localhost:8000>, and `task check` runs the same checks as CI. [The local dev guide](./devdocs/guides/local-dev.md) covers both, including the bare-metal path, and [the documentation map](./devdocs/README.md) is where to start reading the rest.

[`CONTRIBUTING.md`](./CONTRIBUTING.md) explains how to open an issue or a pull request, and what to expect from review.

## Security

Report a vulnerability privately by emailing security@tessary.ai. See [`SECURITY.md`](./SECURITY.md) for what to expect.
