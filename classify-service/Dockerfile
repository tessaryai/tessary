# Encoder classification service — multi-stage so the ~1.9 GB model bake (3 classify heads;
# ~3.6 GB with BAKE_EMBEDDERS=1, which adds the conformance encoder /embed serves) is
# rebuilt ONLY when its actual inputs change.
#
#   models stage inputs: package.json/pnpm-lock.yaml/pnpm-workspace.yaml, models.json, embedders.json, download.js — nothing else.
#     Editing server.js, classify.js scoring, warmup.js, or the README reuses the cached
#     weights layer (locally via BuildKit, in CI via the ECR registry cache).
#   runtime stage: deps + code + COPY --from of the baked weights, then an OFFLINE
#     validation of every head under runtime-identical loading (allowRemoteModels=false),
#     so a bad pin or missing file fails the build, never production.
#
# Build for the deployment platform explicitly; production runs on ARM64 Fargate (Graviton).
#
# Of the 3 heads (models.json), frustration + attribution live in PRIVATE HF repos under
# tessaryai/ ("gated": true there) and need an HF_TOKEN with read access; groundedness
# (Xenova/bart-large-mnli) is public and always bakes. A build that supplies the token
# gets all 3 heads, exactly as before:
#   HF_TOKEN=<token> docker buildx build --secret id=hf_token,env=HF_TOKEN \
#     --platform linux/arm64 -t <ecr>/evals-classify:<sha> .
#
# Omitting --secret id=hf_token entirely is a SUPPORTED keyless build (#877, for the
# open-core edition): the mount below has no `required=true`, so it already tolerates a
# missing secret and yields HF_TOKEN="". download.js then skips the two gated heads
# instead of failing, and classify.js reports them as a clean 400 "unavailable" at
# /classify rather than crash-looping the container's boot-time pre-warm — no Dockerfile
# change was needed for this, only the gating logic in download.js/classify.js/warmup.js.
# An explicit token that is present but invalid/expired is NOT tolerated the same way:
# only the token's absence skips a gated head, so a bad credential still fails the build
# loud, as it always has.

# ---------------------------------------------------------------------------
# Stage 1: model bake — downloads pinned revisions, assembles localModelPath layout
# ---------------------------------------------------------------------------
FROM node:22-slim AS models

WORKDIR /app
# pnpm by explicit global install rather than corepack, so image builds never depend on corepack's
# registry signature check. PNPM_VERSION is supplied by the build orchestrator (the reusable ECS
# deploy passes the `PNPM_VERSION` Actions variable; local builds get the Taskfile value) — no
# default, so the version has a single source. pnpm-workspace.yaml is required, not cosmetic: pnpm
# 11 keeps settings there, and it carries the allowBuilds entries for onnxruntime-node/sharp/
# protobufjs. Without it the install exits 0 with no native ONNX Runtime and it only fails at load.
ARG PNPM_VERSION
RUN npm i -g pnpm@${PNPM_VERSION}
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --prod

ENV HF_CACHE_DIR=/models
# Bake embedders.json's conformance encoder into the image? OFF by default, and the default
# is what production builds: the encoder is ~1.7 GB of fp32 weights, and with it resident the
# service settles at ~4.5 GiB and is OOM-killed during pre-warm on the 1 vCPU / 4 GB Fargate
# task — which would take the /classify heads down with it. Off, this stage produces exactly
# the weights layer it did before the encoder was registered, and /embed answers 400 for the
# checkpoint (embed.js). Dev compose builds with BAKE_EMBEDDERS=1; production may only do so
# AFTER the ECS task definition is raised to 8 GB in the infra repo. See the README,
# "Enabling the conformance encoder in production".
ARG BAKE_EMBEDDERS=0
COPY models.json embedders.json download.js ./
# The frustration + groundedness heads' weights live in PRIVATE HF repos under tessaryai/
# — download.js needs HF_TOKEN to pull them. A BuildKit secret mount (not ARG/ENV) so the token
# never lands in an image layer or `docker history`. transformers.js reads HF_TOKEN from the
# process env on its own (see the @huggingface/transformers dist source; under pnpm it resolves via
# node_modules/.pnpm rather than a flat path) — no download.js
# code change needed, just get the env var into this RUN's process.
RUN --mount=type=secret,id=hf_token \
    mkdir -p /models && \
    BAKE_EMBEDDERS="${BAKE_EMBEDDERS}" \
    HF_TOKEN="$(cat /run/secrets/hf_token 2>/dev/null || true)" node download.js

# ---------------------------------------------------------------------------
# Stage 2: runtime — code + baked weights + offline validation
# ---------------------------------------------------------------------------
FROM node:22-slim

WORKDIR /app
ARG PNPM_VERSION
RUN npm i -g pnpm@${PNPM_VERSION}
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --prod

ENV HF_CACHE_DIR=/models
# --chown at COPY time: a separate `RUN chown -R` would duplicate the whole bake into a new layer.
COPY --from=models --chown=node:node /models /models

COPY models.json embedders.json classify.js embed.js warmup.js ./
# Offline validation: warmup loads every head through classify() — and every BAKED embed
# checkpoint through embed() — exactly as runtime does (localModelPath +
# allowRemoteModels=false) — no network involved, seconds not minutes. The same
# BAKE_EMBEDDERS value the models stage got, so a build that ASKED for the encoder and
# didn't get it fails here rather than shipping an image that 400s.
ARG BAKE_EMBEDDERS=0
RUN BAKE_EMBEDDERS="${BAKE_EMBEDDERS}" node warmup.js

# Last: HTTP layer only — a server.js/queue.js edit re-runs nothing above this line.
# queue.js MUST be copied alongside server.js: server.js require()s './queue' at load,
# so an image missing it crash-loops on boot (MODULE_NOT_FOUND) before binding a port.
COPY server.js queue.js ./
# Build-time guard: resolve server.js's first-party require graph in the runtime image
# (no HTTP listen, no model load beyond classify.js's cheap manifest check). A missing
# first-party file — the exact bug that took queue.js down in prod — fails the build here
# instead of crash-looping every deployed task.
RUN node -e "require('./queue'); require('./classify'); require('./embed');"

ENV PORT=8080
EXPOSE 8080
USER node
CMD ["node", "server.js"]
