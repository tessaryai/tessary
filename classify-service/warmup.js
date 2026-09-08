// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Build-time offline validation (Dockerfile stage 2 of the bake): score every head through
 * classify() — and embed one text through every BAKED embedders.json checkpoint — under
 * EXACTLY the runtime loading conditions (localModelPath layout, no hub access), so a file
 * download.js failed to assemble, or any runtime hub dependency creeping back in, fails
 * the build instead of the deployment.
 */
const { classify, HEADS, PAIR_HEADS, headResidency } = require('./classify');
const { embed, checkpointResidency } = require('./embed');

(async () => {
  // Only smoke-score heads whose weights actually got baked. A gated head (frustration,
  // attribution) with no HF_TOKEN at build time (#877) never downloaded — there's nothing
  // to score offline, and that's expected, not a build failure: unlike BAKE_EMBEDDERS
  // (a flag that can lie — "asked for it, didn't get it"), a gated head's presence or
  // absence IS the download outcome itself. download.js already throws loudly per-head if
  // a token WAS supplied and the fetch/merge genuinely failed, so there is no separate
  // claim to assert against here — a silently-half-baked gated head cannot happen.
  const { resident: residentHeads, missing: missingHeads } = headResidency();
  for (const head of residentHeads) {
    const started = Date.now();
    const payload = PAIR_HEADS.has(head)
      ? { head, pairs: [{ premise: 'warmup document', claim: 'warmup claim' }] }
      : { head, texts: ['warmup'] };
    const { scores } = await classify(payload);
    if (!Array.isArray(scores) || scores.length !== 1 || typeof scores[0] !== 'number' || Number.isNaN(scores[0])) {
      throw new Error(`head '${head}' warmup returned an invalid score: ${JSON.stringify(scores)}`);
    }
    console.log(`validated head '${head}' offline in ${Date.now() - started}ms`);
  }
  for (const head of missingHeads) {
    // A missing NON-gated head (groundedness) is not a supported "unavailable" state —
    // its weights are public and download.js always fetches them — so this is a real
    // build defect, not an opt-out; fail loud exactly as the pre-#877 unconditional loop
    // did, rather than silently skip it like a gated head.
    if (!HEADS[head].gated) {
      throw new Error(`head '${head}' has no baked weights and is not a gated head — the bake is broken`);
    }
    console.log(`skipped head '${head}': not baked into this image (gated, no HF_TOKEN at build time)`);
  }
  // Embed checkpoints are baked only when the image is built with BAKE_EMBEDDERS=1, so
  // this stage validates the ones that are actually present — and asserts, when the build
  // ASKED for them, that they all arrived. Without that second half an ON build whose
  // download silently produced nothing would ship a green image that 400s in production.
  const { resident, missing } = checkpointResidency();
  if (process.env.BAKE_EMBEDDERS === '1' && missing.length > 0) {
    throw new Error(`built with BAKE_EMBEDDERS=1 but these checkpoints have no weights: ${missing.join(', ')}`);
  }
  for (const checkpoint of missing) {
    console.log(`skipped embed checkpoint '${checkpoint}': not baked into this image (BAKE_EMBEDDERS is not 1)`);
  }
  for (const checkpoint of resident) {
    const started = Date.now();
    const { vectors, dim } = await embed({ checkpoint, texts: ['warmup'] });
    const v = vectors && vectors[0];
    // A unit vector is the contract (L2-normalised mean pool); a bake that produces
    // anything else — wrong file, wrong output, NaN weights — must fail the build.
    const norm = Array.isArray(v) ? Math.sqrt(v.reduce((s, x) => s + x * x, 0)) : NaN;
    if (!Array.isArray(v) || v.length !== dim || dim <= 0 || !v.every(Number.isFinite) || Math.abs(norm - 1) > 1e-6) {
      throw new Error(`embed checkpoint '${checkpoint}' warmup returned an invalid vector (dim ${dim}, norm ${norm})`);
    }
    console.log(`validated embed checkpoint '${checkpoint}' offline in ${Date.now() - started}ms (dim ${dim})`);
  }
})().catch((e) => {
  console.error('warmup failed:', e);
  process.exit(1);
});
