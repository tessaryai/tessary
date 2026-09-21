// SPDX-License-Identifier: Apache-2.0
'use strict';
/**
 * Parity harness for classifiers/tests/test_groundedness_token_head.py — NOT a serving path.
 *
 * Reads {model_dir, responses: [{passages, question, answer}]} on stdin, loads the checkpoint's
 * tokenizer through transformers.js exactly as classify.js does (local files only), and prints the
 * encoder input groundedness.js would build for each response: the token ids and the per-sentence
 * token ranges. The Python side builds the same thing with the HF tokenizer (`truncation="only_first"`)
 * and the same sentence splitter, and the two must agree id for id — that is what makes the served
 * model the evaluated model.
 */
const path = require('node:path');
const g = require('./groundedness');

async function main() {
  const input = JSON.parse(await new Promise((res) => {
    let buf = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (d) => (buf += d));
    process.stdin.on('end', () => res(buf));
  }));
  const tf = await import('@huggingface/transformers');
  tf.env.localModelPath = path.dirname(path.resolve(input.model_dir));
  tf.env.allowRemoteModels = false;
  const tokenizer = await tf.AutoTokenizer.from_pretrained(path.basename(path.resolve(input.model_dir)));
  const template = g.specialTemplate(tokenizer);
  const encode = (text) => tokenizer.encode(text, { add_special_tokens: false });
  const isWs = (id) => tokenizer.decode([id]).trim() === '';
  const out = input.responses.map((r) => {
    const { ids, answerStart, sentenceRanges } = g.assemble(encode, template, r.passages, r.question || null, r.answer, isWs);
    return { ids, answerStart, sentenceRanges: sentenceRanges.map((x) => x.slice(0, 4)) };
  });
  process.stdout.write(JSON.stringify(out));
}

main().catch((e) => {
  process.stderr.write(String(e && e.stack ? e.stack : e));
  process.exit(1);
});
