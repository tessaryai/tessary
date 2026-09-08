// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Unit tests for /embed's reimplemented arithmetic and request validation (node:test, no
 * model download). Run by scripts/check-classify-service.sh.
 *
 * The pooling half of the conformance fit contract is pinned against the SAME parity
 * fixture that pins the backend's Java twin (EmbeddingPooling): the engine-generated
 * `pooling_cases` in backend/analysis/src/test/resources/conformance_parity.json,
 * asserted component-wise at 1e-9 — synthetic last_hidden_state tensors with expected
 * embeddings computed by the Python engine's own numpy arithmetic, never hand-written.
 * One fixture, three implementations (Python engine, backend JVM, this service), zero
 * drift by construction.
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { embed, meanPoolNormalize, fitContractTruncate } = require('./embed');

const FIXTURE = path.join(__dirname, '..', 'backend', 'analysis', 'src', 'test', 'resources', 'conformance_parity.json');
const POOLING_TOLERANCE = 1e-9;

test('pooling_cases: mask mean-pool + L2-normalise lands on the engine fixture at 1e-9', () => {
  const fixture = JSON.parse(fs.readFileSync(FIXTURE, 'utf8'));
  const cases = fixture.pooling_cases;
  assert.ok(Array.isArray(cases) && cases.length > 0, 'fixture carries pooling_cases');
  for (const c of cases) {
    const actual = meanPoolNormalize(c.last_hidden_state, c.attention_mask);
    assert.equal(actual.length, c.expected_embedding.length, `${c.name}: embedding width`);
    for (let j = 0; j < actual.length; j++) {
      assert.ok(
        Math.abs(actual[j] - c.expected_embedding[j]) <= POOLING_TOLERANCE,
        `${c.name}[${j}]: ${actual[j]} != ${c.expected_embedding[j]} (tolerance ${POOLING_TOLERANCE})`,
      );
    }
  }
});

test('pooling rejects a mask/hidden-state length mismatch', () => {
  assert.throws(() => meanPoolNormalize([[1, 2]], [1, 0]), /token rows.*attention mask/);
});

test('pooling rejects an empty sequence', () => {
  assert.throws(() => meanPoolNormalize([], []), /empty sequence/);
});

test('pooling rejects ragged hidden states', () => {
  assert.throws(() => meanPoolNormalize([[1, 2], [1]], [1, 1]), /ragged/);
});

// The fit contract's truncation half: HF truncates the CONTENT to fit and keeps the
// closing special token ([CLS] + content[:max-2] + [SEP]); transformers.js slices the
// finished sequence, dropping [SEP] and keeping one extra content token. These pin the
// reconstruction at token-id level with sentinel special ids, no model needed.

test('fitContractTruncate keeps the template and truncates content on the right by default', () => {
  const content = Array.from({ length: 400 }, (_, i) => 1000 + i);
  const out = fitContractTruncate(content, [101], [102], 256, 'right');
  assert.equal(out.length, 256);
  assert.equal(out[0], 101);
  assert.equal(out[255], 102);
  assert.deepEqual(out.slice(1, 255), content.slice(0, 254));
});

test('fitContractTruncate truncates content on the left when the tokenizer says so', () => {
  const content = Array.from({ length: 300 }, (_, i) => 2000 + i);
  const out = fitContractTruncate(content, [101], [102], 256, 'left');
  assert.equal(out.length, 256);
  assert.deepEqual(out.slice(1, 255), content.slice(300 - 254));
  assert.equal(out[0], 101);
  assert.equal(out[255], 102);
});

test('fitContractTruncate leaves a fitting sequence whole', () => {
  const content = [7, 8, 9];
  assert.deepEqual(fitContractTruncate(content, [101], [102], 256, 'right'), [101, 7, 8, 9, 102]);
});

test('fitContractTruncate handles multi-token and empty templates', () => {
  const content = Array.from({ length: 10 }, (_, i) => i);
  assert.deepEqual(fitContractTruncate(content, [0, 1], [2, 3], 8, 'right'), [0, 1, 0, 1, 2, 3, 2, 3]);
  assert.deepEqual(fitContractTruncate(content, [], [], 4, 'right'), [0, 1, 2, 3]);
});

test('fitContractTruncate refuses a template wider than the budget', () => {
  assert.throws(() => fitContractTruncate([1], [0, 1, 2], [3, 4], 4, 'right'), /exceeds maxTokens/);
});

// Request validation — every rejection is a clear 400, never an attempted forward pass.
// (The manifest ships empty, so no test here can accidentally reach a model load.)

test('embed refuses a missing or empty checkpoint', async () => {
  await assert.rejects(embed({ texts: ['x'] }), (e) => e.statusCode === 400 && /checkpoint/.test(e.message));
  await assert.rejects(embed({ checkpoint: '', texts: ['x'] }), (e) => e.statusCode === 400);
});

test('embed refuses non-array / empty / non-string texts', async () => {
  for (const texts of [undefined, 'x', [], [1], ['ok', null]]) {
    await assert.rejects(embed({ checkpoint: 'any', texts }), (e) => e.statusCode === 400 && /texts/.test(e.message));
  }
});

test('embed refuses an oversized batch with a clear error instead of attempting it', async () => {
  const texts = new Array(100_000).fill('t'); // over any sane cap, whatever EMBED_MAX_TEXTS is set to
  await assert.rejects(
    embed({ checkpoint: 'any', texts }),
    (e) => e.statusCode === 400 && /exceeds the \d+-text cap/.test(e.message),
  );
});

test('embed refuses an unknown checkpoint loudly (no substitute encoder, ever)', async () => {
  await assert.rejects(
    embed({ checkpoint: 'sentence-transformers/not-in-manifest', texts: ['x'] }),
    (e) => e.statusCode === 400 && /unknown embed checkpoint/.test(e.message),
  );
});

test('embed treats inherited prototype properties as unknown checkpoints', async () => {
  await assert.rejects(embed({ checkpoint: 'constructor', texts: ['x'] }), (e) => e.statusCode === 400);
});
