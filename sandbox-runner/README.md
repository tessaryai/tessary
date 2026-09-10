# sandbox-runner

Runs the platform's own **agent** in an isolated sandbox: agentic RCA (`/rca`) and Layer-2 triage
(`/triage`). The Java backend has no E2B SDK and no Docker client, so it calls this Node sidecar over
HTTP and the sidecar owns the sandbox lifecycle — one request, one fresh sandbox, torn down after.

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

- `launcher/` — the HTTP sidecar (`server.js`). Owns the three backends below.
- `agent-sandbox/` — the agent sandbox: `rca.js`, `triage.js`, the shared `agent-stream.js`
  OpenCode runner, and TWO INDEPENDENTLY MAINTAINED RECIPES for one runtime — `template.ts` (the E2B
  template, published by `build.ts`) and `Dockerfile` (the published agent image the Docker backend
  spawns). Change one, change the other.

## Security model

The agent this service runs is OURS, not a customer's code, and that is the one fact the whole model
now rests on. Until grading was removed it also executed untrusted, LLM-generated grader JavaScript, and most of
what follows was written for that; the boundaries described are unchanged, but what they contain is
narrower.

- **Isolation:** the E2B backend's microVM is a kernel-level boundary. The **Docker** backend gets
  an equivalent process-level one — a fresh, hardened sibling container per request (`--cap-drop=ALL`,
  `no-new-privileges`, its own bridge network). The `local` backend has **no container or VM
  isolation at all**: the agent runs as a plain host child process. It is a developer convenience
  (`task dev:local`), not an isolation option.
- **Credentials and egress:** the agent NEEDS both — a repo clone and a model-provider call — so
  AGENT_POSTURE hands it the provider credentials `agentEnvs()` derives and puts the container on the
  sandbox bridge network. `E2B_API_KEY` stays in the launcher and is never sent to the backend or
  into a sandbox.
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

## Deploy

The launcher ships as its own image, built and pushed by
`.github/workflows/deploy-aws-production.yml`'s `build-sandbox-runner` job from
`launcher/Dockerfile`, and runs as the `sandbox-runner` service in both compose files. The backend
reaches it by service DNS at `http://sandbox-runner:8080` and authenticates with `SANDBOX_API_KEY`.

The E2B backend's template is published by that same workflow, from the `E2B_API_KEY` repository
secret, in two jobs either side of the commit point:

| job | writes | undone by |
|---|---|---|
| `build-agent-template` | `<version>` and `recipe-<hash>` — and **only builds if the recipe changed** | `cleanup` |
| `verify-agent-template` | nothing; boots `tessary/tessary-agent-sandbox:<version>` and runs seven checks through it | n/a |
| `finalize` | moves `latest` and `default` onto that build | nothing (this is the commit point) |

`recipe-<hash>` is a digest of the real build inputs — `template.ts`, the three agent scripts, the
validator wrapper, the five `contract/` files, and the cpu/memory pair. A build already carrying
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

## Local agent backend (`task dev:local`)

Both launcher paths — RCA (`/rca`) and Layer-2 triage (`/triage`) — drive the **`opencode`** CLI. By
default the launcher runs each in a fresh **E2B microVM** (template
`tessary/tessary-agent-sandbox`, namespaced because E2B scopes template names to the project that
built them), which needs `E2B_API_KEY` and Bedrock credentials. For local development you can instead run those scripts on the **host**,
against a locally installed `opencode`, with no E2B at all:

- **`SANDBOX_BACKEND`** — `docker` (default — see below) | `e2b` (opt-in) | `local`.
  In `local`, the agentic paths run the `agent-sandbox/*.js` scripts on the host via
  `node` (no `E2B_API_KEY`, no `e2b` package install, no container isolation at all).
  The launcher logs the active backend at startup:
  `listening on :8080 (backend=local)` / `(backend=e2b, template=…)` /
  `(backend=docker, image=…, concurrency=…)`.
- **Model** — no local override, and nothing to translate. Both backends get the same
  model and the same credentials, so a local run cannot silently exercise a different
  provider than production. The backend still configures a bare Bedrock id; the launcher's
  `toProviderModel()` qualifies it with the OpenCode provider that serves it
  (`global.anthropic.claude-sonnet-4-6` → `amazon-bedrock/…`, `openai.gpt-5.6-terra` →
  `bedrock-mantle-gpt/…`). An id that already names a provider passes through.
- **Credentials** — `agentEnvs()` forwards **SigV4 only** (`AWS_REGION`,
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, plus `AWS_SESSION_TOKEN` when set) along
  with the generated OpenCode provider config in `OPENCODE_CONFIG_CONTENT`. A Bedrock
  bearer token is not a substitute here — on this path the launcher never reads one. For a
  dev machine with no AWS access key / secret, `AGENT_PROVIDER` selects a dev-only escape
  hatch instead: `anthropic` (+ `ANTHROPIC_API_KEY`) runs the agent on the first-party
  Anthropic API, serving the Claude line only, and `bedrock-api-key`
  (+ `AWS_BEARER_TOKEN_BEDROCK`) runs bedrock-runtime over a bearer token, without the
  mantle GPT models. Unset (in every compose file) is always SigV4 Bedrock. `AGENT_PROVIDER`
  is also how a self-hoster's own model keys plug into the Docker backend below — it is
  not a dev-only escape hatch any more. Setup detail lives in
  [`devdocs/guides/local-dev.md` § Agent credentials without AWS keys](../devdocs/guides/local-dev.md#agent-credentials-without-aws-keys)
  and `.env.example`.
- **`WORK_DIR`** — the filesystem root the analyzer scripts use (`$WORK_DIR/repo`,
  `$WORK_DIR/candidate.js`, `$WORK_DIR/units`). Defaults to `/home/user` so the E2B
  template behavior is unchanged; in local mode the launcher sets it per-request to a
  fresh `os.tmpdir()` temp dir, which it removes after the run.

**Recipe** — from the repo root, with `opencode` installed and `git` on PATH:

```bash
task dev:local
```

This runs the normal Docker stack **plus** a 5th tmux window (`launcher`) running the
host launcher in local mode, and auto-points the backend container at it
(`TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL=http://host.docker.internal:8080`, key `devkey`
unless `TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY` is set). It installs the host analyzer
deps (`sandbox-runner/agent-sandbox/node_modules`) on first run. Plain `task dev` is unchanged (E2B path, no launcher window).

> **Note (Linux):** the backend container reaches the host launcher via
> `host.docker.internal`, which resolves out-of-the-box on Docker Desktop (macOS/Windows) —
> the platform the documented `task dev:local` workflow targets. On a **native Linux** Docker
> engine that hostname may not resolve; add an `extra_hosts: ["host.docker.internal:host-gateway"]`
> mapping to the backend service (or point `TESSARY_OBSERVER_AGENTIC_LAUNCHER_URL` at the host's
> IP) for it to work there.

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
  address `postgres` and the other internal services directly, not only the MCP port.
- **Concurrency:** `SANDBOX_DOCKER_CONCURRENCY` (default `1`) — an in-process semaphore
  around the one container-spawn call site.
- **Teardown:** every container is removed explicitly when its run ends (not just `--rm`), and a
  startup reconciliation pass reaps any `tessary.sandbox=1`-labeled container older than a few
  request timeouts — the case a normal `finally` can't cover: the launcher itself crashing or
  being redeployed mid-run.
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
  built from the **repo root** as context (it needs `contract/` alongside its own directory).
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
manual proof below is for. `check-open-boot.sh` (the open-edition boot gate's dev leg) does not
probe this path — it's HTTP-only against backend/frontend/caddy, and docker-compose.dev.yml's
`sandbox-runner` service is profile-gated off there. The gate's second leg,
`check-open-boot-selfhost.sh`, boots `docker-compose.yml` with `sandbox-runner` started
unconditionally and `SANDBOX_BACKEND` left unset, and asserts the launcher is RUNNING on the
docker default — the boot half of this gap. It still does not drive a sandbox run through it;
that remains a larger, separately-scoped change. (docker-compose.dev.yml no longer defaults the
service to `e2b` either — its passthrough is empty, matching docker-compose.yml.)

### Manual end-to-end proof (2026-09-01, against a real Docker daemon)

Reproduced with a minimal stub agent image (a `triage.js` that echoes its input) standing in for
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
4. **`task dev:local` unaffected:** that path's `runScriptLocally` and the `local` backend
   selection are untouched by this issue — `SANDBOX_BACKEND=local` still runs the analyzer
   scripts directly on the host process, no Docker involved.
