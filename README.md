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

Tessary is an open-source reliability platform for AI agents in production. It monitors every trace, detects issues using cheap classifiers, groups related findings into cases, and investigates their root cause using trace and repository evidence.

## Get running

Paste this into your coding agent:

```text
Self-host Tessary for me by following https://github.com/tessaryai/tessary/blob/main/setup.md
```

[`setup.md`](./setup.md) is written as instructions to an agent: install what's missing, bring every service up, verify the frontend, and hand back the URL.

Prefer to run it yourself? The same install is one command, with nothing cloned and no `.env` to edit:

```bash
docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
```

Either way, open <http://localhost> when `docker compose -p tessary ps` reports every service healthy. It needs Docker Engine 26 or newer and Docker Compose v2.34 or newer on the machine. [Set up Tessary](./docs/self-hosting/setup.mdx) takes it from there.

### 1. Point your agent's traces at it

Send OTLP traces to the endpoint shown during setup and add `tessary.call_site.id` to spans that invoke a model. Use [`instrument.md`](./instrument.md) to have a coding agent identify and instrument these call sites.

### 2. Connect the repo (optional)

Connect a GitHub repository under **Settings > Git integration** and both triage and RCA cite the code that produced the failing traces.

## How it works

1. **Watch every trace.** OTLP over HTTP and gRPC, and SDK push, normalize to the OpenTelemetry `gen_ai.*` conventions at the edge. PII (personally identifiable information) redaction runs before storage.
2. **Filter cheaply.** Classifiers sweep every trace continuously and open a finding when one fires. The per-trace check stays cheap enough to afford at production volume, which is what makes reading all of it possible instead of sampling.
3. **Group into cases.** Related findings collapse into one case, surfaced on **Triage**. A finding becomes a case only after an LLM triage step rules it a real deviation rather than a legitimate change.
4. **Explain the case.** RCA runs an agentic session over the failing traces and, when a repo is connected, the code itself. It returns grounded hypotheses and the checks it ruled out.
5. **Route it to a human.** An alert carries the case to whoever owns it. Tessary explains and hands off. It doesn't open the fix.

## License

Tessary is licensed under the [Apache License 2.0](./LICENSE).

## Telemetry

A self-hosted instance sends one anonymous heartbeat to `home.tessary.ai` at backend start and every 24 hours after. It carries a schema version and a timestamp, an install id, the edition, the app version, the host OS family and CPU architecture, and bucketed counts of orgs, projects, and daily trace volume. It never carries trace or prompt content, an email address, an org or project name, a hostname, or a retained IP address.

```bash
TESSARY_TELEMETRY_ENABLED=false
```

Set that in `.env` and the instance makes no call to that host, DNS lookups included, and loses nothing: no feature, license check, or in-app behavior depends on the heartbeat reaching us. The field-by-field contract is [the telemetry contract](./devdocs/reference/telemetry-contract.md).

## Contributing

[`CONTRIBUTING.md`](./CONTRIBUTING.md) explains how to open an issue or a pull request, how to run the checks locally, and what to expect from review. [The documentation map](./devdocs/README.md) is where to start reading the rest.

## Security

Report a vulnerability privately by emailing security@tessary.ai. See [`SECURITY.md`](./SECURITY.md) for what to expect.
