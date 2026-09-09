# classify-service

Standalone serving for the platform's built-in encoder heads: one single-text head
(`frustration` — purpose-built ModernBERT ONNX, [`tessaryai/frustration-detector`](https://huggingface.co/tessaryai/frustration-detector),
trained on [`tessaryai/frustration-v1`](https://huggingface.co/datasets/tessaryai/frustration-v1))
plus one pair head, `groundedness` (claim-vs-premise support scoring — MiniCheck-RoBERTa-Large).
Extracted from the sandbox-runner launcher after the 2026-07-12 incident, where a classification
burst OOM-looped the shared production host: CPU inference with model weights now runs in its
own resource envelope (ECS Fargate, ARM64, **2 vCPU / 6 GB** — `classify_cpu` / `classify_memory`
in the infrastructure repo; see the 2026-08-11 note, which raises the memory) and can only ever
kill its own task.

**2026-07-16 catalog reduction:** `refusal`/`jailbreak`/`unsafe_text` were retired from the
default classifier catalog and decommissioned here (see `BuiltInSignalCatalog`'s catalog-
reduction note) — down from 5 resident heads to 2.

**2026-07-23 frustration rebuild:** replaced the GoEmotions `max(annoyance,anger,disappointment)`
proxy (q8) with a context-aware ModernBERT binary head (fp32, ~600 MB ONNX). Both resident
heads are private `tessaryai/*` pins; bake needs `HF_TOKEN`. `models.json` may carry optional
`dataset_repo` / `dataset_revision` provenance (ignored by the bake — prod is weights-only).

**2026-08-11 conformance encoder registered, bake opt-in.** `embedders.json` now names
`Alibaba-NLP/gte-large-en-v1.5` so `/embed` has a checkpoint to serve (see "Embedding"
below) — without it both callers are dead, the conformance sweep and the compile service's
fit. Its ~1.7 GB of fp32 weights are baked **only** when the image is built with
`BAKE_EMBEDDERS=1`. Dev compose passes it, and **production now does too** — the ECS task was
raised to 2 vCPU / 8 GB first. See "Enabling the conformance encoder in production" below for the
ordering, which matters if it is ever reverted.

## Contract

Same wire contract the launcher exposed, minus everything E2B, with two mutually-exclusive
request shapes depending on the head:

- `POST /classify {head, texts[]} -> {scores[]}` — single-text heads. Bearer auth with
  `CLASSIFY_API_KEY`. Batches cap at 512 texts / 8 MB body. `MAX_INFLIGHT` requests run at once
  (default 2, the memory ceiling); extras wait in a bounded FIFO queue (`MAX_QUEUE`, default 8)
  rather than failing outright. It returns 429 only when the queue is full or a waiter exceeds
  `QUEUE_TIMEOUT_MS` (default 20000), at which point the backend's sweep retry is the backpressure.
- `POST /classify {head, pairs: [{premise, claim}]} -> {scores[]}` — pair heads (`groundedness`
  today; see `PAIR_HEADS` in `classify.js`). Scores each `claim` for support against its
  `premise`; a long premise is windowed internally (chunk + max-aggregate, never truncated
  silently) so a fact buried past one encoder window is still caught — see "Pair heads" below.
  Same batch cap, auth, and backpressure as the single-text shape.
- `POST /embed {checkpoint, texts[]} -> {vectors[][], dim, checkpoint}` — raw sentence
  embeddings for the SOP-conformance classifier, under the conformance fit contract
  (tokenize with the checkpoint's own `tokenizer.json`, truncation 256 → `last_hidden_state`
  → attention-mask mean pool (mask-sum clamp 1e-9) → L2-normalise (1e-12 floor)); see
  "Embedding" below. Same auth and the same shared backpressure gate as `/classify`;
  batches cap at `EMBED_MAX_TEXTS` (default 256, a **400** with a clear message past it —
  bounded work per request is this service's founding lesson).
- `GET /healthz` — unauthenticated, used by the ECS health check.

### Pair heads

`groundedness` scores whether a `claim` (the thing being checked — typically a model's output)
is supported by a `premise` (the source content it should be grounded in — input/context/tool
results). Unlike `frustration`, the model needs BOTH strings, joined internally as
`premiseChunk + tokenizer.eos_token + claim` (MiniCheck's own input format — no separate
pair-tokenizer path). The premise, not the claim, is what gets chunked when the input is too
big for the ~512-token window: the claim is the exact content being judged, so it must never be
the part silently dropped by truncation. See `premiseChunksFor`/`classifyPairs` in `classify.js`.

### Embedding (`/embed`)

`/embed` moved the conformance classifier's turn-text embedding out of the backend JVM
(where `OnnxConformanceEncoder` ran it as an explicitly-interim measure) into this
service's resource envelope — the accepted plan from that class's Javadoc. Registry is
`embedders.json`, same manifest discipline as `models.json` (pinned revision, bake input,
localModelPath layout). It registers one checkpoint,
`Alibaba-NLP/gte-large-en-v1.5` @ `104333d6af6f97649377c2afbde10a7704870c7b`, **fp32** —
the checkpoint the engine fits against and names verbatim in every bundle manifest
(`manifest.encoder.checkpoint`), so the fit's checkpoint-echo check passes and the sweep
finds an encoder. The hub's int8/fp16 exports would be smaller but are a **different
encoder** as far as a fitted bundle's coefficients and the parity fixture's reference
vectors are concerned, so the dtype is pinned and `download.js` rejects anything else.

**Registered is not baked.** The manifest ships in every image; the ~1.7 GB of weights
behind it are downloaded only when the image is built with `BAKE_EMBEDDERS=1`
(`onnx/model.onnx` + the two tokenizer files — the bake streams three files, not the
repo). Built without it, `/embed` answers a **400** naming the checkpoint and saying the
weights are not in this image, boot pre-warm logs it and skips it, and `/classify` is
untouched. Unknown checkpoints get the same treatment: 400, never a substitute encoder and
never a zero vector, because a bundle fitted against one encoder scored with another is
exactly the silent divergence the conformance parity fixture exists to prevent.

The arithmetic is pinned twice: `embed.test.js` reproduces the
fixture's `pooling_cases` at 1e-9, and `embed.smoke.test.js` (opt-in via
`CONFORMANCE_ENCODER_MODEL_DIR=/path/to/model`, mirroring the backend's
`ConformanceEncoderSmokeTest`) embeds the fixture's three `encoder_smoke` sentences
against a real model and asserts cosine ≥ 0.999 versus the Python engine's reference
vectors. The backend's `HttpConformanceEncoder` is the only caller.

The backend reaches this service via `tessary.observer.encoder.url` / `.api-key`
(`TESSARY_OBSERVER_ENCODER_URL` / `TESSARY_OBSERVER_ENCODER_API_KEY`). This is the ONLY
`/classify` implementation — the sandbox-runner launcher does not serve it, and there is no
fallback endpoint. Locally the same image runs as the `classify` service in
`docker-compose.dev.yml`; `classify.js` here is the single head registry for every
environment.

## Weights are baked at build time — and cached aggressively

Multi-stage build. The `models` stage downloads the pinned revisions and assembles a
localModelPath layout; its ONLY inputs are `models.json` (the model manifest: ids, pinned
revisions, dtype), `download.js`, and the lockfile. The runtime stage copies the baked
weights in and validates every head **offline** (`allowRemoteModels=false` — the loader is
physically unable to reach the hub at runtime), so a bad pin or missing file fails the
build instead of production.

Cache behavior, by what you edit:

| change | what rebuilds |
|---|---|
| `server.js` | final COPY only (~1 s) |
| `classify.js` scoring / `warmup.js` | offline re-validation (~4 s) |
| `models.json` / `embedders.json` / `download.js` / lockfile / the `BAKE_EMBEDDERS` value | full re-bake (~1–2 min, ~4 with the encoder) — the only good reason |

In CI the cache lives in ECR under the `:buildcache` tag (BuildKit registry cache, exempt
from the repo's image-expiry rule), so the weights layer survives across runners and
GHA cache eviction.

## Enabling the conformance encoder in production

**Production bakes the encoder as of the `bake_embedders: "1"` input on
`deploy-classify-service.yml`**, which reached the build through `reusable-ecs-deploy.yml`. Before
that it built without the arg, so the image carried the two `/classify` heads and nothing else and
`POST /embed` answered 400 for `Alibaba-NLP/gte-large-en-v1.5` even though `embedders.json`
registered it.

The prerequisite was met first: the ECS task is 2 vCPU / **8192 MiB** (task definition revision 5,
`tessaryai/infrastructure#14`). SOP-conformance is still capability-flagged off for every org, so
nothing in production calls `/embed` yet — the encoder is present and idle, which is the point:
the capability can now be turned on for a project without a deploy.

Why it is not simply on: the encoder is ~1.7 GB of fp32 weights and boot pre-warm loads it.
Measured on ARM64 with `docker run --memory=N --memory-swap=N`:

| task memory | result |
|---|---|
| 4 GB | **OOM-killed, exit 137, during pre-warm** — never becomes healthy |
| 5 GB | warms and serves, ~4.5 GiB resident |
| 6 GB (the task before the resize) | warms and serves, ~4.5 GiB resident |
| 8 GB (today's task) | warms and serves, with the arena headroom the sizing assumes |

The two heads alone sit at ~3.2 GiB, so the encoder adds ~1.2 GiB. That is why 4 GB is fatal and
why 6 GB — which boots — was still not enough: the sizing exists to leave the onnxruntime arena
~4 GB of high-water under worst-case fp32 long-text serving, and a resident encoder leaves it
~1.5 GiB.

**The order this was done in, and the order any revert must follow:**

1. **Raise the ECS task to 8 GB** (2 vCPU / 8192 MiB, a valid Fargate pairing). The task definition
   is **not in this repo** — it is the infrastructure repo's
   `terraform/modules/evals-platform/ecs-classify.tf` (`classify_memory`); the workflows here only
   push an image and roll the service. Done: revision 5, applied and stable on the pre-encoder
   image before step 2.
2. **Then** pass the build arg — `bake_embedders: "1"` on the `deploy-classify-service.yml` call
   into `reusable-ecs-deploy.yml`, which forwards it as `BAKE_EMBEDDERS` to the Dockerfile. The
   first build after the flip re-bakes the weights layer and adds ~1.4 GB to the pushed image.

Doing (2) before (1) is the crash loop above. **Reverting must undo them in reverse**: drop
`bake_embedders` and redeploy first — `/embed` goes back to a 400 while `/classify` keeps serving —
and only then shrink the task, or the running image will not fit the smaller one.

## Build & run

```bash
# production image (Fargate runs ARM64/Graviton). The groundedness head's weights are a
# private HF repo (tessaryai/MiniCheck-RoBERTa-Large-onnx) — HF_TOKEN must be passed as a
# BuildKit secret, never a build ARG (that would land in image history).
HF_TOKEN=<token> docker buildx build --secret id=hf_token,env=HF_TOKEN \
  --platform linux/arm64 -t tessary-classify:dev .

# same, plus the SOP-conformance encoder (~1.4 GB larger, needs a >= 6 GB task to boot and 8 GB for arena headroom —
# read "Enabling the conformance encoder in production" first). This is what `task dev` builds.
HF_TOKEN=<token> docker buildx build --secret id=hf_token,env=HF_TOKEN \
  --build-arg BAKE_EMBEDDERS=1 --platform linux/arm64 -t tessary-classify:dev .

# local
CLASSIFY_API_KEY=dev pnpm start
curl -s localhost:8080/classify -H 'Authorization: Bearer dev' -H 'Content-Type: application/json' \
  -d '{"head":"frustration","texts":["This is absolutely infuriating, you never listen!"]}'
curl -s localhost:8080/classify -H 'Authorization: Bearer dev' -H 'Content-Type: application/json' \
  -d '{"head":"groundedness","pairs":[{"premise":"The candidate has 5 years of Python experience.","claim":"The candidate knows Python."}]}'
```

Deployed by `.github/workflows/deploy-classify-service.yml` (ECR push + ECS redeploy);
infrastructure lives in the infrastructure repo, `terraform/modules/evals-platform/ecs-classify.tf`.
