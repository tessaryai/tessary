# Running it locally

Two paths: bare-metal (fastest iteration, requires host Java/Node/Caddy) or Docker (one runtime, isolated).

## Bare metal

Node packages use **pnpm 11** (requires **Node ≥ 22.13**). Each `package.json` pins the
version via `packageManager`; enable corepack once so that pin is what gets used:

```bash
corepack enable
task node:install                                        # every Node package; or `task frontend:install`

# Three terminals (backend needs Postgres 16 + pgvector via TESSARY_JDBC_URL — e.g.
# `docker compose up -d postgres` for just Postgres — `task dev:up` brings up the whole
# stack instead, including a Caddy container that collides with `task caddy` below — or
# a local pgvector/pgvector:pg16 container):
task backend            # :8080  (connects to TESSARY_JDBC_URL)
task frontend           # :5173 (Vite, proxies /api → :8080 too)
task caddy              # :8000 (canonical entry)
```

`task node:install` / `frontend:install` self-heal a pre-pnpm checkout (clears npm's flat
`node_modules` when the `.pnpm/` marker is missing).

Auth is bypassed locally because `TESSARY_AUTH_DISABLED=true` is set for you — in `docker-compose.dev.yml` for the Docker stack, and in the `backend` task itself for the bare-metal one. Unsetting WorkOS is no longer enough on its own: without the flag every `/api/**` call answers 401, deliberately, so that an unconfigured deployment refuses rather than opens. With the flag, sign-in is skipped and every request is anonymous (no `TenantContext` populated).

To exercise the auth flow end-to-end against WorkOS staging, set `WORKOS_API_KEY`, `WORKOS_CLIENT_ID`, `TESSARY_AUTH_COOKIE_PASSWORD`, `WORKOS_REDIRECT_URI`, and `SPRING_PROFILES_ACTIVE=production` before `task backend` — **and also edit the `backend` task's hardcoded `TESSARY_AUTH_DISABLED: "true"` to `"false"`, or unset it.** (Corrected 2026-09-01: this used to say a configured provider always wins over the flag, so unsetting it wasn't needed — that precedence was retired. The flag is authoritative on its own now; a configured WorkOS provider no longer overrides it.)

## Docker (dev)

Dev images: JVM-mode backend with Spring Boot devtools, Vite dev server with HMR, Caddy reverse proxy. Source is bind-mounted; node_modules and the Maven repo live in named volumes so rebuilds don't refetch.

```bash
task dev                                                   # → tmux session
task dev:slim                                              # same, minus classify + compile (was `dev:2gb`, still aliased)

task dev:up                                                # no tmux: same containers, detached, logs via `task dev:logs`
task dev:up:slim                                           # = TESSARY_SKIP_CLASSIFY=1 task dev:up
```

### Slim mode — what runs

`dev:slim` (and `TESSARY_SKIP_CLASSIFY=1` in front of any dev task) starts everything except the two encoder services. The list is derived from compose itself in `scripts/lib/dev-services.sh`, so a new service joins slim mode automatically and both entry points agree by construction.

| Service | Slim | Notes |
|---|---|---|
| `postgres` | ✅ | Postgres 16 + pgvector, on `localhost:5433` for `psql` |
| `backend` | ✅ | Spring Boot + devtools, JDWP on `:5005` |
| `frontend` | ✅ | Vite dev server, HMR over the bind mount |
| `caddy` | ✅ | Reverse proxy on `:8000` — the entry point, unchanged |
| `alloy` | ❌ | Opt-in: behind the `observability` Compose profile, off by default. `COMPOSE_PROFILES=observability` to forward `gen_ai` judge spans to Langfuse |
| `classify` | ❌ | Encoder service. Build downloads gated HF weights (`BAKE_EMBEDDERS` bakes ~1.7 GB into the dev image); container capped at 8 GB |
| `compile` | ❌ | SOP-conformance fitter. `depends_on: classify`, and every fit calls its `/embed` — without classify it can only dead-letter, so it goes too |

So the product is whole in slim mode: the browser, every page, ingestion, triage, RCA. The one thing dormant is anything encoder-backed — classifier heads and SOP-conformance fits — which stays dormant until a full `task dev`.

The classifier-training tooling (Argilla, MLflow) is behind the `classifiers` compose profile and never starts in either mode; `task classifiers:up` brings it up alongside.

Where tmux isn't available (CI, an agent, a plain `ssh`), `task dev:up` is the way in. Without HuggingFace credentials the classify build fails on its gated encoder-weight download and leaves you with zero containers, so reach for `task dev:up:slim` there.

The tmux session opens four tabbed windows: `[0] shell` (cheat-sheet + common commands; you land here), `[1] backend`, `[2] frontend`, `[3] caddy`. Session-local no-prefix bindings (defined in `.tmux.conf` at the repo root) jump with bare `0`/`1`/`2`/`3` and cycle with `Tab`/`Shift+Tab`; the standard `C-b 0`/`1`/`2`/`3` and `C-b n`/`p` still work as fallbacks.

Hot-reload behaviour:

- **Frontend** — Vite HMR is fully automatic over the bind mount.
- **Backend** — Java changes don't recompile automatically. From the tmux shell: `task dev:reload-backend` (runs `mvn compile` inside the container; devtools restarts on classpath change). Full restart: `task rb`.

Quick restart shortcuts (run from the tmux shell window; they target the matching log window, kill the tail, force-recreate the container, and reattach the tail). Inside the session you can also press the `r` chord — `r` then `f`/`b`/`c` — as a no-prefix equivalent:

- `task rb` (or `r` then `b`) — restart backend (alias `restart:backend`)
- `task rf` (or `r` then `f`) — restart frontend (alias `restart:frontend`)
- `task rc` (or `r` then `c`) — restart Caddy (alias `restart:caddy`)
- `task rb:full` / `task rf:full` — full image rebuild (use when `pom.xml` / `package.json` / Dockerfile changed)
- `task logs -- backend` — tail logs for a single service

After the npm→pnpm migration, if a leftover `frontend-node-modules` volume still has npm's
flat tree, `task rf` alone will not clear it (the Dockerfile.dev CMD does self-heal on boot,
but a stuck volume can also be wiped with `docker volume rm <project>_frontend-node-modules`
then `task rf:full`).

### Agent credentials — org-scoped, no dev overrides any more

**`AGENT_PROVIDER` and every deployment-env-var credential path the sandboxed
agent lanes (RCA, Layer-2 triage) used to read were removed** — there is no dev-only override any more. Every
RCA/TRIAGE run now resolves the org's own `ProviderCredential` (Settings → Providers) for whichever
provider that project's lane is pointed at, decrypts it in the backend, and injects it into the
launcher request directly (`AgenticCredentialResolver`) — the launcher itself reads no provider
secret from its own process env at all. To develop against a non-Bedrock provider locally, add an
org credential for it (Gemini/GLM/Grok/Custom are all reachable this way, same as production) and
point the RCA or TRIAGE lane at a model from that provider under Settings → Models — there is no
separate dev-only knob, because the real per-request path already covers this case. Ollama (the
platform's one credential-free provider) was removed, so every provider, dev included,
needs a configured key.

Stop everything:

```bash
task dev:stop      # kills tmux session + `docker compose down`
```

Inside the session, `C-e` runs `task dev:stop` directly.

## Docker (prod)

Multi-stage build: Spring Boot JVM layered bootJar in `backend/Dockerfile` (fast build on a Temurin JRE 25 runtime, nonroot; dependency layers cache across rebuilds), Vite static + Caddy in `frontend/Dockerfile`.

```bash
task prod:build    # builds both images
task prod:up       # starts postgres + backend + frontend (alloy is not in the prod compose)
# open http://localhost:8000
task prod:down
```

Alloy (OTLP/Langfuse/Grafana Cloud export) is absent from the open prod compose — no Grafana Cloud or Langfuse
account required to boot the open edition.

## Without Docker, prod-style

`task backend:build` → produces the executable bootJar at `backend/app/target/app-*-exec.jar`, run with `java -jar`; the plain `app-*.jar` beside it is the module jar the paid assembly depends on. `task frontend:build` → produces `frontend/dist/`. Serve the dist yourself with any static file server / reverse proxy that also proxies `/api/*`, `/auth/*`, `/mcp`, `/webhooks/*`, `/v1/traces` to the backend jar — see `frontend/caddy/base.caddy` + `frontend/caddy/render.sh` for the routes the shipped production image renders (there is no standalone open-edition Caddyfile.prod to reuse directly) — and run the jar directly.
