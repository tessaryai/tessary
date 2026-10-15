# Agent sandbox E2B template (v2 SDK build)

The microVM every agentic lane runs in: it materializes a finding's dossier, clones the target
repo at HEAD when the caller sends a clone URL, and runs **OpenCode** over them. Defined in code
with the **E2B v2 build system** (no `e2b.toml`). The sibling `Dockerfile` builds the same runtime
as the Docker backend's agent image.

## Files
- `template.ts` — the image definition (base image, `git`, the `opencode-ai` CLI +
  `@opencode-ai/sdk` pinned in lockstep, and the in-VM scripts).
- `agent-stream.js` — the shared OpenCode runner: starts `opencode` as a server and drives it
  through the SDK. Required by `lane.js` and `session.js`.
- `mcp-relay.js` — a loopback MCP proxy with named upstreams: each name is served at
  `/mcp/<name>` and forwards to its own URL with its own bearer token, so no token reaches
  OpenCode's config. Every `tools/call` result is saved whole to `checks/mcp/NNN-<tool>.json` (one
  counter across all upstreams), and a large one comes back to the agent as a summary. Used by
  `agent-stream.js` on the system-prompt path, and by `session.js` for the session's named servers.
- `lane.js` — the three one-shot lanes, run as `node lane.js <rca|triage|run> <input.json>`. Each
  materializes the caller's dossier under `dossier/` and runs the agent wired to the platform's MCP
  surface, emitting the schema-constrained answer (or prose, for `run` with no `json_schema`). (Node
  builtins only.)
  - `rca` — the finding-anchored root cause. The clone is optional and read-only; with one, the
    prompt's `{onset_commit}` becomes the newest commit before `onset_at`, or `unknown`. It may never
    edit.
  - `triage` — the Layer-2 ruling. It never clones: no repository says whether a claim about
    production traffic is true. It may edit anywhere under the work dir; `checks/` is where its own
    scripts go.
  - `run` — the generic run behind the backend's `AgentRunService` (the `AGENT_RUN` lane), under
    the caller's own system prompt. The clone is optional and read-only (`edit` denied under
    `repo/`); everywhere else under the work dir is writable.
- `session.js` — one long-lived agent session: reads and deletes `session.json`, starts the relay
  with the session's named MCP servers and one `opencode serve`, and serves `POST /message`,
  `POST /abort`, `GET /events` and `GET /healthz` to the launcher behind a per-session secret. It
  maps OpenCode's event stream to `text`, `tool_start`, `tool_end`, `message_end`, `error` and
  `closed` events. The contract is in [`sandbox-runner/README.md`](../README.md#agent-sessions-sessionjs).
  The launcher runs it with `/usr/local/bin/node-sealed`, a copy of `node` both recipes make. In
  the Docker image the copy is execute-only: a process started from a binary its user cannot read
  is not dumpable, so the agent, which runs as the same user, cannot read the relay's MCP tokens out
  of its memory or `SESSION_SECRET` out of its environment through `/proc`.
  `test/session-image.test.js` checks this in a built image (skipped unless
  `SESSION_IMAGE_UNDER_TEST` names one). The E2B template has no such protection: E2B's build does
  not keep the mode, and the sandbox user has passwordless `sudo`.
- `build.ts` — the ONLY thing in this repo that talks to E2B: builds, verifies and tags the
  template. Published in our project as **`tessary-agent-sandbox`**, and **public**, so everyone
  else reaches it as **`tessary/tessary-agent-sandbox`** — which is what the launcher's
  `E2B_ANALYZER_TEMPLATE` default says.

## Build / publish

Releases do this on their own. `.github/workflows/release.yml` drives the four verbs below from the
`E2B_API_KEY` repository secret, and only rebuilds when the recipe actually changed — see
[`sandbox-runner/README.md`](../README.md#deploy) for the job order and why it splits the way it
does.

By hand (a dev template, or the first build in a fresh E2B project):

```bash
cd sandbox-runner/agent-sandbox
pnpm install
echo "E2B_API_KEY=<your team's key>" > .env     # or export it
pnpm exec tsx build.ts                            # == pnpm run build
```

| verb | does | when |
|---|---|---|
| *(none)* | build + publish under the `default` tag | by hand |
| `--release=<semver>` | build **only if the recipe hash changed**, tag `<semver>` + `recipe-<hash>` | `build-agent-template` |
| `--verify=<semver>` | assert public + namespaced name, then boot it and run five checks | `verify-agent-template` |
| `--promote=<semver>` | move `latest` and `default` onto that build | `finalize` |
| `--rollback=<semver>` | remove the `<semver>` tag, keep `recipe-<hash>` | `cleanup` |

- The `E2B_API_KEY` must be for the **same team** that owns the template, or the build lands in a
  different project under a name nothing references.
- The name is stable, so rebuilds update the same template; tags are what distinguish builds.
- Verify: `e2b template list` shows `tessary/tessary-agent-sandbox`.

> Network egress: this template reaches github.com + the model provider at run time. Credentials are never baked in —
> the launcher injects them per invocation.
