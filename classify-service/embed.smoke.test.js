// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * OPT-IN end-to-end smoke test for the real /embed path — the checkpoint's own
 * tokenizer.json, a real ONNX session and the pinned pooling together against a REAL
 * model, mirroring the backend's ConformanceEncoderSmokeTest. Skipped unless
 * CONFORMANCE_ENCODER_MODEL_DIR points at a directory holding tokenizer.json
 * (+ tokenizer_config.json) and model.onnx (or onnx/model.onnx) for the checkpoint the
 * backend parity fixture's `encoder_smoke` block names (the silver bundle's
 * Alibaba-NLP/gte-large-en-v1.5), e.g.:
 *
 *   hf download Alibaba-NLP/gte-large-en-v1.5 tokenizer.json tokenizer_config.json \
 *     onnx/model.onnx --local-dir /tmp/gte
 *   pnpm install   # NOT --ignore-scripts: onnxruntime-node needs its native binaries
 *   CONFORMANCE_ENCODER_MODEL_DIR=/tmp/gte node --test embed.smoke.test.js
 *
 * Three fixed sentences are embedded and compared by cosine (>= 0.999) against
 * reference vectors the Python engine's own encoder computed into the fixture — the
 * loose bound is deliberate: ONNX export and torch legitimately differ at float32
 * noise level, while a wrong pooling, a wrong truncation bound, or a mismatched
 * tokenizer all crater the cosine. The exact-arithmetic pin for the reimplemented half
 * lives in embed.test.js; this test exists to catch the delegated half being wired to
 * the wrong contract.
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const MODEL_DIR = process.env.CONFORMANCE_ENCODER_MODEL_DIR || '';
const MIN_COSINE = 0.999;
const FIXTURE = path.join(__dirname, '..', 'backend', 'analysis', 'src', 'test', 'resources', 'conformance_parity.json');

function cosine(a, b) {
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (let j = 0; j < a.length; j++) {
    dot += a[j] * b[j];
    na += a[j] * a[j];
    nb += b[j] * b[j];
  }
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

test(
  'real model embeddings land on the Python reference vectors',
  { skip: MODEL_DIR === '' ? 'set CONFORMANCE_ENCODER_MODEL_DIR to run against a real model' : false },
  async () => {
    const { embedWithDir } = require('./embed');
    const smoke = JSON.parse(fs.readFileSync(FIXTURE, 'utf8')).encoder_smoke;
    assert.ok(smoke && Array.isArray(smoke.sentences), 'fixture carries an encoder_smoke block');
    assert.equal(smoke.sentences.length, 3, 'the smoke block carries the three fixed sentences');

    const vectors = await embedWithDir(MODEL_DIR, smoke.sentences);
    assert.equal(vectors.length, smoke.expected_embeddings.length, 'one embedding per sentence');
    for (let i = 0; i < vectors.length; i++) {
      const reference = smoke.expected_embeddings[i];
      assert.equal(vectors[i].length, reference.length, `embedding width for sentence ${i}`);
      const c = cosine(reference, vectors[i]);
      assert.ok(c >= MIN_COSINE, `sentence ${i} ("${smoke.sentences[i]}") cosine ${c} < ${MIN_COSINE}`);
    }
  },
);

// Truncation parity past the 256-token window (`truncation_smoke`): the fit side truncates
// CONTENT and keeps the closing special token; a finished-sequence slice (the transformers.js
// default this service corrects — see fitContractTruncate) lands a different final window and
// craters the cosine to 0.93-0.96. Two assertions per case: the exact HF input ids, and the
// pooled embedding against the engine's own (torch) reference at >= 0.999.
test(
  '>256-token texts truncate to the fit tokenization and land on the reference vectors',
  { skip: MODEL_DIR === '' ? 'set CONFORMANCE_ENCODER_MODEL_DIR to run against a real model' : false },
  async () => {
    const { embedWithDir, fitContractTruncate, specialTokenTemplate, MAX_TOKENS } = require('./embed');
    const smoke = JSON.parse(fs.readFileSync(FIXTURE, 'utf8')).truncation_smoke;
    assert.ok(smoke && Array.isArray(smoke.cases) && smoke.cases.length > 0, 'fixture carries a truncation_smoke block');
    assert.equal(smoke.max_tokens, MAX_TOKENS, 'the fixture pins the same window this service serves');

    const tf = await import('@huggingface/transformers');
    const tokenizerConfigPath = path.join(MODEL_DIR, 'tokenizer_config.json');
    const tokenizerConfig = fs.existsSync(tokenizerConfigPath)
      ? JSON.parse(fs.readFileSync(tokenizerConfigPath, 'utf8'))
      : {};
    const tokenizer = new tf.PreTrainedTokenizer(
      JSON.parse(fs.readFileSync(path.join(MODEL_DIR, 'tokenizer.json'), 'utf8')),
      tokenizerConfig,
    );
    const template = specialTokenTemplate(tokenizer);
    const side = tokenizerConfig.truncation_side === 'left' ? 'left' : 'right';

    for (const c of smoke.cases) {
      const content = Array.from(tokenizer(c.text, { add_special_tokens: false }).input_ids.data, Number);
      assert.equal(content.length, c.content_tokens, 'the case still tokenizes past the window');
      const ids = fitContractTruncate(content, template.prefix, template.suffix, MAX_TOKENS, side);
      assert.deepEqual(ids, c.expected_input_ids, 'truncated ids equal the HF tokenization');
    }

    const vectors = await embedWithDir(MODEL_DIR, smoke.cases.map((c) => c.text));
    for (let i = 0; i < vectors.length; i++) {
      const c = cosine(smoke.cases[i].expected_embedding, vectors[i]);
      assert.ok(c >= MIN_COSINE, `truncation case ${i} cosine ${c} < ${MIN_COSINE}`);
    }
  },
);
