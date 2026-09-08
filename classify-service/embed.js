// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Raw sentence-embedding serving for the SOP-conformance classifier — POST /embed.
 *
 * Unlike the /classify heads (one calibrated score per text), /embed returns the raw
 * pooled+normalised embedding vector under the conformance FIT CONTRACT, the exact
 * arithmetic every coefficient in a conformance artifact bundle was fitted against
 * (the conformance engine's shared/encoders.py, overlay-owned since #1293, mirrored by the backend's
 * EmbeddingPooling.java):
 *
 *   tokenize with the checkpoint's own tokenizer.json, truncation to 256 tokens
 *   (HF semantics: content truncated to fit, the closing special token kept —
 *   see fitContractTruncate)
 *   -> ONNX forward pass -> last_hidden_state
 *   -> attention-mask mean pooling (mask-sum clamped at 1e-9)
 *   -> L2 normalisation (norm floored at 1e-12)
 *
 * This endpoint exists so that arithmetic can run HERE — in the process whose whole
 * reason for existing is that CPU inference can only ever kill its own task (the
 * 2026-07-12 OOM incident) — instead of inside the backend JVM, which served it as an
 * explicitly-interim measure (see OnnxConformanceEncoder's Javadoc). Pinnability is
 * preserved: the model is a real ONNX session on the checkpoint's pinned weights, the
 * tokenizer is the checkpoint's own tokenizer.json (loaded through transformers.js's
 * tokenizers implementation, the same runtime every /classify head already tokenizes
 * with), and the pooling below is pinned by the backend parity fixture's
 * `pooling_cases` at 1e-9 (embed.test.js) with the full path smoke-checked against
 * Python-computed reference vectors at cosine >= 0.999 (embed.smoke.test.js).
 *
 * Checkpoint registry: embedders.json — same manifest discipline as models.json
 * (pinned revisions, the ONLY bake input alongside download.js), same localModelPath
 * layout under HF_CACHE_DIR (/models/<model-id>/{tokenizer.json, onnx/model.onnx}).
 * It registers `Alibaba-NLP/gte-large-en-v1.5`, the checkpoint the engine fits against
 * and names in every bundle manifest (`experiments.engine.run.ENCODERS['gte']`, echoed
 * into `manifest.encoder.checkpoint`) — the key here is that string VERBATIM, because
 * the fit's own checkpoint-echo check (shared.encoders.RemoteEncoder) refuses to fit
 * when what /embed echoes back differs from what the bundle will declare. Both paths
 * through /embed are otherwise dead: the serving sweep (HttpConformanceEncoder) and the
 * compile service's fit, which carries no checkpoint of its own on purpose.
 *
 * REGISTERED IS NOT BAKED. The manifest ships in every image; the ~1.7 GB of fp32 weights
 * behind it are baked only when the image is built with BAKE_EMBEDDERS=1 (dev compose
 * passes it; the production deploy does NOT). The reason is memory, measured: with the
 * encoder resident the service settles at ~4.5 GiB and is OOM-killed during pre-warm on
 * the 4 GB production task, so baking it by default would take the /classify heads down
 * with it. Enabling it in production is a two-step change — raise the ECS task to 8 GB in
 * the infra repo FIRST, then build with the arg — spelled out in the README.
 *
 * fp32 is pinned, not chosen for size: the hub repo also ships int8/fp16/q4 exports and
 * any of them would shrink the image, but the coefficients in a fitted bundle and the
 * parity fixture's reference vectors are fp32 arithmetic — a different dtype is a
 * different encoder wearing the same name. download.js enforces the pin.
 *
 * An unknown checkpoint — and a registered one whose weights this image does not carry —
 * is refused loudly (400), never served by a substitute and never by a zero vector: a
 * bundle fitted against one encoder scored with another is exactly the silent divergence
 * the parity fixture exists to prevent.
 */

const fs = require('node:fs');
const path = require('node:path');
// deBlob only — the shared pre-tokenization guard. classify.js's scoring machinery
// (batching, windowing, score maps) is per-head and deliberately not reused here.
const { deBlob } = require('./classify');

/** The fit contract's tokenizer truncation bound (max_length=256 in encoders.py). */
const MAX_TOKENS = 256;

/** torch's clamp(min=1e-9) on the mask sum — a fully-masked row divides by this, not zero. */
const MASK_SUM_FLOOR = 1e-9;

/** torch F.normalize's default eps — a zero vector divides by this and stays zero. */
const NORM_FLOOR = 1e-12;

/**
 * Batch cap for one /embed request. Half the /classify text cap: each element of the
 * RESPONSE is a ~1024-float vector (~5 MB of JSON at 256 texts), so the bound is on
 * response size and per-request work, not just tokenization cost. The backend client
 * chunks far below this (32 texts/request); anything larger is rejected with a clear
 * 400 rather than attempted — bounded work per request is this service's founding
 * lesson.
 */
const EMBED_MAX_TEXTS = Number(process.env.EMBED_MAX_TEXTS || 256);

/**
 * Per-text clamp before tokenization, same rationale as classify.js's MAX_TEXT_CHARS:
 * the model sees at most 256 tokens (~1K chars of dense prose), so everything past
 * this bound is guaranteed-discarded by the tokenizer's own truncation — the clamp
 * only prevents tokenizing megabyte texts at all. Note the in-JVM encoder applies no
 * such clamp; the two implementations can differ only on inputs where a >4K-char
 * prefix still fits in 256 tokens, i.e. degenerate non-prose blobs whose embeddings
 * are meaningless either way. deBlob (below) has the same character.
 */
const EMBED_MAX_TEXT_CHARS = Number(process.env.EMBED_MAX_TEXT_CHARS || 4096);

// Manifest: checkpoint name -> {model, revision, dtype}. Keys are the exact checkpoint
// names conformance bundle manifests carry (manifest.encoder.checkpoint); `model` is
// the hub repo baked under HF_CACHE_DIR. Validated eagerly so a malformed manifest
// fails the build guard / check script, never a live request.
const EMBEDDERS = require('./embedders.json');
for (const [checkpoint, spec] of Object.entries(EMBEDDERS)) {
  if (!spec || !spec.model || !/^[0-9a-f]{40}$/.test(spec.revision) || spec.dtype !== 'fp32') {
    throw new Error(
      `embedders.json checkpoint '${checkpoint}' has an incomplete spec ` +
        `(model/revision-sha required, dtype must be 'fp32' — the fit contract embeds in fp32)`,
    );
  }
}

/** A 400-worthy client error: bad request shape, unknown checkpoint, oversized batch. */
function badRequest(message) {
  return Object.assign(new Error(message), { statusCode: 400 });
}

/**
 * The model directory for `spec` if its weights are actually baked into this image, else null.
 *
 * Registration and residency are deliberately two different things. embedders.json is CONFIG —
 * it names the checkpoint every conformance bundle declares, and it must say so in every image
 * so the backend, the compile service and the gate all agree on the name. The ~1.7 GB of weights
 * behind that name are OPTIONAL, baked only when the image is built with BAKE_EMBEDDERS=1,
 * because the encoder does not fit the production task's memory envelope yet. Every caller that
 * needs "can I actually serve this?" asks here rather than assuming the manifest implies weights.
 */
function residentModelDir(spec) {
  const dir = path.join(process.env.HF_CACHE_DIR || '/models', spec.model);
  const hasTokenizer = fs.existsSync(path.join(dir, 'tokenizer.json'));
  const hasOnnx = fs.existsSync(path.join(dir, 'model.onnx')) || fs.existsSync(path.join(dir, 'onnx', 'model.onnx'));
  return hasTokenizer && hasOnnx ? dir : null;
}

/**
 * Split the declared registry into what this image can serve and what it only names.
 * Shared by the boot pre-warm, the build-time warmup assertion and the check script, so
 * "is the encoder baked?" has exactly one definition.
 */
function checkpointResidency() {
  const resident = [];
  const missing = [];
  for (const [checkpoint, spec] of Object.entries(EMBEDDERS)) {
    (residentModelDir(spec) ? resident : missing).push(checkpoint);
  }
  return { resident, missing };
}

/**
 * Attention-mask mean pooling + L2 normalisation over one sequence — the reimplemented
 * half of the fit contract, kept a pure function so embed.test.js can pin it against
 * the backend parity fixture's `pooling_cases` at 1e-9 (the same discipline as the
 * backend's EmbeddingPooling.meanPoolNormalize; JS numbers are IEEE-754 doubles, the
 * same precision the Java twin and the Python reference compute in).
 *
 * @param {number[][]} lastHiddenState [tokens][hidden] final hidden states
 * @param {number[]} attentionMask [tokens] 1 for real tokens, 0 for padding
 * @returns {number[]} the [hidden]-wide unit embedding
 */
function meanPoolNormalize(lastHiddenState, attentionMask) {
  if (lastHiddenState.length !== attentionMask.length) {
    throw new Error(
      `last_hidden_state has ${lastHiddenState.length} token rows but the attention mask has ${attentionMask.length}`,
    );
  }
  if (lastHiddenState.length === 0) throw new Error('cannot pool an empty sequence');
  const hidden = lastHiddenState[0].length;
  const pooled = new Array(hidden).fill(0);
  let maskSum = 0;
  for (let t = 0; t < lastHiddenState.length; t++) {
    if (lastHiddenState[t].length !== hidden) {
      throw new Error(`ragged last_hidden_state: token ${t} has ${lastHiddenState[t].length} dims, token 0 has ${hidden}`);
    }
    const m = attentionMask[t];
    maskSum += m;
    if (m === 0) continue;
    for (let j = 0; j < hidden; j++) pooled[j] += lastHiddenState[t][j] * m;
  }
  const denominator = Math.max(maskSum, MASK_SUM_FLOOR);
  let normSquared = 0;
  for (let j = 0; j < hidden; j++) {
    pooled[j] /= denominator;
    normSquared += pooled[j] * pooled[j];
  }
  const norm = Math.max(Math.sqrt(normSquared), NORM_FLOOR);
  for (let j = 0; j < hidden; j++) pooled[j] /= norm;
  return pooled;
}

// One warm embedder per model dir, memoized as the loading promise (concurrent first
// requests share one load). Loads are serialized on a chain, same reasoning as
// classify.js's loadChain: concurrent ONNX session builds spike transient memory.
const embedders = new Map();
let loadChain = Promise.resolve();

function embedderFor(dir) {
  let p = embedders.get(dir);
  if (!p) {
    const load = () => loadEmbedder(dir);
    p = loadChain.then(load);
    loadChain = p.catch(() => {});
    p.catch(() => embedders.delete(dir));
    embedders.set(dir, p);
  }
  return p;
}

/**
 * Load one embedder from a model directory holding the checkpoint's own
 * tokenizer.json (+ optional tokenizer_config.json) and model.onnx (or
 * onnx/model.onnx, the hub layout — the same two layouts the backend's in-JVM
 * encoder accepts). Offline by construction: files are read straight from disk,
 * no hub-aware loader is ever invoked.
 */
async function loadEmbedder(dir) {
  const tokenizerJson = path.join(dir, 'tokenizer.json');
  const rootOnnx = path.join(dir, 'model.onnx');
  const modelOnnx = fs.existsSync(rootOnnx) ? rootOnnx : path.join(dir, 'onnx', 'model.onnx');
  if (!fs.existsSync(tokenizerJson) || !fs.existsSync(modelOnnx)) {
    throw new Error(`embed model dir ${dir} must hold tokenizer.json and model.onnx (or onnx/model.onnx)`);
  }
  const started = Date.now();
  // The tokenizer is the checkpoint's OWN tokenizer.json, executed by transformers.js's
  // tokenizers implementation — the runtime every /classify head already tokenizes with.
  const tf = await import('@huggingface/transformers');
  const tokenizerConfigPath = path.join(dir, 'tokenizer_config.json');
  const tokenizerConfig = fs.existsSync(tokenizerConfigPath)
    ? JSON.parse(fs.readFileSync(tokenizerConfigPath, 'utf8'))
    : {};
  const tokenizer = new tf.PreTrainedTokenizer(JSON.parse(fs.readFileSync(tokenizerJson, 'utf8')), tokenizerConfig);
  const template = specialTokenTemplate(tokenizer);
  const truncationSide = tokenizerConfig.truncation_side === 'left' ? 'left' : 'right';
  // A REAL ONNX session on the pinned weights — onnxruntime-node directly, not a
  // pipeline: /embed needs last_hidden_state, and no task pipeline exposes it raw.
  // No CPU memory arena, same reasoning as classify.js: the arena retains its
  // high-water mark per session, so inference memory must return to baseline instead.
  const ort = require('onnxruntime-node');
  const session = await ort.InferenceSession.create(modelOnnx, { enableCpuMemArena: false });
  const wantsTokenTypeIds = session.inputNames.includes('token_type_ids');
  console.log(`embed: model ready from ${dir} in ${Date.now() - started}ms (tokenTypeIds ${wantsTokenTypeIds})`);
  return { tokenizer, session, ort, wantsTokenTypeIds, template, truncationSide };
}

/**
 * The tokenizer's single-sequence special-token layout — the id prefix/suffix its
 * post-processor wraps around content tokens ([CLS] … [SEP] for the BERT family) —
 * derived by probing the tokenizer itself rather than parsing tokenizer.json's
 * post_processor schema: the probe reads the truth from the exact code path that runs
 * at serve time, for any template, and cannot rot against a library bump.
 */
function specialTokenTemplate(tokenizer) {
  const withSpecials = Array.from(tokenizer('a', { add_special_tokens: true }).input_ids.data, Number);
  const bare = Array.from(tokenizer('a', { add_special_tokens: false }).input_ids.data, Number);
  for (let i = 0; i + bare.length <= withSpecials.length; i++) {
    if (bare.every((id, j) => withSpecials[i + j] === id)) {
      return { prefix: withSpecials.slice(0, i), suffix: withSpecials.slice(i + bare.length) };
    }
  }
  throw new Error(
    'cannot derive the special-token template: the bare tokenization is not a contiguous run of the special-token encoding',
  );
}

/**
 * HF-faithful truncation of a single sequence's CONTENT ids under a special-token
 * template: keep `maxTokens - prefix - suffix` content tokens (from the start under
 * right truncation, the end under left) and re-wrap them in the template — the
 * arithmetic HF's tokenizers crate applies with `truncation=True, max_length=N`,
 * yielding [CLS] + content + [SEP]. transformers.js instead slices the FINISHED
 * sequence to maxTokens, which drops the trailing [SEP] and keeps one extra content
 * token — a different final window, whose pooled embedding drifts far enough
 * (measured cosine 0.928–0.962) to flip near-boundary head decisions against
 * coefficients fitted on the HF tokenization. Pure and exported so the fit contract's
 * truncation half is pinned by embed.test.js without a model.
 */
function fitContractTruncate(contentIds, prefix, suffix, maxTokens, truncationSide) {
  const keep = maxTokens - prefix.length - suffix.length;
  if (keep < 0) throw new Error(`special-token template (${prefix.length}+${suffix.length}) exceeds maxTokens ${maxTokens}`);
  const kept =
    truncationSide === 'left' ? contentIds.slice(Math.max(0, contentIds.length - keep)) : contentIds.slice(0, keep);
  return [...prefix, ...kept, ...suffix];
}

/** One forward pass: tokenize (truncation 256, no padding) -> session -> pool+normalise. */
async function embedOne(embedder, text) {
  const { tokenizer, session, ort, wantsTokenTypeIds, template, truncationSide } = embedder;
  const clamped = deBlob(text.length > EMBED_MAX_TEXT_CHARS ? text.slice(0, EMBED_MAX_TEXT_CHARS) : text);
  // The fit contract's tokenization: the checkpoint's own tokenizer, truncation to 256.
  // Single sequences, never padded — under masked mean pooling that is arithmetically
  // the padding-inert equivalent of the engine's padded batches.
  const enc = tokenizer(clamped, { truncation: true, max_length: MAX_TOKENS, add_special_tokens: true });
  let ids = enc.input_ids.data; // BigInt64Array, dims [1, T]
  let mask = enc.attention_mask.data;
  if (ids.length === MAX_TOKENS) {
    // Possibly truncated — and transformers.js truncates by slicing the FINISHED sequence,
    // which diverges from the HF tokenization every bundle was fitted against (see
    // fitContractTruncate). Re-encode the bare content and re-wrap it in the template so
    // a truncated sequence is bit-identical to the fit side's; the untruncated path above
    // stays literally the same call it always was.
    const content = Array.from(tokenizer(clamped, { add_special_tokens: false }).input_ids.data, Number);
    if (content.length > MAX_TOKENS - template.prefix.length - template.suffix.length) {
      const rebuilt = fitContractTruncate(content, template.prefix, template.suffix, MAX_TOKENS, truncationSide);
      ids = BigInt64Array.from(rebuilt.map(BigInt));
      mask = new BigInt64Array(ids.length).fill(1n);
    }
  }
  const tokens = ids.length;
  const feeds = {
    input_ids: new ort.Tensor('int64', ids, [1, tokens]),
    attention_mask: new ort.Tensor('int64', mask, [1, tokens]),
  };
  if (wantsTokenTypeIds) {
    // Zeros for a single sequence — exactly what the HF tokenizer emits for one segment.
    feeds.token_type_ids = new ort.Tensor('int64', new BigInt64Array(tokens), [1, tokens]);
  }
  const out = await session.run(feeds);
  const hidden = out[session.outputNames[0]]; // last_hidden_state — the graph's first output
  const [batch, seq, width] = hidden.dims;
  if (batch !== 1 || seq !== tokens) {
    throw new Error(`encoder returned last_hidden_state of shape [${hidden.dims}] for a [1, ${tokens}]-token input`);
  }
  const rows = new Array(seq);
  const maskNumbers = new Array(seq);
  for (let t = 0; t < seq; t++) {
    rows[t] = Array.from(hidden.data.subarray(t * width, (t + 1) * width));
    maskNumbers[t] = Number(mask[t]);
  }
  return meanPoolNormalize(rows, maskNumbers);
}

/**
 * Embed `texts` (sequentially — one bounded forward pass at a time; the request-level
 * concurrency gate in server.js is the cross-request bound) with the model in `dir`.
 * Exported for the opt-in smoke test, which serves a local model dir directly.
 */
async function embedWithDir(dir, texts) {
  const embedder = await embedderFor(dir);
  const vectors = [];
  for (const text of texts) {
    vectors.push(await embedOne(embedder, text));
  }
  return vectors;
}

/**
 * POST /embed { checkpoint, texts: [".."] } -> { vectors: [[..]], dim, checkpoint }
 * Vectors are index-aligned with the input texts. Client errors (bad shape, unknown
 * checkpoint, oversized batch) carry statusCode 400 and a message safe to return;
 * anything else is a serving failure the caller must treat as loud (502).
 */
async function embed(payload) {
  const { checkpoint, texts } = payload || {};
  if (typeof checkpoint !== 'string' || checkpoint.length === 0) {
    throw badRequest('embed checkpoint must be a non-empty string');
  }
  if (!Array.isArray(texts) || texts.length === 0 || !texts.every((t) => typeof t === 'string')) {
    throw badRequest('embed texts must be a non-empty string array');
  }
  if (texts.length > EMBED_MAX_TEXTS) {
    throw badRequest(`embed batch of ${texts.length} texts exceeds the ${EMBED_MAX_TEXTS}-text cap — send smaller batches`);
  }
  // Object.hasOwn: an inherited prototype property (checkpoint="constructor") must be
  // an unknown checkpoint, not a truthy junk spec.
  const spec = Object.hasOwn(EMBEDDERS, checkpoint) ? EMBEDDERS[checkpoint] : undefined;
  if (!spec) {
    throw badRequest(`unknown embed checkpoint: '${checkpoint}' — not in embedders.json, refusing to substitute an encoder`);
  }
  // Declared but not baked: this image was built without BAKE_EMBEDDERS=1 (the production
  // default — see the README's "Enabling the conformance encoder in production"). Refuse
  // it as a 400 with the reason, exactly like an unknown checkpoint, rather than letting
  // the loader fail as an opaque 502: the caller cannot fix either by retrying, and the
  // one thing that must never happen is a substitute encoder or a zero vector.
  const dir = residentModelDir(spec);
  if (!dir) {
    throw badRequest(
      `embed checkpoint '${checkpoint}' is registered in embedders.json but its weights are not in this image ` +
        `(built without BAKE_EMBEDDERS=1) — refusing to substitute an encoder`,
    );
  }
  const vectors = await embedWithDir(dir, texts);
  const dim = vectors[0].length;
  for (const v of vectors) {
    if (v.length !== dim) throw new Error(`inconsistent embedding widths in one batch: ${v.length} vs ${dim}`);
  }
  return { vectors, dim, checkpoint };
}

/**
 * Load every RESIDENT manifest checkpoint sequentially (server boot pre-warm + build warmup).
 * Skips declared-but-not-baked checkpoints instead of throwing: server.js exits the process
 * when pre-warm rejects, so throwing here would turn an image built without BAKE_EMBEDDERS=1
 * — the production default — into a crash loop that takes the /classify heads down with it.
 * The build-time assertion that an ON bake actually produced weights lives in warmup.js,
 * which knows the build's intent; at runtime a missing checkpoint is a per-request 400.
 */
async function warmAllEmbedders() {
  const { resident, missing } = checkpointResidency();
  for (const checkpoint of resident) {
    await embedderFor(residentModelDir(EMBEDDERS[checkpoint]));
    console.log(`embed: checkpoint '${checkpoint}' resident`);
  }
  for (const checkpoint of missing) {
    console.log(`embed: checkpoint '${checkpoint}' declared but not baked into this image — /embed will 400 for it`);
  }
}

module.exports = {
  embed,
  embedWithDir,
  warmAllEmbedders,
  meanPoolNormalize,
  fitContractTruncate,
  specialTokenTemplate,
  checkpointResidency,
  EMBEDDERS,
  MAX_TOKENS,
};
