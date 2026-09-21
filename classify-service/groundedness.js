// SPDX-License-Identifier: Apache-2.0
'use strict';
/**
 * Groundedness v2 — a long-context TOKEN head, served as one forward pass per response.
 *
 * WHAT CHANGED FROM THE PAIR HEAD. The previous `groundedness` scored (premise-window, claim) pairs
 * with a three-way NLI encoder and reduced across windows. Measured on human-labelled RAG output
 * (RAGTruth) that design topped out at 0.04-0.15 recall at a 2% false-alarm rate, and so did every
 * off-the-shelf NLI checkpoint — see classifiers/groundedness/README.md, "External validity". The
 * head here reads the whole evidence and the whole answer in ONE 8,192-token pass and labels every
 * answer token O / BASELESS / CONFLICT. It is ModernBERT-large fine-tuned from the published
 * lettucedetect checkpoint on RAGTruth plus Tessary's own verified corpora; 0.56 sentence recall at
 * 2% FP on RAGTruth, equal to the best published model, and 0.63 on Tessary's own domain.
 *
 * THE CONTRACT CHANGED WITH IT. `unsupported` is now P(BASELESS) + P(CONFLICT): "anything here the
 * evidence does not support", which is what the field means by hallucination detection and what the
 * human annotators labelled. `conflict` is exposed separately for a consumer that wants the old,
 * narrower question. A true fact from a tool call that was not passed as evidence IS unsupported by
 * this definition — the fix is to hand tool output in as a passage, not to exempt it.
 *
 * INPUT FORMAT IS PART OF THE MODEL. The context side is laid out exactly as the checkpoint was
 * trained (its provenance records `template: lettuce`): numbered passages under a fixed instruction,
 * with the question when there is one. Serving with a different layout is a silent accuracy loss,
 * which is why the layout lives here next to the model and the caller sends passages, not a string.
 *
 * TOKENISATION IS DONE BY HAND. transformers.js has no `truncation: only_first`, and Python training
 * truncated the CONTEXT side to fit the answer. So this encodes context and answer separately with no
 * special tokens, cuts the context to the budget, and assembles `[CLS] ctx [SEP] ans [SEP]` from the
 * tokenizer's own template — pinned by groundedness.test.js against ids Python produced. The answer
 * is tokenised sentence by sentence so each sentence's token range is known without offset mappings
 * (which transformers.js does not return); Python's sentence splitter is mirrored here.
 */

const MAX_LENGTH = 8192;
const SPECIALS = 3; // [CLS] ctx [SEP] ans [SEP]
const MIN_SENT_CHARS = 20; // classifiers/groundedness/ragtruth_pairs.MIN_SENT_CHARS
const LABEL = { O: 0, BASELESS: 1, CONFLICT: 2 }; // classifiers/groundedness/token_data.LABELS
const MAX_RESPONSES = Number(process.env.GROUNDEDNESS_MAX_RESPONSES || 16);
const MAX_PASSAGE_CHARS = Number(process.env.GROUNDEDNESS_MAX_PASSAGE_CHARS || 20000);
const MAX_ANSWER_CHARS = Number(process.env.GROUNDEDNESS_MAX_ANSWER_CHARS || 8000);

// classifiers/groundedness/token_data.lettuce_prompt — byte for byte.
function contextPrompt(passages, question) {
  const ctx = passages.map((p, i) => `passage ${i + 1}: ${p}`).join('\n');
  if (question) {
    return (
      `Briefly answer the following question:\n${question}\n` +
      `Bear in mind that your response should be strictly based on the following ${passages.length} passages:\n` +
      `${ctx}\nIn case the passages do not contain the necessary information to answer the question, ` +
      `please reply with: "Unable to answer based on given passages."\noutput:`
    );
  }
  return `Summarize the following text:\n${ctx}\noutput:`;
}

// classifiers/groundedness/ragtruth_pairs._sentences: split after .!? followed by whitespace and an
// upper-case letter, digit, quote or bracket. Returns [start, end, text] with offsets into `text`.
const SENT_RE = /(?<=[.!?])\s+(?=[A-Z0-9"'(])/g;
function sentences(text) {
  const out = [];
  let pos = 0;
  for (const m of text.matchAll(SENT_RE)) {
    out.push([pos, m.index, text.slice(pos, m.index)]);
    pos = m.index + m[0].length;
  }
  out.push([pos, text.length, text.slice(pos)]);
  return out;
}

function validate(payload) {
  const { responses, texts, pairs } = payload;
  if (texts !== undefined || pairs !== undefined) {
    throw new Error("head 'groundedness' takes responses: [{passages[], question?, answer}] — not texts or pairs");
  }
  const ok =
    Array.isArray(responses) &&
    responses.length > 0 &&
    responses.every(
      (r) =>
        r &&
        Array.isArray(r.passages) &&
        r.passages.length > 0 &&
        r.passages.every((p) => typeof p === 'string') &&
        typeof r.answer === 'string' &&
        (r.question === undefined || r.question === null || typeof r.question === 'string')
    );
  if (!ok) throw new Error('groundedness responses must be a non-empty array of {passages: string[], question?: string, answer: string}');
  if (responses.length > MAX_RESPONSES) throw new Error(`groundedness batch exceeds ${MAX_RESPONSES} responses`);
  return responses;
}

/**
 * Build one encoder input. `encode(text)` must return token ids with NO special tokens.
 * Returns { ids, answerStart, sentenceRanges: [[start, end, tokFrom, tokTo]] } where tokFrom/tokTo
 * index into `ids` (tokTo exclusive) and start/end are char offsets into `answer`.
 */
function assemble(encode, template, passages, question, answer, isWhitespaceToken = () => false) {
  const { cls, sep } = template;
  const ctxIds = encode(contextPrompt(passages, question));
  const sents = sentences(answer);
  // Tokenise the answer sentence by sentence, each with the whitespace that preceded it, so the
  // concatenation is the same BPE stream Python gets from the whole string (a leading space is what
  // gives ModernBERT's BPE its 'Ġ' word-start tokens) while each sentence's token range is known.
  const ansIds = [];
  const ranges = [];
  let prevEnd = 0;
  for (const [start, end, text] of sents) {
    const piece = answer.slice(prevEnd, end); // includes the whitespace before this sentence
    const ids = encode(piece);
    // A space merges into the next word's token ('ĠWord'), but a newline is its own token: Python's
    // offset mapping puts that token in no sentence, so the range starts after any leading
    // whitespace-only tokens.
    let lead = 0;
    while (lead < ids.length && isWhitespaceToken(ids[lead])) lead++;
    ranges.push([start, end, ansIds.length + lead, ansIds.length + ids.length, text]);
    ansIds.push(...ids);
    prevEnd = end;
  }
  const budget = MAX_LENGTH - SPECIALS - ansIds.length;
  // A 400, like classify.js's other request-shaped refusals: the backend must treat it as the
  // caller's problem and move on, not retry the whole chunk on every heartbeat.
  if (budget < 1) {
    throw Object.assign(new Error(`groundedness answer too long to score (${ansIds.length} tokens)`), { statusCode: 400 });
  }
  const ctx = ctxIds.length > budget ? ctxIds.slice(0, budget) : ctxIds; // only_first truncation
  const ids = [cls, ...ctx, sep, ...ansIds, sep];
  const answerStart = 1 + ctx.length + 1;
  return { ids, answerStart, sentenceRanges: ranges };
}

/**
 * Reduce per-token class probabilities to the response's scores.
 * `probs[i]` = [pO, pBASELESS, pCONFLICT] for answer token i (answer tokens only, in order).
 */
function reduce(probs, sentenceRanges) {
  const unsupportedOf = (p) => p[LABEL.BASELESS] + p[LABEL.CONFLICT];
  let unsupported = 0;
  let conflict = 0;
  const spans = [];
  for (const [start, end, from, to, text] of sentenceRanges) {
    if (text.trim().length < MIN_SENT_CHARS) continue;
    let u = 0;
    let c = 0;
    for (let i = from; i < to && i < probs.length; i++) {
      u = Math.max(u, unsupportedOf(probs[i]));
      c = Math.max(c, probs[i][LABEL.CONFLICT]);
    }
    spans.push({ start, end, unsupported: u, conflict: c });
    unsupported = Math.max(unsupported, u);
    conflict = Math.max(conflict, c);
  }
  return { unsupported, conflict, spans };
}

/**
 * The [CLS]/[SEP] ids, read off the tokenizer's own single-sequence template: encoding the empty
 * string with special tokens yields exactly `[CLS] [SEP]` for a BERT-style post-processor, which is
 * what ModernBERT's tokenizer.json declares. Asserted rather than assumed, because the pair layout
 * below re-creates that template by hand.
 */
function specialTemplate(tokenizer) {
  const ids = tokenizer.encode('', { add_special_tokens: true });
  if (ids.length !== 2) throw new Error(`groundedness tokenizer template is not [CLS] x [SEP] (got ${ids.length} specials)`);
  return { cls: ids[0], sep: ids[1] };
}

function softmax(row) {
  const m = Math.max(...row);
  const e = row.map((v) => Math.exp(v - m));
  const s = e.reduce((a, b) => a + b, 0);
  return e.map((v) => v / s);
}

/**
 * Score a batch of responses with a loaded {tokenizer, model} (transformers.js). One forward pass per
 * response; batching across responses is deliberately not done — sequences of very different lengths
 * would pad to the longest and ModernBERT attention is O(n) in padded length per token.
 */
async function scoreResponses(loaded, responses) {
  const { tokenizer, model, tf } = loaded;
  const template = specialTemplate(tokenizer);
  const encode = (text) => tokenizer.encode(text, { add_special_tokens: false });
  const isWs = (id) => tokenizer.decode([id]).trim() === '';
  const out = [];
  for (const r of responses) {
    const passages = r.passages.map((p) => (p.length > MAX_PASSAGE_CHARS ? p.slice(0, MAX_PASSAGE_CHARS) : p));
    const answer = r.answer.length > MAX_ANSWER_CHARS ? r.answer.slice(0, MAX_ANSWER_CHARS) : r.answer;
    const { ids, answerStart, sentenceRanges } = assemble(encode, template, passages, r.question || null, answer, isWs);
    const n = ids.length;
    const input_ids = new tf.Tensor('int64', BigInt64Array.from(ids.map((i) => BigInt(i))), [1, n]);
    const attention_mask = new tf.Tensor('int64', new BigInt64Array(n).fill(1n), [1, n]);
    const { logits } = await model({ input_ids, attention_mask });
    const [, , k] = logits.dims;
    const data = logits.data;
    const probs = [];
    for (let t = answerStart; t < n - 1; t++) {
      probs.push(softmax(Array.from(data.subarray(t * k, t * k + k))));
    }
    out.push(reduce(probs, sentenceRanges));
  }
  return { scores: out };
}

module.exports = {
  contextPrompt, sentences, validate, assemble, reduce, softmax, scoreResponses, specialTemplate,
  MAX_LENGTH, MIN_SENT_CHARS, LABEL,
};
