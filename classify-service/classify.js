// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * In-process encoder classification for the built-in signal heads — the single head
 * registry (model ids, pinned revisions, score mappings) for every environment. The heads
 * are small ONNX encoders scored on this service's own CPU via transformers.js. Pipelines
 * load lazily on first use and stay warm for the process lifetime; the image pre-bakes the
 * weights into HF_CACHE_DIR at build time (warmup.js), so no hub download ever happens at
 * runtime.
 *
 * Each head maps a model's per-label scores to ONE score in [0,1] — the probability-ish
 * "behavior present" number the backend thresholds into confidence bands. The registry is
 * fixed: these heads are the product's built-in semantic classifiers, best-in-class
 * off-the-shelf ONNX checkpoints.
 *
 * `groundedness` is a TOKEN head (whole-response scoring, see groundedness.js). The pair and
 * single-text paths stay for any future head of those shapes; none is registered today.
 *
 * Licenses: groundedness (tessaryai/groundedness-token-v1) MIT.
 * (2026-07-16: refusal/jailbreak/unsafe_text were retired from the default catalog and
 * decommissioned here — their non-permissive licenses are moot now that nothing serves them.
 * The frustration and attribution heads went the same way when frustration moved to a
 * decision model the backend calls directly.)
 */

// node:fs / node:path — only for residentModelDir/headResidency below (mirrors embed.js's
// own residentModelDir precedent), everything else in this file is pure scoring logic.
const fs = require('node:fs');
const path = require('node:path');

const HEAD_MAX_TEXTS = 512;

// Clamp each text before tokenization. The models see at most 512 tokens (~2K chars of
// dense prose); everything past this bound is guaranteed-discarded by the tokenizer's
// own truncation, so slicing is output-lossless. What it prevents: tokenizing megabyte
// texts at all — BPE/SentencePiece work scales super-linearly on long UNBROKEN runs
// (base64 blobs, minified code in real traces), which burns CPU and spikes memory
// before truncation ever applies.
const MAX_TEXT_CHARS = Number(process.env.MAX_TEXT_CHARS || 4096);

// Micro-batch size for a single forward pass. Activation memory scales with
// batch × sequence-length² — 100 window-filling texts through an fp32 base model in one
// pass is a multi-GB tensor spike (OOM-killed the first production task on 2026-07-12,
// reproduced locally at a 4 GB cap). Sub-batching bounds the peak regardless of request
// shape. fp32 per-text scores are batch-independent (attention masks make padding
// inert); the q8 heads use dynamic quantization, whose activation scales depend on the
// batch's value range, so scores there can shift by a few hundredths with batch
// composition — real, measured (0.718 paired vs 0.690 solo on one q8 head), and why
// BuiltInSignalCatalog thresholds need margin, but never enough to flip a confident
// verdict.
const INFER_BATCH = Number(process.env.INFER_BATCH || 8);

// Sliding-window scoring (behind WINDOW_SCORING, default off). Single-text encoder heads see only
// ~512 tokens, so behavior buried past the head of a long turn is otherwise invisible. When
// enabled, a long text is scored in overlapping char windows sized to
// fit the encoder window, and the head's score is the MAX across windows (behavior present
// anywhere fires). Cost is bounded: at most MAX_WINDOWS forward passes per text — the windows are
// chosen to include the head AND tail — and every window flows through the SAME INFER_BATCH
// micro-batching, so peak activation memory is unchanged (more CPU-time, not more peak memory; the
// 2026-07-12 OOM was a peak-memory event). Scores can only rise vs. head-only, so this ships
// opt-in and is validated against the eval harness before it's turned on anywhere.
const WINDOW_SCORING = /^(1|true)$/i.test(process.env.WINDOW_SCORING || '');
const WINDOW_CHARS = Number(process.env.WINDOW_CHARS || 1800); // ~<=512 tokens of dense prose
const WINDOW_OVERLAP = Number(process.env.WINDOW_OVERLAP || 200); // so boundary-straddling behavior isn't split
const MAX_WINDOWS = Number(process.env.MAX_WINDOWS || 4); // the N× cost cap: windows scored per text
const WINDOW_MAX_CHARS = Number(process.env.WINDOW_MAX_CHARS || 20000); // hard input bound when windowing

// Break unbroken runs before tokenization: SentencePiece/Unigram (deberta) builds a candidate
// lattice over substrings, and a SINGLE 4K-char unbroken run spikes multiple GB (measured: 8
// clamped blobs OOM-killed a 6 GB container through the (now-decommissioned) jailbreak head). No
// natural language has 256-char words — only blobs (base64, minified code) are touched, and their
// scores are meaningless either way. Shared by every tokenization path (single-text AND pair-head
// premise/claim) — anything that reaches the tokenizer needs this, not just classifyTexts.
function deBlob(text) {
  return text.replace(/(\S{256})(?=\S)/g, '$1 ');
}

// Model specs (ids, pinned revisions, dtype) live in models.json — the ONLY input to the
// image's model-bake stage, so editing scoring code here never invalidates the ~1.3 GB
// weights layer. Every head pins `revision` to an exact hub commit SHA (supply chain): a
// deploy must serve the smoke-tested weights (the thresholds in BuiltInSignalCatalog are
// tuned to these exact checkpoints), not whatever upstream main points at.
//
// score() receives a Map<label, score> for one text (softmax for single-label models,
// sigmoid per label for multi-label ones — transformers.js applies the right activation
// from the model config's problem_type).
const MODELS = require('./models.json');

const SCORERS = {
  // bart-large-mnli (MIT), THREE-WAY NLI: {contradiction, neutral, entailment} softmax over a
  // joined `document </s> claim` input. A PAIR head (see PAIR_HEADS below): it scores a
  // (premise, claim) relationship rather than one string in isolation.
  //
  // WHY NOT MiniCheck ANY MORE. MiniCheck is BINARY — config id2label {'0','1'}, trained to answer
  // "is this claim supported, yes or no" — so "the document does not mention it" collapses into NO.
  // That single collapse produced every false positive we have measured: on zipeats it fired 474
  // times, ~28% of swept observations, on answers like "you're entitled to a full refund of $24.74"
  // premised against a generic refunds leaflet. The claim is TRUE and came from a tool call; the
  // leaflet can neither confirm nor deny it. Measured on 40 labelled claims, MiniCheck scored those
  // tool-derived facts at 0.006 support — BELOW outright fabrications at 0.064 — so no threshold
  // could ever separate them.
  //
  // Returning 1 - P(contradiction) means the detector's `unsupported = 1 - support` becomes exactly
  // P(contradiction): it fires on what the document CONTRADICTS and stays quiet on what the document
  // merely does not mention. Neutral is the abstain, and it is the whole point of the swap.
  //
  // The cost is honest and bounded: a FABRICATED addition ("store credit comes with a 10% bonus")
  // is also neutral — input-identical to a true tool-derived fact, since only provenance separates
  // them and provenance is not in the premise. Measured 2/10 caught. MiniCheck appeared to catch
  // those, but only because it fired on everything that was not a verbatim restatement.
  //
  // Measured on the 40-claim set (bart-large-mnli): precision 1.000, recall 0.500 — 0 false fires
  // across 20 should-be-quiet claims, against ~28% false firing in production today.
  groundedness: (labels) => 1 - (labels.get('contradiction') ?? 0),
};

// How a head's per-window scores collapse to one score per input: MAX, "this behaviour is
// present if ANY window shows it", which is right for every support- or presence-shaped score.
// A head whose score means something else registers its own reducer here; none does today
// (the groundedness pair head that needed MIN-over-contradiction was replaced by the token head).
const REDUCERS = {};
const DEFAULT_REDUCER = (scores) => Math.max(...scores);
const reducerFor = (head) => REDUCERS[head] ?? DEFAULT_REDUCER;

// Heads that score a (premise, claim) RELATIONSHIP rather than one string in isolation. A pair
// head's /classify request carries `pairs: [{premise, claim}]` instead of `texts: [".."]` — the
// two shapes are mutually exclusive per head so a caller can't accidentally submit the wrong one.
// `groundedness` moved from the pair path to a long-context TOKEN head (groundedness.js) on
// 2026-09-18: one 8,192-token pass per response, contract "anything unsupported". PAIR_HEADS is
// kept (empty) so a future pair head has its path; nothing in this file assumes it is non-empty.
const PAIR_HEADS = new Set([]);
const TOKEN_HEADS = new Set(['groundedness']);

// Pair-input budgeting. The encoder still sees only ~512 tokens total, and the input is built as
// `premiseChunk + eos_token + claim` — so unlike single-text scoring, the CLAIM must never be the
// part that gets silently truncated (that's the exact content being judged). The premise is what
// gets chunked instead: split the premise into overlapping windows sized to leave room for the
// (bounded) claim, score the claim against every window, and reduce across windows with the head's
// own reducer (see REDUCERS above — `groundedness` reduces by MIN support, i.e. "contradicted if
// ANY window contradicts"). Claim length itself is clamped defensively (a claim longer than the clamp loses
// its tail to the shared tokenizer truncation the same way every other head's input already does
// — an accepted, pre-existing bound in this file, not a new one).
const PAIR_TOTAL_CHARS = 1800; // ~<=512 tokens of dense prose, same budget as WINDOW_CHARS
const PAIR_CLAIM_MAX_CHARS = Number(process.env.PAIR_CLAIM_MAX_CHARS || 1000);
const PAIR_OVERLAP = Number(process.env.PAIR_OVERLAP || 200);
const PAIR_MAX_CHUNKS = Number(process.env.PAIR_MAX_CHUNKS || 4);
const PAIR_MAX_PREMISE_CHARS = Number(process.env.PAIR_MAX_PREMISE_CHARS || 20000);

// Manifest and scorers must agree exactly — a head in one but not the other is a build
// mistake, caught here (and therefore by warmup.js at image build time).
const HEADS = Object.fromEntries(
  Object.keys(MODELS).map((head) => {
    if (!SCORERS[head]) throw new Error(`models.json head '${head}' has no scorer in classify.js`);
    return [head, { ...MODELS[head], score: SCORERS[head] }];
  }),
);
// The OTHER direction is not a build mistake any more. The scoring code above is open source,
// and the open edition's models.json binds only the heads whose weights are public
// (groundedness, since 2026-09-21); any private head's pin lives in an overlay's manifest. So a scorer with no manifest entry is a head this build knows BY NAME but
// cannot serve, and it
// is registered here as UNBACKED rather than thrown on: throwing at module load would take
// down the whole service (and warmup.js, which does nothing but `require` this file) for a
// condition that is the open edition's normal, expected shape.
//
// Registered rather than omitted, deliberately: the open backend still asks for these heads
// by name — BuiltInClassifierCatalog carries their thresholds and EncoderScorer calls
// them — so /classify must answer "I know this head, this edition cannot
// serve it" (UNAVAILABLE_IN_OPEN_EDITION, thrown by requireResident below) and NOT the
// generic `unknown classify head`, which reads as a caller bug and would send someone
// hunting a typo that isn't there.
//
// An unbacked spec carries no `model`/`revision`/`dtype` at all, only `score`, so every
// weights-facing path has to skip it: residentModelDir returns null for it, headResidency
// reports it as neither resident nor missing, and warmAll never loads it. See each.
for (const head of Object.keys(SCORERS)) {
  if (!MODELS[head]) HEADS[head] = { unbacked: true, score: SCORERS[head] };
}

// Is a head's weights layer actually resident under HF_CACHE_DIR? Mirrors embed.js's
// residentModelDir/checkpointResidency precedent exactly (same two marker files, same
// localModelPath root) so "is this on disk?" has one definition across /classify and
// /embed. A gated head (`"gated": true` in models.json) that skipped download.js's
// fetch (no HF_TOKEN at build time) never has this directory; a non-gated head
// (groundedness) is always expected to have it, and if it somehow
// doesn't, that's a real build defect — this is not a supported "unavailable" path for
// it, so callers below only special-case `spec.gated`, never every missing head.
function residentModelDir(spec) {
  // An unbacked head (open edition, empty manifest) names no checkpoint at all, so there is
  // no directory to look for — and `path.join(dir, undefined)` would throw a TypeError.
  if (!spec.model) return null;
  const dir = path.join(process.env.HF_CACHE_DIR || '/models', spec.model);
  const hasTokenizer = fs.existsSync(path.join(dir, 'tokenizer.json'));
  const hasOnnx = fs.existsSync(path.join(dir, 'model.onnx')) || fs.existsSync(path.join(dir, 'onnx', 'model.onnx'));
  return hasTokenizer && hasOnnx ? dir : null;
}

/**
 * Split the head registry into what this image can actually serve and what it only
 * names. Shared by the boot pre-warm (warmAll, below) and warmup.js's build-time
 * validation, so "is a gated head baked?" has exactly one definition.
 */
function headResidency() {
  const resident = [];
  const missing = [];
  for (const [head, spec] of Object.entries(HEADS)) {
    // An unbacked head (open edition: no manifest entry, therefore no checkpoint anyone
    // could have baked) is NEITHER — it is not a bake outcome at all. Counting it as
    // `missing` would make warmup.js fail the build with "no baked weights and is not a
    // gated head — the bake is broken", which is exactly backwards: nothing was asked for.
    if (spec.unbacked) continue;
    (residentModelDir(spec) ? resident : missing).push(head);
  }
  return { resident, missing };
}

// One warm pipeline per head, memoized as the loading promise so concurrent first
// requests share a single download/load instead of racing.
const pipelines = new Map();

// Serialize ONNX session creation across heads: building a session transiently uses a
// multiple of the model size, so two fp32 heads loading concurrently (e.g. a cold task
// hit by a sweep burst) can spike past the task's memory envelope — that exact storm
// OOM-killed the first production task on 2026-07-12. One load at a time caps the peak
// at resident-heads + one load transient.
let loadChain = Promise.resolve();

function pipelineFor(head) {
  let p = pipelines.get(head);
  if (!p) {
    const load = async () => {
      // Dynamic import: transformers.js is ESM-only and this service is CommonJS.
      const tf = await import('@huggingface/transformers');
      // Offline by construction: weights are baked into the image (download.js) in
      // localModelPath layout, and remote loading is disabled so the loader can NEVER
      // reach the hub at runtime. (transformers.js fetches tokenizer_config.json at
      // revision `main` even with a pinned revision and a warm cache — with remote
      // allowed, that made every cold start silently depend on Hugging Face.)
      tf.env.localModelPath = process.env.HF_CACHE_DIR || '/models';
      tf.env.allowRemoteModels = false;
      const spec = HEADS[head];
      const opts = {
        dtype: spec.dtype,
        // No CPU memory arena: onnxruntime's arena retains its high-water mark per
        // session, so four heads serving window-filling batches accumulate arenas past
        // any sane task size (the 6 GB Fargate task OOM'd on exactly this, 2026-07-12).
        // Without the arena, inference memory returns to baseline after every
        // micro-batch — ~10-20% slower, bounded forever.
        session_options: { enableCpuMemArena: false },
      };
      if (spec.subfolder !== undefined) opts.subfolder = spec.subfolder;
      console.log(`classify: loading head '${head}' (${spec.model}@${spec.revision})`);
      const started = Date.now();
      const pipe = await tf.pipeline('text-classification', spec.model, opts);
      // The baked tokenizer_config must carry a real encoder window (download.js clamps
      // it to <=512): an unbounded model_max_length turns `truncation: true` into a no-op
      // and attention memory becomes O(sequence²) on arbitrary input — the core bomb of
      // the 2026-07-12 incident. Assert instead of mutate (model_max_length is
      // getter-only); a bad bake fails warmup at BUILD time, never in production.
      const window = Number(pipe.tokenizer.model_max_length);
      if (!Number.isFinite(window) || window <= 0 || window > 512) {
        throw new Error(`head '${head}' has unbounded tokenizer window (${window}) — bake must clamp model_max_length`);
      }
      console.log(`classify: head '${head}' ready in ${Date.now() - started}ms (window ${window})`);
      return pipe;
    };
    p = loadChain.then(load);
    // Keep the chain alive past a failed link, and don't poison the per-head cache —
    // the next request retries.
    loadChain = p.catch(() => {});
    p.catch(() => pipelines.delete(head));
    pipelines.set(head, p);
  }
  return p;
}

/**
 * Load every head sequentially (used by server startup pre-warm). A gated head with no
 * baked weights (keyless open-edition build) is skipped rather than loaded — the
 * whole point of gating is that server.js's unconditional boot-time warmAll() must not
 * crash-loop the container just because a gated head wasn't baked; it used
 * to, before this check existed, because pipelineFor's tf.pipeline() call throws hard on
 * a missing on-disk model with allowRemoteModels=false.
 */
async function warmAll() {
  for (const head of Object.keys(HEADS)) {
    const spec = HEADS[head];
    if (spec.unbacked) {
      console.log(`warmAll: skipping '${head}' (no models.json entry — unavailable in this edition)`);
      continue;
    }
    if (spec.gated && !residentModelDir(spec)) {
      console.log(`warmAll: skipping '${head}' (gated head, weights not baked)`);
      continue;
    }
    await (TOKEN_HEADS.has(head) ? tokenModelFor(head) : pipelineFor(head));
  }
}

/**
 * Split `text` into at most `maxWindows` overlapping character windows, each sized to fit the
 * encoder's ~512-token window. A short text (<= windowChars) yields a single window — exactly the
 * head-only behavior. A long text is tiled with `overlap`, and if that needs more than
 * `maxWindows` windows we sample `maxWindows` offsets spread across the text INCLUDING the first
 * and last, so head- and tail-position behavior are both scored at bounded cost. Pure + testable.
 */
function windowsFor(text, opts = {}) {
  const winChars = opts.windowChars ?? WINDOW_CHARS;
  // Clamp overlap < window so a misconfig (overlap >= winChars) can't degenerate stride to 1
  // and build a huge intermediate offsets array.
  const overlap = Math.min(opts.overlap ?? WINDOW_OVERLAP, winChars - 1);
  const maxWindows = Math.max(1, opts.maxWindows ?? MAX_WINDOWS);
  if (text.length <= winChars || maxWindows === 1) return [text.slice(0, winChars)];

  const stride = Math.max(1, winChars - overlap);
  const starts = [];
  for (let s = 0; s < text.length; s += stride) {
    starts.push(s);
    if (s + winChars >= text.length) break;
  }
  // Guarantee the tail is covered by a window that ends at text.length.
  const lastStart = Math.max(0, text.length - winChars);
  if (starts[starts.length - 1] !== lastStart) starts.push(lastStart);

  let chosen = starts;
  if (starts.length > maxWindows) {
    // Evenly-spaced offsets across [first .. last], endpoints included; dedup collisions.
    const picked = [];
    for (let i = 0; i < maxWindows; i++) {
      picked.push(starts[Math.round((i * (starts.length - 1)) / (maxWindows - 1))]);
    }
    chosen = [...new Set(picked)];
  }
  return chosen.map((s) => text.slice(s, s + winChars));
}

/**
 * Split `premise` into at most `maxChunks` overlapping character windows sized to leave room for
 * a `claimLen`-character claim in the shared {@link PAIR_TOTAL_CHARS} budget — the pair-head
 * analogue of {@link windowsFor}. Delegates to it with a claim-aware window size; a floor keeps
 * some premise content even when the claim alone eats most of the budget. Pure + testable.
 */
function premiseChunksFor(premise, claimLen, opts = {}) {
  const total = opts.totalChars ?? PAIR_TOTAL_CHARS;
  const chunkChars = Math.max(50, total - claimLen);
  return windowsFor(premise, {
    windowChars: chunkChars,
    overlap: opts.overlap ?? PAIR_OVERLAP,
    maxWindows: opts.maxChunks ?? PAIR_MAX_CHUNKS,
  });
}

/**
 * Reduce per-window scores back to one score per group, using `reduce`. Pure and exported so the
 * reduction can be pinned without loading weights — the reason `windowsFor`/`premiseChunksFor` are
 * exported too. `groupOf[w]` names the group window `w` belongs to.
 */
function reduceWindows(winScores, groupOf, numGroups, reduce) {
  const buckets = Array.from({ length: numGroups }, () => []);
  winScores.forEach((sc, w) => buckets[groupOf[w]].push(sc));
  // A group with no windows cannot happen (every input contributes at least one window), but a 0
  // is a safer answer than reducing an empty array to -Infinity if that ever changes.
  return buckets.map((b) => (b.length ? reduce(b) : 0));
}

/**
 * Run `windows` through `pipe` in {@link INFER_BATCH} micro-batches, map each result through
 * `spec.score`, then reduce back to one score per group with the head's own reducer (see
 * {@link REDUCERS}). Shared tail for both single-text and pair-head scoring.
 */
async function scoreWindowedBatch(pipe, spec, windows, groupOf, numGroups, head) {
  const winScores = [];
  for (let i = 0; i < windows.length; i += INFER_BATCH) {
    // top_k null = every label's score; the tokenizer truncates to the model window itself
    // (an intrinsic property of the encoder, not content clipping on our side).
    const out = await pipe(windows.slice(i, i + INFER_BATCH), { top_k: null });
    // Shape: one entry per text, each an array of {label, score}. A single text can come
    // back un-nested — normalize.
    const perText = Array.isArray(out[0]) ? out : [out];
    winScores.push(...perText.map((entries) => spec.score(new Map(entries.map((e) => [e.label, e.score])))));
  }

  return reduceWindows(winScores, groupOf, numGroups, reducerFor(head));
}

/**
 * POST /classify — two mutually-exclusive request shapes:
 *   single-text heads: { head, texts: [".."] }              -> { scores: [0..1] }
 *   pair heads (PAIR_HEADS): { head, pairs: [{premise, claim}] } -> { scores: [0..1] }
 * Index-aligned with the input array either way.
 */
async function classify(payload) {
  const { head } = payload || {};
  // Object.hasOwn: an inherited prototype property (e.g. head="constructor") must be an
  // unknown head, not a truthy junk spec.
  const spec = Object.hasOwn(HEADS, head) ? HEADS[head] : undefined;
  if (!spec) throw new Error(`unknown classify head: ${head}`);

  if (TOKEN_HEADS.has(head)) return classifyTokens(head, spec, payload);
  return PAIR_HEADS.has(head) ? classifyPairs(head, spec, payload) : classifyTexts(head, spec, payload);
}

// Token heads: whole-response scoring, see groundedness.js for the contract and the encoding.
const tokenHead = require('./groundedness');
const tokenModels = new Map();
async function tokenModelFor(head) {
  let p = tokenModels.get(head);
  if (!p) {
    const load = async () => {
      const tf = await import('@huggingface/transformers');
      tf.env.localModelPath = process.env.HF_CACHE_DIR || '/models';
      tf.env.allowRemoteModels = false;
      const spec = HEADS[head];
      const opts = { dtype: spec.dtype, session_options: { enableCpuMemArena: false } };
      if (spec.subfolder !== undefined) opts.subfolder = spec.subfolder;
      console.log(`classify: loading token head '${head}' (${spec.model}@${spec.revision})`);
      const started = Date.now();
      const tokenizer = await tf.AutoTokenizer.from_pretrained(spec.model);
      const model = await tf.AutoModelForTokenClassification.from_pretrained(spec.model, opts);
      // The bake must carry the model's real window (8192 for ModernBERT); groundedness.js sizes
      // its own truncation to MAX_LENGTH, and a smaller baked window would silently cut answers.
      const window = Number(tokenizer.model_max_length);
      if (!Number.isFinite(window) || window < tokenHead.MAX_LENGTH) {
        throw new Error(`token head '${head}' tokenizer window ${window} < ${tokenHead.MAX_LENGTH} — bake must not clamp it`);
      }
      console.log(`classify: token head '${head}' ready in ${Date.now() - started}ms (window ${window})`);
      return { tokenizer, model, tf };
    };
    p = loadChain.then(load);
    loadChain = p.catch(() => {});
    p.catch(() => tokenModels.delete(head));
    tokenModels.set(head, p);
  }
  return p;
}

async function classifyTokens(head, spec, payload) {
  const responses = tokenHead.validate(payload);
  requireResident(head, spec);
  const loaded = await tokenModelFor(head);
  return tokenHead.scoreResponses(loaded, responses);
}

// A gated head (`"gated": true` in models.json) with no baked weights (keyless
// open-edition build, no HF_TOKEN at build time) is a known, distinguishable
// "unavailable" — not the transport/serving failure EncoderScorer's fail-loud contract
// means to catch. Checked here, right before the only place that would otherwise reach
// pipelineFor (and throw an uglier, less specific error from tf.pipeline itself) —
// deliberately AFTER request-shape validation in classifyTexts/classifyPairs, not before
// it in classify(), so a malformed request against a gated head still gets the shape
// error, matching the existing "these all throw before pipelineFor() is reached"
// contract windowing.test.js pins for pair-shape validation. statusCode: 400 (not a new
// 503) is deliberate: server.js already special-cases `e.statusCode === 400` as a
// verbatim client-facing body, so this needs zero server.js changes, and every non-2xx
// is treated identically by the Java caller today regardless of which one it is.
function requireResident(head, spec) {
  // Checked FIRST because it is the coarser condition: an unbacked head has no
  // manifest entry, so `spec.gated` and `residentModelDir` below have nothing to read. This
  // is a third, distinct state — not "unknown head" (a caller bug) and not "gated, not
  // baked" (a build-input outcome) but an EDITION boundary, and the only one whose cause the
  // caller cannot fix by supplying a token or a correct head name.
  //
  // UNAVAILABLE_IN_OPEN_EDITION is a LITERAL, load-bearing token, not prose —
  // scripts/check-classify-service.sh branches on that exact spelling. Do not reword it.
  //
  // What it does NOT reach today, measured, so nobody assumes otherwise: the Java caller.
  // LauncherEncoderScorer discards the body of every non-2xx — it logs `httpStatus` only and
  // throws `launcher /classify returned HTTP 400` — so the token is invisible on that side and
  // the open edition degrades exactly the way a keyless build already does under the gated arm
  // below: the encoder sweep fails loudly and retries on the next heartbeat. That is a tolerable
  // shape (nothing crashes, nothing scores silently wrong), not a distinguishing one. Making the
  // backend tell "this edition cannot serve it" apart from "the service is broken" needs a read
  // of the body there, and backend/** is out of scope for this change.
  //
  // statusCode 400 for the same reason the gated arm uses it — server.js already returns a
  // 400's message verbatim, so this needs no server.js change.
  if (spec.unbacked) {
    throw Object.assign(
      new Error(
        `UNAVAILABLE_IN_OPEN_EDITION: head '${head}' has no entry in models.json — ` +
          'the open manifest binds only the public heads, so no checkpoint is bound to it',
      ),
      { statusCode: 400 },
    );
  }
  if (spec.gated && !residentModelDir(spec)) {
    throw Object.assign(
      new Error(`head '${head}' is not baked into this image (gated, no HF_TOKEN at build time)`),
      { statusCode: 400 },
    );
  }
}

async function classifyTexts(head, spec, payload) {
  const { texts, pairs } = payload;
  if (pairs !== undefined) throw new Error(`head '${head}' is not a pair head — send texts, not pairs`);
  if (!Array.isArray(texts) || texts.length === 0 || !texts.every((t) => typeof t === 'string')) {
    throw new Error('classify texts must be a non-empty string array');
  }
  if (texts.length > HEAD_MAX_TEXTS) throw new Error(`classify batch exceeds ${HEAD_MAX_TEXTS} texts`);
  requireResident(head, spec);
  const pipe = await pipelineFor(head);

  // Clamp each text (a larger hard bound when windowing so there's tail content to cover, still
  // bounded), then break unbroken runs before tokenization: SentencePiece/Unigram (deberta) builds
  // a candidate lattice over substrings, and a SINGLE 4K-char unbroken run spikes multiple GB
  // (measured: 8 clamped blobs OOM-killed a 6 GB container through the jailbreak head). No natural
  // language has 256-char words — only blobs (base64, minified code) are touched, and their scores
  // are meaningless either way.
  const clampLimit = WINDOW_SCORING ? WINDOW_MAX_CHARS : MAX_TEXT_CHARS;
  const prepared = texts.map((t) => deBlob(t.length > clampLimit ? t.slice(0, clampLimit) : t));

  // Expand each text into its window(s); windowing off => one window per text (head-only, the
  // unchanged behavior). Track which text each window belongs to so scores reduce back by max.
  const windows = [];
  const textOf = [];
  prepared.forEach((text, ti) => {
    // OFF: one head window per text — the re-clamp to MAX_TEXT_CHARS is score-identical to the
    // prior behavior (any difference is past the tokenizer's ~512-token truncation).
    const ws = WINDOW_SCORING ? windowsFor(text) : [text.slice(0, MAX_TEXT_CHARS)];
    for (const w of ws) {
      windows.push(w);
      textOf.push(ti);
    }
  });

  const scores = await scoreWindowedBatch(pipe, spec, windows, textOf, prepared.length, head);
  return { scores };
}

async function classifyPairs(head, spec, payload) {
  const { pairs, texts } = payload;
  if (texts !== undefined) throw new Error(`head '${head}' is a pair head — send pairs, not texts`);
  const valid =
    Array.isArray(pairs) &&
    pairs.length > 0 &&
    pairs.every((p) => p && typeof p.premise === 'string' && typeof p.claim === 'string');
  if (!valid) throw new Error('classify pairs must be a non-empty array of {premise, claim} strings');
  if (pairs.length > HEAD_MAX_TEXTS) throw new Error(`classify batch exceeds ${HEAD_MAX_TEXTS} pairs`);
  requireResident(head, spec);
  const pipe = await pipelineFor(head);
  const eos = pipe.tokenizer.eos_token ?? '';

  // Clamp the premise to the same hard bound single-text heads use before chunking — bounded cost
  // regardless of how large the source document is. The claim is clamped separately and more
  // tightly: it is the fixed part of every chunk's input, so its budget share is reserved, not
  // windowed (see PAIR_TOTAL_CHARS commentary above). deBlob applies to both — this path reaches
  // the same tokenizer as classifyTexts and is just as exposed to an unbroken-run blob.
  const windows = [];
  const pairOf = [];
  pairs.forEach((p, pi) => {
    const premise = deBlob(
        p.premise.length > PAIR_MAX_PREMISE_CHARS ? p.premise.slice(0, PAIR_MAX_PREMISE_CHARS) : p.premise);
    const claim = deBlob(p.claim.length > PAIR_CLAIM_MAX_CHARS ? p.claim.slice(0, PAIR_CLAIM_MAX_CHARS) : p.claim);
    const chunks = premiseChunksFor(premise, claim.length + eos.length);
    for (const chunk of chunks) {
      windows.push(chunk + eos + claim);
      pairOf.push(pi);
    }
  });

  const scores = await scoreWindowedBatch(pipe, spec, windows, pairOf, pairs.length, head);
  return { scores };
}

module.exports = {
  classify, warmAll, HEADS, windowsFor, premiseChunksFor, deBlob, PAIR_HEADS, TOKEN_HEADS, headResidency,
  reduceWindows, reducerFor,
};
