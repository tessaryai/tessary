// SPDX-License-Identifier: Apache-2.0
'use strict';
// node --test groundedness.test.js — no weights, no network. Pins the request contract, the prompt
// layout the model was trained on, the sentence splitter, pair assembly with only-first truncation,
// and the reduction from token probabilities to response scores. Byte-level parity with the Python
// side (tokeniser ids, sentence ranges) is pinned separately by
// classifiers/tests/test_groundedness_token_head.py, which runs this module under node.
const test = require('node:test');
const assert = require('node:assert/strict');
const g = require('./groundedness');

test('validate: rejects the old pair/text shapes and malformed responses', () => {
  assert.throws(() => g.validate({ pairs: [{ premise: 'a', claim: 'b' }] }), /takes responses/);
  assert.throws(() => g.validate({ texts: ['a'] }), /takes responses/);
  assert.throws(() => g.validate({ responses: [] }), /non-empty/);
  assert.throws(() => g.validate({ responses: [{ passages: [], answer: 'x' }] }), /non-empty/);
  assert.throws(() => g.validate({ responses: [{ passages: ['p'], answer: 7 }] }), /non-empty/);
  assert.throws(() => g.validate({ responses: [{ passages: ['p'], answer: 'x', question: 3 }] }), /non-empty/);
  const ok = g.validate({ responses: [{ passages: ['p'], answer: 'x' }, { passages: ['p', 'q'], question: 'why', answer: 'y' }] });
  assert.equal(ok.length, 2);
});

test('contextPrompt: the exact training layout, QA and summary forms', () => {
  assert.equal(
    g.contextPrompt(['alpha', 'beta'], 'What?'),
    'Briefly answer the following question:\nWhat?\n' +
      'Bear in mind that your response should be strictly based on the following 2 passages:\n' +
      'passage 1: alpha\npassage 2: beta\n' +
      'In case the passages do not contain the necessary information to answer the question, ' +
      'please reply with: "Unable to answer based on given passages."\noutput:'
  );
  assert.equal(g.contextPrompt(['only'], null), 'Summarize the following text:\npassage 1: only\noutput:');
});

test('sentences: mirrors the Python splitter, offsets cover the text exactly', () => {
  const text = 'First one. Second (two)! third stays. Fourth "quoted". 5 numbers. Last';
  const s = g.sentences(text);
  assert.deepEqual(s.map((x) => x[2]), ['First one.', 'Second (two)! third stays.', 'Fourth "quoted".', '5 numbers.', 'Last']);
  for (const [start, end, t] of s) assert.equal(text.slice(start, end), t);
  assert.deepEqual(g.sentences('no terminator'), [[0, 13, 'no terminator']]);
});

// A fake tokeniser: one id per whitespace-delimited piece, so budgets are countable by hand.
const fakeEncode = (text) => text.split(/(\s+)/).filter((x) => x.length).map((x) => x.length);
const template = { cls: 101, sep: 102 };

test('assemble: [CLS] ctx [SEP] ans [SEP], answer ranges index into ids, context truncated first', () => {
  const { ids, answerStart, sentenceRanges } = g.assemble(fakeEncode, template, ['p'], null, 'Ab cd. Ef gh.');
  assert.equal(ids[0], 101);
  assert.equal(ids[answerStart - 1], 102);
  assert.equal(ids[ids.length - 1], 102);
  // two sentences: "Ab cd." -> 3 pieces, " Ef gh." -> 4 pieces (leading whitespace kept with the sentence)
  assert.deepEqual(sentenceRanges.map((r) => r.slice(0, 4)), [[0, 6, 0, 3], [7, 13, 3, 7]]);
  assert.equal(ids.length, answerStart + 7 + 1);
});

test('assemble: an over-long context is cut to the budget, the answer never is', () => {
  const longCtx = Array.from({ length: g.MAX_LENGTH }, (_, i) => `w${i}`).join(' ');
  const { ids, answerStart } = g.assemble(fakeEncode, template, [longCtx], null, 'Short answer here.');
  assert.equal(ids.length, g.MAX_LENGTH);
  assert.equal(ids[ids.length - 1], 102);
  assert.ok(answerStart < ids.length - 1);
  assert.throws(
    () => g.assemble(fakeEncode, template, ['p'], null, Array.from({ length: g.MAX_LENGTH }, () => 'x').join(' ')),
    /too long/
  );
});

test('reduce: response = max over sentences, sentence = max over its tokens, short sentences skipped', () => {
  const O = [1, 0, 0];
  const probs = [O, [0.2, 0.7, 0.1], O, [0.1, 0.1, 0.8], O, O];
  const ranges = [
    [0, 30, 0, 2, 'a sentence long enough to count'],
    [31, 60, 2, 4, 'another sentence that counts'],
    [61, 64, 4, 6, 'Ok.'],
  ];
  const r = g.reduce(probs, ranges);
  assert.equal(r.spans.length, 2);
  assert.ok(Math.abs(r.spans[0].unsupported - 0.8) < 1e-9 && Math.abs(r.spans[0].conflict - 0.1) < 1e-9);
  assert.ok(Math.abs(r.spans[1].unsupported - 0.9) < 1e-9 && Math.abs(r.spans[1].conflict - 0.8) < 1e-9);
  assert.ok(Math.abs(r.unsupported - 0.9) < 1e-9 && Math.abs(r.conflict - 0.8) < 1e-9);
});

test('softmax: sums to one and keeps order', () => {
  const p = g.softmax([1, 2, 3]);
  assert.ok(Math.abs(p.reduce((a, b) => a + b, 0) - 1) < 1e-9);
  assert.ok(p[2] > p[1] && p[1] > p[0]);
});

test('classify() routes groundedness to the token head and rejects pairs before any weights load', async () => {
  const { classify, TOKEN_HEADS, PAIR_HEADS } = require('./classify');
  assert.ok(TOKEN_HEADS.has('groundedness'));
  assert.ok(!PAIR_HEADS.has('groundedness'));
  await assert.rejects(classify({ head: 'groundedness', pairs: [{ premise: 'a', claim: 'b' }] }), /takes responses/);
  await assert.rejects(classify({ head: 'groundedness', responses: [{ passages: [], answer: 'x' }] }), /non-empty/);
});
