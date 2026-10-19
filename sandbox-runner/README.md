# sandbox-runner

Runs the platform's own **agent** in an isolated sandbox: agentic RCA (`/rca`), Layer-2 triage
(`/triage`) and the generic agent run behind `AgentRunService` (`/runs`). The Java backend has no E2B SDK and no Docker client, so it calls this Node sidecar over
HTTP and the sidecar owns the sandbox lifecycle — one request, one fresh sandbox, torn down after.
The one exception is a [launcher session](#launcher-sessions): one sandbox serves one session's
messages until the session closes, and is never shared with another session.

## What this was, and why the name changed

It was `sandbox-runner`, and it ran `kind: deterministic` grader code — LLM-generated JavaScript,
treated as untrusted and never run in the backend process. Grading was removed
from the platform, and with it five of the seven routes this service served:

| route | ran | fate |
|---|---|---|
| `/grade` | user-authored grader code | gone with grading |
| `/lint` | the grader lint gate | gone with grading |
| `/codegen` | deterministic-grader code authoring | gone with grading |
| `/synthesize` | judge-prompt authoring | gone with grading |
| `/analyze` | the git observer's drift analysis | gone with the observer |
| **`/rca`** | agentic root-cause analysis | **survives** |
| **`/triage`** | Layer-2 ruling on a classifier finding | **survives** |
| **`/runs`** | a generic agent run over caller-supplied files, the repo and the traces | **added 2026-10** |

Three whole subtrees went with them: `lambda/` (the AWS Lambda grading executor and the shared
`harness.js`), `template/` (the air-gapped E2B grader sandbox), and the `grade.js` / `lint.js` /
`codegen.js` / `codegen-harness.js` / `synthesize.js` / `analyze.js` scripts under
`agent-sandbox/`. `deploy-grader-lambda.yml` went with the first of those.

**Two E2B cloud templates are affected and only one is built by this repo.** The analyzer template
was republished under the name `tessary-agent-sandbox` (was `evals-observer-analyzer`) and is
**published public**, so it is reachable as `tessary/tessary-agent-sandbox` by any E2B key rather
than only the team's. Building it is **no longer a human step**: `.github/workflows/release.yml`
publishes it from the same dispatch that publishes the four images, tags it with the release's
version, boots it to prove it runs, and repoints its floating tags in finalize — see
[Deploy](#deploy) below. The grader template's alias, `evals-grader-runner`, is simply orphaned in
E2B; nothing in this tree can delete it.

## Layout

- `launcher/` — the HTTP sidecar (`server.js`). Owns the two backends below and the
  [launcher sessions](#launcher-sessions).
- `agent-sandbox/` — the agent sandbox: `lane.js` (the rca, triage and run lanes), the long-lived agent
  session `session.js` (see [Agent sessions](#agent-sessions-sessionjs)), the shared
  `agent-stream.js` OpenCode runner and `mcp-relay.js`, and TWO INDEPENDENTLY MAINTAINED RECIPES for
  one runtime — `template.ts` (the E2B template, published by `build.ts`) and `Dockerfile` (the
  published agent image the Docker backend spawns). Change one, change the other.

## Security model

The agent this service runs is OURS, not a customer's code, and that is the one fact the whole model
now rests on. Until grading was removed it also executed untrusted, LLM-generated grader JavaScript, and most of
what follows was written for that; the boundaries described are unchanged, but what they contain is
narrower.

- **Isolation:** the E2B backend's microVM is a kernel-level boundary. The **Docker** backend gets
  an equivalent process-level one — a fresh, hardened sibling container per request (`--cap-drop=ALL`,
  `no-new-privileges`, its own bridge network). Both are equally isolated; `task dev` builds
  the Docker image from this checkout, so it is also the default for local development, not
  only for a self-hosted install.
- **Credentials and egress:** the agent NEEDS both — a repo clone and a model-provider call — so
  AGENT_POSTURE hands it the provider credentials `agentEnvs()` derives and puts the container on the
  sandbox bridge network. `E2B_API_KEY` stays in the launcher and is never sent to the backend or
  into a sandbox.
  - An **egress credential** (`credential.egress_secret`, Anthropic only, E2B backend only) carries
    no key: it names a secret in E2B's own store, which E2B's egress proxy injects as the provider's
    `x-api-key` outside the microVM. The agent gets a placeholder, and that run's or session's
    egress is limited to the model provider, the MCP door, the clone host and `EGRESS_EXTRA_ALLOW`;
    for a session the MCP door is every `mcp_servers` host.
  - The other posture, UNTRUSTED_POSTURE (empty env, `NetworkMode: 'none'`), has **no route today**:
    its callers were `/grade` and `/lint`. It is kept deliberately — the pair exists so that a
    future untrusted-content route cannot default into the full credential set, and one legal value
    is a default wearing a parameter's clothes.
- **Fail-closed:** transport failure, a non-zero script exit, a timeout or malformed output all reach
  the backend as a classified 502 (`kind`, `timeout`, `error_class`, `detail`), never as a silent
  success. `detail` is launcher-authored or scrubbed SDK metadata only — never the clone URL, sandbox
  output, model output, or repo content.
- **Token scrubbing:** the short-lived clone token rides in the clone URL and is scrubbed from every
  line this launcher logs, and from the scripts' own output before it leaves the sandbox.
- **Sessions:** a session sandbox serves `session.js` on `SESSION_PORT`, behind its own 32-byte
  `SESSION_SECRET`. The script reads it from its env at start, and no child inherits the secret. On
  Docker it runs from an execute-only `node` (`/usr/local/bin/node-sealed`), which makes it
  non-dumpable: the agent runs as the same user but cannot read the script's memory (the MCP
  tokens) or its environment (the secret) through `/proc`. This holds because the agent image
  installs no `sudo`. On E2B there is no such protection: the template build does not keep the
  mode, and the sandbox user has passwordless `sudo`, so the agent can read both. There the tokens
  are guarded only by the microVM holding one session. On E2B that port is public through
  the sandbox host, and the secret is its only gate. With `SANDBOX_NETWORK_ISOLATION` on,
  the launcher joins the isolated bridge to reach the sessions, so on that bridge the sandboxes can
  reach the launcher's port (which needs `SANDBOX_API_KEY`) and each other's session port (which
  needs that session's secret); they still cannot reach the service network.

## Agent sessions (`session.js`)

`agent-sandbox/session.js` keeps one OpenCode session alive for many messages, where the lane
scripts run one prompt and exit. It is generic: it knows a system prompt, files, a model and named
MCP servers, and nothing about what the caller builds with them. A launcher runs it once per
session as `/usr/local/bin/node-sealed session.js /home/user/session.json` (E2B) or
`/usr/local/bin/node-sealed session.js /work/session.json` (Docker), with the copy of `node`
both recipes make (execute-only on Docker only; see Sessions under the security notes above), and
drives it over HTTP.

### Start

| Item | Value |
|---|---|
| `session.json` | `{ system_prompt, files, model, mcp_servers: [ { name, url, token } ], max_tool_calls_per_message }`. The script reads it and deletes it before anything else runs, so no token is on disk while the agent runs. |
| `files` | Relative path to content, written under `<WORK>/dossier/` (the lanes' layout). A path that escapes is a start failure. |
| `mcp_servers` | Names match `^[a-z][a-z0-9-]{0,31}$` and are unique. The tokens go only to the relay (`mcp-relay.js`), which serves each name at `http://127.0.0.1:<port>/mcp/<name>`; OpenCode's config gets one remote entry per name with no headers. |
| `max_tool_calls_per_message` | An integer from 1 to 1000. The session agent `tessary-session` gets `steps = max_tool_calls_per_message + 1`, and the script aborts a reply whose tool calls go past it (see `reason` below). |
| `SESSION_SECRET` | Env var, at least 32 characters (the launcher sends 32 random bytes as hex). The script reads it, then removes it from its env before it spawns `opencode serve`, so no child inherits it; on Docker the script's own start-time environment stays unreadable to the agent only because the script runs from the execute-only `node`, and on E2B the agent can read it. Missing or short: `SESSION_SECRET is required` on stderr, exit 2, nothing listens. |
| `SESSION_PORT` | Env var, default `9400`. The launcher sets it from `SANDBOX_SESSION_PORT`, default 9400; the tests use free ports. The session server binds `0.0.0.0`; OpenCode and the relay stay on `127.0.0.1`. |
| Permissions | The lanes' base (`basePermission` in `agent-stream.js`): bash allowed, webfetch and websearch denied, `edit` a blanket allow, outside directories denied except OpenCode's own scratch folders. |

### Routes

Every request carries `Authorization: Bearer <SESSION_SECRET>`, `/healthz` included. Without it, or
with a wrong one: `401 { "kind": "unauthorized" }`. Another path or method: `404 { "kind": "not_found" }`.

| Route | Result |
|---|---|
| `GET /healthz` | `200 { "state": "ready" }` once OpenCode is up, its event stream is connected and the OpenCode session exists. `503 { "state": "starting" }` before; `503 { "state": "failed" }` after a start failure or an OpenCode exit. |
| `POST /message` | Body `{ "text": "<non-empty string>" }`, at most 1 MiB. `202`, and the reply starts. While a reply runs: `409 { "kind": "busy" }`. Not ready: `503 { "kind": "starting" }` or `503 { "kind": "failed" }`. Bad body: `400 { "kind": "bad_request" }`. OpenCode refused the prompt: `502 { "kind": "prompt", "detail" }`, and no reply starts. |
| `POST /abort` | `204`. A running reply ends with `message_end.reason = "aborted"`; with no reply running it does nothing. The session stays. If OpenCode refuses the abort: `502 { "kind": "abort", "detail" }`. |
| `GET /events?from=<n>` | `200`, `application/x-ndjson`: one `{ "type", "data" }` object per line, in emit order, with no sequence number (the launcher adds it). The script keeps every event of the session in memory; `from` (default 0) is the index of the first event wanted, which is the count of lines the reader already holds. It writes `from..` and then live events, and holds the response open. `from` equal to the log size gives only live events; a larger `from` is `400 { "kind": "bad_request" }`. Several readers may read at once. |

### Events

| Type | Data | When |
|---|---|---|
| `text` | `{ delta }` | Live text of the reply. Reasoning text, user text and synthetic parts are never sent. |
| `tool_start` | `{ call_id, server, tool, input_summary }` | Once per OpenCode call id, when the call is first seen running or done. `server` is the configured name, or `null` for OpenCode's own tools; `tool` has no server prefix. |
| `tool_end` | `{ call_id, result_summary, bytes, duration_ms, ok }` | Once per call id, when the call completes (`ok: true`) or fails (`ok: false`). |
| `message_end` | `{ usage: { input_tokens, output_tokens, cache_read_input_tokens, cache_creation_input_tokens, cost_usd }, reason }` | Once per accepted message. `usage` sums every model step of the reply. |
| `error` | `{ kind, detail }` | See the kinds below. |
| `closed` | `{ reason: "failed" }` | Only after a start failure or an OpenCode exit. `idle` and `stopped` are the launcher's. |

`input_summary` is the scrubbed tool input as JSON and `result_summary` the scrubbed output (or the
error), each cut to 2000 characters; `bytes` is the size of the whole output before the cut.

`reason` is `limit` when the script cut the reply at its tool cap, else `aborted` when an abort was
asked during the reply, else `limit` when the reply used `steps` model steps or more, else `done`.
OpenCode 1.18.30 does not enforce `steps`: on the step that reaches it, it tells the model that
tools are disabled and to answer in text, but it still offers the tools. So the script counts the
reply's tool calls itself, and when a call starts that takes the count past
`max_tool_calls_per_message`, it aborts the reply in OpenCode; that call ends with `tool_end`
`ok: false` (or its result, if it finished first) and the reply with `limit`. A model that follows
OpenCode's instruction ends on the last step in text, also with `limit`.

| `error.kind` | Cause | Then |
|---|---|---|
| `start` | A bad `session.json`, a dossier path that escapes, or the relay, OpenCode or the OpenCode session did not start. | `closed { failed }`; open `/events` responses end; exit 1 after 5 s. |
| `agent` | An OpenCode `session.error` during a reply, other than an abort. `detail` is `<name>: <message>`, scrubbed and cut to 500 characters. | The reply still ends with one `message_end`, so its spend is booked once. |
| `opencode_exit` | `opencode serve` exited after the session was ready. `detail` is `exit code <n>` or `signal <s>`. | `closed { failed }`; open `/events` responses end; exit 1 after 5 s. |

SIGTERM or SIGINT stops OpenCode (awaited), the relay and the HTTP server, and exits 0 with no event.

### How a reply ends

The script prompts with `prompt_async` and reads OpenCode's own event stream (`GET /event`,
which sends `server.heartbeat` about every 10 s). Three facts of the 1.18.30 binary shape the rules:

- After a reply ends, OpenCode sends the reply's user message again, and after an abort or a
  provider error it sends a second idle pair. So a reply counts an idle only after it has seen its
  own user message, the first user message whose id is new.
- When the context fills, OpenCode compacts the session in the middle of a reply: it adds a user
  message with a `compaction` part, an assistant message with `summary: true` that answers it, and
  a user message that continues, which the later steps answer. The script takes no second message
  while a reply runs, so a user message that is new during a reply is OpenCode's own, and the
  assistant messages that answer it belong to the reply: their text and tools are sent and their
  usage is in `message_end`. The summary's text is not sent, and the summary is not a step.
- After an abort or a provider error, OpenCode reports idle before it settles the running tool and
  completes the assistant message. So an idle ends the reply only when every assistant message of
  the reply is complete; if one is still open 2 s after an idle, the reply ends anyway.
- The event stream has no replay. At the end of a reply the script reads the session's messages
  once and sends any text or tool event the stream did not deliver, so a frame lost in a reconnect
  loses nothing. After the stream reconnects during a reply, the script reads the session's
  messages if it has not yet seen the reply's user message, so a reply whose user message was lost
  is still found, then asks OpenCode for the session status and ends the reply if the session is
  idle.

## Launcher sessions

The launcher owns long-lived agent sessions: `POST /sessions` starts one sandbox that runs
`session.js` (see [Agent sessions](#agent-sessions-sessionjs)), and the routes below forward
messages to it and stream its events. A session is generic: the launcher knows a lane name, files,
a system prompt, a model and named MCP servers, and nothing about what the caller builds with them.
The registry lives in the launcher's memory, so after a launcher restart every session is unknown.

### Routes

Every route checks `Authorization: Bearer <SANDBOX_API_KEY>` before it reads a body; without it:
`401 { "error": "unauthorized" }`. Another method on a session path, or another path under
`/sessions`: `404 { "error": "not found" }`. An unknown session is
`404 { "error": "not found", "kind": "unknown_session" }` on every route; a caller reads it as
`closed`. A `502` is the launcher's failure envelope (`error`, `kind`, `timeout`, `error_class`,
`detail`, `script: "session.js"`, `elapsed_ms`, `timeout_ms`, and on E2B `sandbox_id` once the
sandbox exists); its `detail` is launcher-authored, never script output, a token or a prompt.

| Route | Success | Other answers |
|---|---|---|
| `POST /sessions` | `201 { session_id }` once the script reports ready | `429 { "error": "sandbox session capacity reached", "kind": "capacity" }` at `SANDBOX_SESSION_CAP`, before any sandbox is spent; `502 kind: "bad_request"` for a bad body; `502 kind: "orchestration"` (or `"timeout"` after 90 s) when the sandbox never became ready |
| `POST /sessions/{id}/messages` `{ text }` | `202`, no body | `409 { "error": "session busy", "kind": "busy" }` while a reply runs; `404`, also when the script cannot be reached or answers `503` (it is no longer ready), and the session then closes as `failed`; `502 bad_request` for a bad `text`; `502 orchestration` when the script refused the message with another status |
| `POST /sessions/{id}/abort` | `204`, also with no reply running; the reply ends with `message_end.reason = "aborted"` and the session stays | `404`, also when the script cannot be reached, and the session then closes as `failed`; `502 orchestration` when the script refused the abort |
| `GET /sessions/{id}/events?after=<seq>` | `200 text/event-stream` | `404`; `410 { "error": "events no longer held", "kind": "gone" }`; `502 bad_request` for an `after` that is not a non-negative integer or is past the last event |
| `DELETE /sessions/{id}` | `204` after the sandbox is stopped and removed; `204` again on a closed session | `404` |

`POST /sessions` takes `{ lane, system_prompt, files, model, model_cost?, provider, credential,
mcp_servers: [ { name, url, token } ], idle_timeout_ms, max_tool_calls_per_message }`. The launcher
checks the body in this order, each a `bad_request` with this `detail`:

| Check | `detail` |
|---|---|
| JSON, at most 8 MiB | `invalid json body` / `body too large` |
| `lane` non-empty string | `session.js: lane must be a non-empty string` |
| `credential`, as on the one-shot routes | `session.js: missing or invalid credential (<why>)` |
| `model` non-empty string | `session.js: model must be a non-empty string` |
| `idle_timeout_ms` positive integer | `session.js: idle_timeout_ms must be a positive integer` |
| `idle_timeout_ms` at most 24 hours | `session.js: idle_timeout_ms must be at most 86400000` |
| an `egress_secret` credential on docker | `session.js: an egress_secret credential needs SANDBOX_BACKEND=e2b (got docker)` |
| `system_prompt` non-empty string | `session.js: system_prompt must be a non-empty string` |
| `files`, if present, an object of strings | `session.js: files must map relative paths to strings` |
| `mcp_servers` an array | `session.js: mcp_servers must be an array` |
| each `mcp_servers` name matches `^[a-z][a-z0-9-]{0,31}$` | `session.js: each mcp_servers name must match ^[a-z][a-z0-9-]{0,31}$` |
| each name once | `session.js: mcp_servers name repeated: <name>` |
| on E2B, an `mcp_servers` entry whose `url` is missing or points at localhost (the one-shot routes' check) | `session.js: mcp_servers[<i>].url is missing or unreachable from an E2B microVM` |
| each entry has a string `url` and `token` | `session.js: mcp_servers <name> needs a url and a token` |
| `max_tool_calls_per_message` an integer from 1 to 1000 | `session.js: max_tool_calls_per_message must be an integer from 1 to 1000` |

These are the script's own checks of `session.json`, made here as well so a caller's mistake is a
`bad_request` (the backend's `RUN_FAILED`) that spends no sandbox, never a failed start that reads
as an outage. A dossier path that escapes is still found only by the script. `provider` is ignored, as on the one-shot routes: `credential.provider`
decides. `session.json` holds exactly `{ system_prompt, files, model, mcp_servers,
max_tool_calls_per_message }`, with the model qualified as on the one-shot routes; the credential
reaches the sandbox only through its env, with `model_cost` declared in `OPENCODE_CONFIG_CONTENT`.
Each session has its own `SESSION_SECRET` (32 random bytes as hex), sent with every call the
launcher makes to the script.

`POST /sessions/{id}/messages`: the body the launcher forwards to the script, `{ "text": ... }`,
must be at most 1 MiB, the script's own limit; else `session.js: text must be a non-empty string of
at most 1 MiB`.

### Events

Each event is one frame: `id: <seq>`, `event: <type>`, `data: <JSON>`, with no heartbeat and no
`retry:`. The types and their data are the script's (see [Events](#events) under Agent sessions),
passed through unchanged; a script line that is not JSON or names another type is dropped and
logged by type name only. `seq` starts at 1 and grows by one per event the launcher emits.

`after` (default 0) is the last sequence the caller holds: the response replays every held event
after it, then follows live events. The launcher holds the last `SANDBOX_SESSION_BUFFER` events of
each session; an `after` older than the oldest held event (`after + 1` below it) is `410`, so a
stale reader gets an error, never a silent gap. Exactly one `closed { reason }` ends every session and is
always the last event; the stream ends after it.

### Lifecycle

A session is `starting`, then `live`, then `closing`, then `closed`. Every way out of `live` closes
it the same way, with one `closed { reason }` event:

| Cause | `reason` |
|---|---|
| No message for the caller's `idle_timeout_ms`. The clock starts when the session is live and again at each `message_end`, and stops while a reply runs, so a reply longer than the idle time is never cut. | `idle` |
| `SANDBOX_SESSION_MAX_MS` after the `POST /sessions` arrived | `idle` (both clocks end a healthy session) |
| `DELETE /sessions/{id}` | `stopped` |
| The sandbox exits, the script sends `closed`, the script refuses connections on a message or an abort, or it answers a message with `503` | `failed` |

To close, the launcher stops both clocks and waits for the answer to a message it is forwarding,
if any, so a message the script accepts during the close has a reply that ends below; if a reply
runs and the sandbox is still up, it aborts the
reply and waits up to 5 s for its `message_end`, so the reply's spend reaches the caller; then it
sends `closed`, ends every open event stream, and stops the sandbox (Docker: stops and removes the
container and removes the work dir; E2B: kills the sandbox). When the sandbox exits by itself, the launcher first waits up to 1 s for the script's own last
lines (its `error` and `closed`), so they come before the launcher's. A reply that is open when the
sandbox dies has no `message_end`, and its spend is lost.

A closed session stays known for 5 minutes: `GET /events` replays its buffer and ends, `DELETE` is
`204`, a message or an abort is `404`. After that it is unknown.

A start that fails (the script reports `failed`, the sandbox exits, or 90 s pass) is a `502`; the
launcher logs the script's start events on its console, scrubbed and cut to 500 characters, then
stops the sandbox the same way. A failed E2B `create` leaves nothing to stop. No `closed` event is
sent, because no caller holds the id.

If the launcher's read of the script's events breaks, it reads again from the count of lines it
already holds (`GET /events?from=<n>`), so no event is lost or doubled.

### Env

| Env | Default | Meaning |
|---|---|---|
| `SANDBOX_SESSION_CAP` | `4` | Sessions at once (starting, live or closing), on every backend. Over it: `429`. |
| `SANDBOX_SESSION_BUFFER` | `5000` | Events kept per session for replay: enough for a whole reply's `text` deltas, about 1 MB per session. |
| `SANDBOX_SESSION_MAX_MS` | `7200000` | A session's whole life; also the Docker container's `tessary.sandbox.timeout-ms` label, and on E2B the sandbox's `timeoutMs` less 60 s. |
| `SANDBOX_SESSION_PORT` | `9400` | The port `session.js` listens on in the sandbox, passed to it as `SESSION_PORT`. |

Each must be a positive integer; anything else is `<NAME> must be a positive integer` on stderr
and exit 1 at boot. The idle timeout has no launcher default: the caller sends `idle_timeout_ms`.
E2B caps a sandbox's `timeoutMs` by plan (1 h on Hobby), so with a Hobby key every session start
fails at `create` until `SANDBOX_SESSION_MAX_MS` is at most `3540000`.

### Docker

A session container is built like a one-shot one (the same hardening, the one work-volume mount)
and runs `/usr/local/bin/node-sealed session.js /work/session.json` with the labels `tessary.sandbox=1`,
`tessary.sandbox.script=session.js`, `tessary.sandbox.timeout-ms=<SANDBOX_SESSION_MAX_MS>` and
`tessary.sandbox.session=<id>`. The launcher dials the script at the container's address on the
network it shares with the container, chosen by that network's name, never the first one listed.

### E2B

The launcher refuses to boot when `E2B_ANALYZER_TEMPLATE` names a release other than its own
`IMAGE_VERSION`: the lane and session scripts it runs ship in the template of the same release.
A local build (`dev`) or a floating tag (`latest`) is not checked.

A session sandbox is created from `E2B_ANALYZER_TEMPLATE` with `timeoutMs` set to
`SANDBOX_SESSION_MAX_MS` + 60 s and the metadata `tessary.sandbox.session=<id>`. The launcher
writes `session.json` to `/home/user/session.json` through the files API, then runs
`/usr/local/bin/node-sealed /home/user/session.js /home/user/session.json` in the background with no command timeout,
the credential env, `SESSION_SECRET` and `SESSION_PORT`. It dials the script at
`https://<port>-<sandbox id>.<domain>` (the SDK's `getHost`), or over `http://` under the SDK's own
`E2B_DEBUG=true`. When the command ends, the session closes as `failed`, and the launcher logs one
console line with the exit code and the scrubbed tail of its stderr. The command's event stream to
E2B stays open for the session's whole life, and the SDK's `wait()` also rejects when only that
stream breaks; so only a rejection with an exit code is an exit, and on any other the launcher
reattaches to the same pid with `commands.connect` (no timeout). A pid E2B no longer finds has
exited. Every close, and every failed
start after the `create`, kills the sandbox. There is no boot reaper: after a launcher restart a
session sandbox has no owner and lives until its E2B `timeoutMs`. Listing sandboxes at boot would
also kill the sessions of any other launcher on the same E2B key; the metadata is there for a human
to find them.

## Deploy

The launcher ships as its own image, built and pushed by
`.github/workflows/release.yml`'s `build-sandbox-runner` job from
`launcher/Dockerfile`, and runs as the `sandbox-runner` service in both compose files. The backend
reaches it by service DNS at `http://sandbox-runner:8080` and authenticates with `SANDBOX_API_KEY`.

The launcher starts every session as `/usr/local/bin/node-sealed`, which only an agent image or E2B
template built from the current recipes has; with an older one every session start fails. Ship the
launcher, the agent image and the template together. The template's `--verify` checks the binary.

The E2B backend's template is published by that same workflow, from the `E2B_API_KEY` repository
secret, in two jobs either side of the commit point:

| job | writes | undone by |
|---|---|---|
| `build-agent-template` | `<version>` and `recipe-<hash>` — and **only builds if the recipe changed** | `cleanup` |
| `verify-agent-template` | nothing; boots `tessary/tessary-agent-sandbox:<version>` and runs four checks through it | n/a |
| `finalize` | moves `latest` and `default` onto that build | nothing (this is the commit point) |

`recipe-<hash>` is a digest of the real build inputs — `template.ts`, the agent scripts
(`RECIPE_INPUTS` in `build.ts`), and the cpu/memory pair. A build already carrying
this release's hash gets the version tag assigned to it and no rebuild happens. Asking E2B which
recipes it already holds is self-correcting where a `git diff` against the previous tag is not: after
a release whose template build failed, the source is unchanged at the next attempt, so a diff would
skip the rebuild and stamp a version onto a template that never got the change.

`verify-agent-template` is the gate no docker build can stand in for. `template.ts`'s header names
two mistakes — a missing `bash` (this SDK hardcodes `cmd: "/bin/bash"`) and a `runCmd` that needs
`{ user: 'root' }` — that both produce a template which BUILDS CLEANLY and then fails on every run.
It runs on the dedup path too: "the recipe did not change" says nothing about whether the published
build still boots.

To build it by hand (a dev template, or a first build in a fresh E2B project):

```bash
cd sandbox-runner/agent-sandbox && pnpm install && E2B_API_KEY=<team key> pnpm exec tsx build.ts
```

That no-argument form is unchanged and publishes under the `default` tag. `--release=`, `--verify=`,
`--promote=` and `--rollback=` are the four verbs the workflow drives; every one of them is runnable
on a laptop with the same key, which is the point of them living in `build.ts` rather than inline in
a workflow step.

## Docker sandbox driver (`SANDBOX_BACKEND=docker`, the default)

The open-source, Docker-by-default agentic backend: each of `/rca` and `/triage` runs in a
**fresh, hardened sibling container**, spawned from the
published `AGENT_IMAGE` over the mounted Docker Engine API socket, removed on completion. No
`E2B_API_KEY`, no Tessary-hosted microVM, no Tessary-owned IAM user assumed — the
agentic lanes run on **the self-hoster's own model keys** via `AGENT_PROVIDER` (see above) or the
SigV4 pair `docker-compose.yml` already carries as a pure opt-in default.

- **Isolation:** `--cap-drop=ALL`, `--security-opt=no-new-privileges`, memory/CPU/pids limits
  (`SANDBOX_DOCKER_MEMORY_MB`/`SANDBOX_DOCKER_CPUS`/`SANDBOX_DOCKER_PIDS_LIMIT`), never host
  networking, egress left ON (the agent needs it for the repo clone and the model-provider call —
  never `--network none`), and exactly one bind mount: the per-request work dir.
- **Network placement (`SANDBOX_NETWORK_ISOLATION`, default OFF):** by default a sibling joins the
  network the launcher itself is on, read back from the daemon rather than named in config. That is
  what lets the agent reach the backend's MCP door at `http://backend:8080` — it reads every trace
  it reasons about through that door, and an earlier unconditional cut-off left it no route
  there except the public origin, which a localhost `docker compose up` does not have. Set
  `SANDBOX_NETWORK_ISOLATION=1` to restore that cut-off: siblings then run on a dedicated bridge
  (`DOCKER_SANDBOX_NETWORK`, never the `tessary` service network), and such an install must set
  `SITE_DOMAIN` and point `TESSARY_RCA_AGENTIC_MCP_BASE_URL` at that public origin, because there is
  no longer an internal route. The trade is explicit both ways: isolation off means a sibling can
  address `postgres` and the other internal services directly, not only the MCP port. A session
  container is placed the same way, and the launcher dials it there: on its own network when
  isolation is off; with isolation on, the launcher connects itself to `DOCKER_SANDBOX_NETWORK` at
  boot (`POST /networks/{name}/connect` with its own container id from `HOSTNAME`, skipped when it
  is already on it). A failed join is a warning, and session starts then fail one at a time.
- **Concurrency:** `SANDBOX_DOCKER_CONCURRENCY` (default `1`) — an in-process semaphore
  around the one container-spawn call site of the one-shot routes. A session container never takes
  a slot of it, because one open session would then freeze every one-shot run;
  `SANDBOX_SESSION_CAP` bounds sessions instead.
- **Teardown:** every container is removed explicitly when its run ends (not just `--rm`), and a
  startup reconciliation pass reaps any `tessary.sandbox=1`-labeled container older than a few
  request timeouts — the case a normal `finally` can't cover: the launcher itself crashing or
  being redeployed mid-run. A session container is removed when its session closes, or when its
  start fails; the startup pass reaps every container labeled `tessary.sandbox.session` whatever
  its age, because the session registry lives in memory and no caller can reach the session after
  a restart. Each container carries `tessary.sandbox.work`, the name of its work dir under
  `LAUNCHER_WORK_DIR`; the startup pass removes the work dir of every container it reaps, and every
  other `run-*` or `session-*` dir last changed before this boot that no container it keeps uses
  (a dossier, the saved MCP results, or a `session.json` with its tokens that the launcher wrote
  before it died).
- **The Docker-outside-of-Docker work volume:** the launcher runs AS a container itself
  (`docker-compose.yml`'s `sandbox-runner` service), so a sibling container's mount source has to
  be something the HOST daemon can resolve — the launcher's own in-container temp dir means
  nothing to it. The answer is to name no path at all: `docker-compose.yml` mounts ONE NAMED
  VOLUME into the launcher at a fixed path (`LAUNCHER_WORK_DIR`) and passes its name
  (`SANDBOX_WORK_VOLUME`, default `tessary-sandbox-work`); every sibling mounts the same volume by
  name, with the Engine API's `VolumeOptions.Subpath` naming the per-request subdirectory. A
  volume name means the same thing to the daemon whoever asks, so nothing here depends on the
  operator's filesystem — which is what lets the published one-command install work at all
  (`setup.md`): it has no repository directory to bind. The docker backend fails at boot if
  `SANDBOX_WORK_VOLUME` is unset, rather than producing agent runs that silently see an empty
  `/work`. Docker creates a fresh volume `root:root` mode `0755` — unwritable by the non-root
  `node` user (uid 1000) `sandbox-runner`'s own `mkdtempSync` calls run as — so
  `docker-compose.yml`'s `sandbox-runner-work-init` one-shot service runs first (root, `busybox`)
  and `chown`s it to `1000:1000`; `sandbox-runner` `depends_on` it with
  `condition: service_completed_successfully`.
- **Agent image pull:** `POST /containers/create` over the raw Engine API does **not** auto-pull
  the way `docker run`/the CLI does, and `AGENT_IMAGE` is only ever an env var on this service,
  never a compose `image:` — so `docker compose pull` never fetches it either. `server.js`'s
  `ensureAgentImage()` checks `GET /images/{ref}/json` before every `/containers/create` and, on
  a miss, pulls via `POST /images/create` first (cached after the first success per process) — a
  self-hoster who never manually `docker pull`-ed `AGENT_IMAGE` still gets a working first
  request instead of a `502`.
- **Socket permissions:** `USER node` (non-root, `sandbox-runner/launcher/Dockerfile`) is not a
  member of whatever group owns `/var/run/docker.sock` on the host by default.
  `docker-compose.yml`'s `group_add: ["${DOCKER_SOCK_GID:-999}"]` grants that one supplementary
  group instead of running the container as root; if the launcher logs `EACCES
  /var/run/docker.sock`, set `DOCKER_SOCK_GID` to your host's actual `docker` group GID
  (`getent group docker`).
- **Agent image:** `sandbox-runner/agent-sandbox/Dockerfile` — a plain OCI build mirroring
  `template.ts`'s E2B recipe 1:1 (same base install steps, same pinned `opencode-ai` version),
  built from the **repo root** as context (its COPY paths carry the full `sandbox-runner/agent-sandbox/` prefix).
  Published (as `agent-sandbox-<version>`) by `.github/workflows/release.yml` to Docker Hub
  (primary) and GHCR (mirror), alongside backend/frontend/sandbox-runner, when a human dispatches
  it — every workflow trigger in this repo is currently `workflow_dispatch`-only.

### Automated coverage

`sandbox-runner/launcher/test/docker-backend.test.js` (`node --test`, zero new dependency, run via
`task sandbox-runner:check` / `scripts/check-sandbox-runner-launcher.sh`, so it runs in CI on every
push, not just by hand) runs the real `server.js` as a child process against a **fake** Docker
daemon (a plain `node:http` server on a temp unix socket) and asserts, across four cases: the
container-create hardening flags; that `SANDBOX_DOCKER_CONCURRENCY=1` actually serializes two
concurrent runs; that a missing `AGENT_IMAGE` gets pulled via `/images/create` before create is
attempted; and — the specific gap an earlier review round flagged, since every other case here
pins `SANDBOX_BACKEND=docker` explicitly — that the docker path is what actually runs when
`SANDBOX_BACKEND` is **left unset**, matching a genuinely fresh self-host's `.env`. A fake daemon
proves the launcher asked Docker for the right thing; it cannot prove a spawned container
actually runs, or exercise real bind-mount permissions/image-registry behavior — that's what the
manual proof below is for. `docker-sessions.test.js` drives the launcher sessions the same way: its
fake daemon starts a fake `session.js` (`test/fixtures/fake-session`) for each session container,
and the tests cover the routes, the numbered events and their replay, busy, abort, idle, the max
lifetime, the cap, the buffer, a dead or unreachable sandbox, the network join and the session
container's create body. `docker-failures.test.js` covers the boot reaper's session rule and its
removal of the work dirs a dead launcher left.
`e2b-sessions.test.js` runs the same session tests on the e2b backend: the fake E2B
(`test/fixtures/fake-e2b`) relays each SDK call to the test, which runs a fake `session.js` per
sandbox, and the tests also cover the create, the file, the command, the kill on every way out and
the egress rule.

### Manual end-to-end proof (2026-09-01, against a real Docker daemon)

Reproduced with a minimal stub agent image (a lane script that echoes its input) standing in for
the published `AGENT_IMAGE`, both as a bare host process and as a genuinely containerized launcher
(matching the compose deployment shape) — real Docker (`docker info`), no E2B key set anywhere:

1. **Before the fix (proves the "no E2B key ⇒ fails today" case):** with `SANDBOX_BACKEND`
   unset and `E2B_API_KEY` unset, `/triage` 502s (`sandbox create failed`) — the previous default
   (`e2b`) has no fallback.
2. **After, bare host process:** `SANDBOX_BACKEND=docker`, real `AGENT_IMAGE`, `E2B_API_KEY`
   unset → `/triage` returns `200 {"raw":"ok:..."}`. `docker ps` before/after shows the container
   created then removed; `docker inspect` on an in-flight run confirmed
   `CapDrop=[ALL] SecurityOpt=[no-new-privileges] Memory=2147483648 NanoCpus=2000000000
   PidsLimit=256 NetworkMode=tessary-sandbox AutoRemove=false` and exactly one `Mounts` entry.
   `SANDBOX_DOCKER_CONCURRENCY=1` with two concurrent 2s-sleeping requests took ~5s total
   (serialized), not ~2s. A 2s-timeout against a 10s script returned the standard timeout 502
   envelope in ~2s, with the container removed.
3. **After, launcher itself containerized** (the real deployment shape — this is what step 2 does
   NOT prove, and is where the Docker-outside-of-Docker path bug above was actually found): built
   `sandbox-runner/launcher/Dockerfile`, ran it with the socket mounted plus the
   `LAUNCHER_WORK_DIR`/`SANDBOX_WORK_VOLUME` pair wired exactly as `docker-compose.yml` wires
   them → `/triage` still returns `200`. (`--user root` was needed for this specific run only
   because this host's Docker Desktop socket has no group-write bit at all — see the socket
   permissions note above; `group_add` is the documented mechanism for a real Linux host, whose
   `docker.sock` is normally group-writable.)
4. **(2026-09-16) The `local` backend this point once described — a host-process escape hatch
   with no container or VM isolation, `task dev:local` — was removed once `task dev` started
   building AGENT_IMAGE from the checkout: an isolated Docker sandbox is now no slower to get
   running than the unisolated one was, so there was no remaining reason to keep it.
