// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Build-time model download + assembly (Dockerfile stage 1 of the bake).
 *
 * Downloads every head at its PINNED revision through the hub cache, then assembles a
 * plain localModelPath layout under HF_CACHE_DIR:
 *
 *   /models/<model-id>/{config.json, tokenizer.json, tokenizer_config.json, onnx/...}
 *
 * The runtime (classify.js) loads with env.localModelPath + allowRemoteModels=false, so
 * revisions are enforced HERE and only here — what got baked is what serves. warmup.js
 * (stage 2) then validates every head under those exact offline conditions, so a file
 * this step failed to assemble breaks the build, not production.
 */
const fs = require('node:fs');
const path = require('node:path');
// models.json / embedders.json directly — NOT via classify.js / embed.js — so this
// stage's inputs are exactly the manifests + this file, and scoring-code edits never
// invalidate the weights layer. Bake reads only {model, revision, dtype[, subfolder]};
// optional dataset_repo / dataset_revision fields are provenance for the platform and
// are ignored here.
const HEADS = require('./models.json');
const EMBEDDERS = require('./embedders.json');

const STAGING = '/tmp/hub-staging';
const TARGET = process.env.HF_CACHE_DIR || '/models';

/**
 * Fetch one file of an embed checkpoint at its PINNED revision straight from the hub's
 * resolve endpoint (streamed to disk — model.onnx is GBs, never buffered whole).
 * `required=false` tolerates a 404 (tokenizer_config.json is optional in the /embed
 * layout); anything else non-2xx fails the bake.
 */
async function fetchPinned(spec, file, dest, required) {
  const url = `https://huggingface.co/${spec.model}/resolve/${spec.revision}/${file}`;
  const headers = process.env.HF_TOKEN ? { Authorization: `Bearer ${process.env.HF_TOKEN}` } : {};
  const res = await fetch(url, { headers });
  if (res.status === 404 && !required) {
    console.log(`embed bake: optional ${file} absent for ${spec.model}@${spec.revision}, skipping`);
    return;
  }
  if (!res.ok) throw new Error(`embed bake: GET ${url} -> HTTP ${res.status}`);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const stream = require('node:stream');
  await stream.promises.pipeline(stream.Readable.fromWeb(res.body), fs.createWriteStream(dest));
}

/** Copy src dir into dst recursively; existing files in dst win (revision-pinned beats stray). */
function mergeDir(src, dst, overwrite) {
  fs.mkdirSync(dst, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dst, entry.name);
    if (entry.isDirectory()) mergeDir(s, d, overwrite);
    else if (overwrite || !fs.existsSync(d)) fs.copyFileSync(s, d);
  }
}

(async () => {
  const tf = await import('@huggingface/transformers');
  tf.env.cacheDir = STAGING;
  tf.env.allowRemoteModels = true;

  // frustration + attribution live in private tessaryai/ HF repos and need a token with
  // read access; groundedness (Xenova/bart-large-mnli) is public and always bakes. A
  // gated head is skipped ONLY on a missing token — an explicit-but-bad token still fails
  // loud below (the fetch itself errors), so this never silently masks a real credential
  // problem, only a genuinely absent one. This is what makes it possible to build the image
  // without an HF_TOKEN at all: the two private heads report themselves
  // "unavailable" at runtime (classify.js) instead of the build needing their weights.
  const hasToken = !!process.env.HF_TOKEN;
  const baked = [];

  for (const [head, spec] of Object.entries(HEADS)) {
    if (spec.gated && !hasToken) {
      console.log(`skipping gated head '${head}' (${spec.model}) — no HF_TOKEN at build time`);
      continue;
    }
    const started = Date.now();
    const opts = { dtype: spec.dtype, revision: spec.revision };
    if (spec.subfolder !== undefined) opts.subfolder = spec.subfolder;
    await tf.pipeline('text-classification', spec.model, opts);
    console.log(`downloaded head '${head}' (${spec.model}@${spec.revision}) in ${Date.now() - started}ms`);

    // Assemble: <staging>/<model>/<revision>/* is the pinned tree — it wins; any
    // root-level strays the loader cached under other revision keys (e.g. the
    // main-keyed tokenizer_config.json) fill remaining gaps.
    const modelStaging = path.join(STAGING, spec.model);
    const revDir = path.join(modelStaging, spec.revision);
    const target = path.join(TARGET, spec.model);
    if (!fs.existsSync(revDir)) throw new Error(`no staged files for ${spec.model}@${spec.revision}`);
    mergeDir(revDir, target, true);
    for (const entry of fs.readdirSync(modelStaging, { withFileTypes: true })) {
      if (entry.isFile()) {
        const d = path.join(target, entry.name);
        if (!fs.existsSync(d)) fs.copyFileSync(path.join(modelStaging, entry.name), d);
      }
    }
    baked.push(head);
  }

  // Clamp every BAKED head's tokenizer window to the 512-token encoder reality. Some
  // checkpoints ship the HF placeholder model_max_length=1e30
  // (deberta-v3-base-prompt-injection-v2 does), which turns `truncation: true` into a
  // no-op — attention memory then scales with the SQUARE of arbitrary input length. Root
  // cause of both the 2026-07-12 host incident and the Fargate OOM loop. Fixing the baked
  // artifact keeps runtime code off private tokenizer APIs; classify.js asserts the clamp
  // held (so warmup fails a bad bake). Iterating `baked` rather than all of HEADS matters:
  // a gated head skipped above never wrote a tokenizer_config.json, so reading HEADS
  // directly here would throw ENOENT on the exact heads-off build this file exists to
  // support.
  for (const head of baked) {
    const spec = HEADS[head];
    const cfgPath = path.join(TARGET, spec.model, 'tokenizer_config.json');
    const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
    const declared = Number(cfg.model_max_length);
    cfg.model_max_length = Number.isFinite(declared) && declared > 0 ? Math.min(declared, 512) : 512;
    fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2));
    console.log(`clamped ${spec.model} model_max_length -> ${cfg.model_max_length}`);
  }

  // Embed checkpoints (embedders.json — the /embed registry). These are NOT pipeline
  // heads: embed.js runs a raw ONNX session on last_hidden_state with the checkpoint's
  // own tokenizer.json, so the bake fetches exactly those pinned files directly rather
  // than instantiating a task pipeline that may not exist for the architecture. The
  // revision pin is enforced by the resolve URL — what got baked is what serves.
  //
  // OPT-IN (BAKE_EMBEDDERS=1). The conformance encoder is ~1.7 GB of fp32 weights and the
  // service is OOM-killed on the 4 GB production task with it resident, so the default
  // build produces the same weights layer it always did and /embed refuses the checkpoint
  // with a 400 (embed.js). Dev compose turns it on; production turns it on only after the
  // ECS task is resized. The manifest is still VALIDATED either way — a bad pin fails the
  // build whether or not this build is the one that downloads it.
  const bakeEmbedders = process.env.BAKE_EMBEDDERS === '1';
  for (const [checkpoint, spec] of Object.entries(EMBEDDERS)) {
    if (spec.dtype !== 'fp32') throw new Error(`embed checkpoint '${checkpoint}' must pin dtype fp32 (the fit contract)`);
    if (!bakeEmbedders) {
      console.log(`skipping embed checkpoint '${checkpoint}' (BAKE_EMBEDDERS is not 1) — /embed will 400 for it`);
      continue;
    }
    const started = Date.now();
    const target = path.join(TARGET, spec.model);
    await fetchPinned(spec, 'tokenizer.json', path.join(target, 'tokenizer.json'), true);
    await fetchPinned(spec, 'tokenizer_config.json', path.join(target, 'tokenizer_config.json'), false);
    await fetchPinned(spec, 'onnx/model.onnx', path.join(target, 'onnx', 'model.onnx'), true);
    console.log(`downloaded embed checkpoint '${checkpoint}' (${spec.model}@${spec.revision}) in ${Date.now() - started}ms`);
  }

  fs.rmSync(STAGING, { recursive: true, force: true });
  console.log('assembled local model layout under', TARGET);
})().catch((e) => {
  console.error('download failed:', e);
  process.exit(1);
});
