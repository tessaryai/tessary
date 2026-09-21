// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Unit tests for windowsFor — the cost-bounding math for sliding-window scoring (node:test, no
 * model download). Run by scripts/check-classify-service.sh. Tests pass explicit opts so they don't
 * depend on env-configured module defaults.
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { windowsFor, premiseChunksFor, deBlob, classify, headResidency, reduceWindows, reducerFor } = require('./classify');

const OPTS = { windowChars: 100, overlap: 20, maxWindows: 4 };

// Which edition is this checkout? models.json binds each head to a pinned checkpoint. Read
// directly rather than via classify.js so this is a statement about the FILE, not about the
// registry classify.js derives from it.
// The open manifest binds exactly the PUBLIC heads (groundedness, since 2026-09-21); an overlay
// manifest may bind private heads as well. scripts/check-classify-service.sh is the
// exact-set gate; this switch only needs to tell the two shapes apart.
const PUBLIC_HEADS = ['groundedness'];
const MANIFEST = require('./models.json');
const OPEN_EDITION = Object.keys(MANIFEST).every((head) => PUBLIC_HEADS.includes(head));

test('a short text is a single window (head-only behavior, no cost)', () => {
  const w = windowsFor('short text', OPTS);
  assert.deepEqual(w, ['short text']);
});

test('text exactly one window wide stays a single window', () => {
  const t = 'x'.repeat(100);
  assert.deepEqual(windowsFor(t, OPTS), [t]);
});

test('a slightly-long text tiles into overlapping windows covering head and tail', () => {
  const t = 'x'.repeat(150); // > 100, needs 2 windows
  const w = windowsFor(t, OPTS);
  assert.ok(w.length >= 2);
  assert.equal(w[0], t.slice(0, 100), 'first window covers the head');
  assert.equal(w[w.length - 1], t.slice(50, 150), 'last window ends at the tail');
  assert.ok(w.every((s) => s.length <= 100), 'no window exceeds the encoder window');
});

test('a very long text is capped at maxWindows, including first and last', () => {
  const t = 'abcdefghij'.repeat(500); // 5000 chars — far more than 4 windows would tile
  const w = windowsFor(t, OPTS);
  assert.ok(w.length <= OPTS.maxWindows, `at most ${OPTS.maxWindows} windows, got ${w.length}`);
  assert.equal(w[0], t.slice(0, 100), 'head is always scored');
  assert.equal(w[w.length - 1], t.slice(t.length - 100), 'tail is always scored (the end-refusal case)');
  assert.ok(w.every((s) => s.length <= 100));
});

test('maxWindows=1 degrades to head-only', () => {
  const t = 'y'.repeat(1000);
  assert.deepEqual(windowsFor(t, { ...OPTS, maxWindows: 1 }), [t.slice(0, 100)]);
});

test('tail coverage even when stride does not land on the end', () => {
  const t = 'z'.repeat(230); // stride 80: starts 0,80,160 -> 160+100=260>=230 stops; tail start=130
  const w = windowsFor(t, OPTS);
  assert.equal(w[w.length - 1], t.slice(130, 230), 'an explicit tail window is appended');
});

// premiseChunksFor — the pair-head (groundedness) chunking budget: the premise is windowed,
// leaving room for a fixed-length claim so the claim itself is never the part that gets truncated.
const PAIR_OPTS = { totalChars: 100, overlap: 20, maxChunks: 4 };

test('a short premise with a short claim is a single chunk', () => {
  const chunks = premiseChunksFor('short premise', 10, PAIR_OPTS);
  assert.deepEqual(chunks, ['short premise']);
});

test('a long premise is windowed to leave room for the claim', () => {
  const premise = 'p'.repeat(150);
  const chunks = premiseChunksFor(premise, 20, PAIR_OPTS); // 100-20=80 chars per chunk
  assert.ok(chunks.every((c) => c.length <= 80), 'every chunk leaves room for the claim');
  assert.ok(chunks.length >= 2);
});

test('a claim that eats most of the budget still leaves a non-empty premise chunk (floor)', () => {
  const premise = 'p'.repeat(150);
  const chunks = premiseChunksFor(premise, 500, PAIR_OPTS); // claim alone exceeds totalChars
  assert.ok(chunks.every((c) => c.length >= 50), 'the 50-char floor holds even when the claim is huge');
});

// reduceWindows / reducerFor — how per-window scores collapse to one score per input. The
// reduction has to agree with what a head's score() MEANS, and when it doesn't, nothing fails: the
// head just goes quiet. That is exactly what happened to `groundedness` when it swapped from
// MiniCheck (binary support) to bart-large-mnli (`1 - P(contradiction)`) and kept the shared MAX.
test('reduceWindows groups windows back to one score per input', () => {
  // three windows: two belong to input 0, one to input 1
  const scores = reduceWindows([0.1, 0.9, 0.4], [0, 0, 1], 2, (b) => Math.max(...b));
  assert.deepEqual(scores, [0.9, 0.4]);
});

test('an unregistered head falls back to MAX rather than throwing', () => {
  assert.equal(reducerFor('some-future-head')([0.2, 0.95]), 0.95);
});

// classify() request-shape validation for pair heads (groundedness) — no model load needed, these
// all throw before pipelineFor() is reached.
// `groundedness` is a TOKEN head since 2026-09-18 (groundedness.js): it takes `responses`, and the
// pair-shape contract below is pinned through it because it is the one head that used to be a pair.
test('a token head rejects texts- and pairs-shaped requests', async () => {
  await assert.rejects(() => classify({ head: 'groundedness', texts: ['x'] }), /takes responses/);
  await assert.rejects(() => classify({ head: 'groundedness', pairs: [{ premise: 'a', claim: 'b' }] }), /takes responses/);
});

test('a token head rejects a malformed responses array', async () => {
  await assert.rejects(() => classify({ head: 'groundedness', responses: [{ passages: ['p'] }] }), /non-empty array/);
  await assert.rejects(() => classify({ head: 'groundedness', responses: [] }), /non-empty array/);
});

// deBlob — the unbroken-run breaker that keeps SentencePiece/Unigram tokenization off a single
// multi-KB blob (base64, minified code). Shared by classifyTexts AND classifyPairs (premise +
// claim) — regression coverage for the pair path having once omitted this.
test('deBlob inserts a space after every 256-char unbroken run', () => {
  const blob = 'a'.repeat(300);
  const out = deBlob(blob);
  assert.ok(out.length > blob.length, 'a space was inserted');
  assert.ok(
    out.split(/\s/).every((run) => run.length <= 256),
    'no unbroken run over 256 chars survives',
  );
});

test('deBlob leaves ordinary prose untouched', () => {
  const prose = 'The quick brown fox jumps over the lazy dog, again and again.';
  assert.equal(deBlob(prose), prose);
});

// headResidency() from marker files alone. No model download needed — this only touches the
// filesystem marker check (residentModelDir), the same one embed.js's checkpointResidency() uses
// for /embed. Restores HF_CACHE_DIR in a `finally` so this test can't leak state into whatever runs
// after it in the same process.
test('headResidency reports the public head resident from marker files alone', () => {
  const { HEADS } = require('./classify');
  const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'classify-residency-'));
  const groundednessDir = path.join(fixtureRoot, ...HEADS.groundedness.model.split('/'));
  fs.mkdirSync(groundednessDir, { recursive: true });
  fs.writeFileSync(path.join(groundednessDir, 'tokenizer.json'), '{}');
  fs.mkdirSync(path.join(groundednessDir, 'onnx'), { recursive: true });
  fs.writeFileSync(path.join(groundednessDir, 'onnx', 'model.onnx'), '');
  fs.writeFileSync(path.join(groundednessDir, 'model.onnx'), '');

  const prevCacheDir = process.env.HF_CACHE_DIR;
  process.env.HF_CACHE_DIR = fixtureRoot;
  try {
    const { resident, missing } = headResidency();
    assert.deepEqual(resident.sort(), ['groundedness']);
    assert.deepEqual(missing, []);
  } finally {
    if (prevCacheDir === undefined) delete process.env.HF_CACHE_DIR;
    else process.env.HF_CACHE_DIR = prevCacheDir;
    fs.rmSync(fixtureRoot, { recursive: true, force: true });
  }
});

// This build's counterpart to the test above. With the open models.json every scorer in
// classify.js that the manifest does not name is UNBACKED: still resolvable by name — the
// backend's BuiltInClassifierCatalog / EncoderScorer ask for these heads by name and must not be
// told they are unknown — but unservable, and saying so in a way a caller can tell apart from a
// serving failure. The public heads are backed in both editions. Runs whether or not an overlay
// manifest is present: when it is, this test asserts the opposite, that no head is unbacked, so a
// manifest entry silently disappearing can never pass unnoticed.
//
// UNAVAILABLE_IN_OPEN_EDITION is asserted as a LITERAL here on purpose. It is a cross-boundary
// contract, not prose: scripts/check-classify-service.sh branches on that exact spelling, so a
// reword is a breaking change and this assertion is what makes that visible at the source.
test('an unbacked head resolves by name and reports UNAVAILABLE_IN_OPEN_EDITION rather than throwing at load', async () => {
  // Head names are the registry's, not a hard-coded list — the exact-set assertion belongs to
  // check-classify-service.sh, and duplicating it here would just double the edit cost of a
  // legitimately-added head.
  const { HEADS, PAIR_HEADS, TOKEN_HEADS } = require('./classify');
  for (const [head, spec] of Object.entries(HEADS)) {
    if (!OPEN_EDITION) {
      assert.ok(!spec.unbacked, `paid edition: head '${head}' should have a models.json entry`);
      continue;
    }
    if (PUBLIC_HEADS.includes(head)) {
      assert.ok(!spec.unbacked, `open edition: public head '${head}' should have a models.json entry`);
      continue;
    }
    assert.ok(spec.unbacked, `open edition: head '${head}' should be unbacked`);
    // Send each head its OWN request shape: the edition error is raised after shape validation
    // (deliberately — see requireResident's comment), so a pair head sent `texts` would fail the
    // shape check first and never reach the assertion this test exists to make.
    const payload = TOKEN_HEADS.has(head)
      ? { head, responses: [{ passages: ['p'], answer: 'a' }] }
      : PAIR_HEADS.has(head)
        ? { head, pairs: [{ premise: 'p', claim: 'c' }] }
        : { head, texts: ['x'] };
    await assert.rejects(
      () => classify(payload),
      (e) => e.statusCode === 400 && e.message.startsWith('UNAVAILABLE_IN_OPEN_EDITION:'),
      `head '${head}'`,
    );
  }
  // An unbacked head is neither baked nor missing-a-bake: warmup.js fails the build on a
  // non-gated head reported `missing`, so headResidency must report it as neither. The public
  // heads are one or the other, depending on whether this checkout has baked weights on disk.
  if (OPEN_EDITION) {
    const { resident, missing } = headResidency();
    assert.deepEqual([...resident, ...missing].sort(), [...PUBLIC_HEADS].sort());
  }
});

// A head name nothing scores is still a caller bug, in either edition. This is the line the
// UNAVAILABLE_IN_OPEN_EDITION arm must not blur: "unknown" means fix your request, "unavailable"
// means this build cannot serve it however correct your request is.
test('an unknown head is still an unknown head, not an edition problem', async () => {
  await assert.rejects(
    () => classify({ head: 'no-such-head', texts: ['x'] }),
    (e) => /unknown classify head/.test(e.message) && !/UNAVAILABLE_IN_OPEN_EDITION/.test(e.message),
  );
});
