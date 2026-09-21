#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# classify-service gate. Single source of truth: invoked by both the Taskfile
# (`task classify:check`) and CI (.github/workflows/check.yml, via scripts/check.sh). Fast static checks only, no model
# download (the image build's offline warmup validation covers the heads).
#
# The scoring code in classify.js is open source. models.json, the manifest binding each head to a
# pinned checkpoint and revision, binds in THIS tree exactly the heads whose weights are public:
# `groundedness` (tessaryai/groundedness-token-v1, MIT, public since 2026-09-21). A scorer the
# manifest does not bind registers unbacked and answers the literal token
# UNAVAILABLE_IN_OPEN_EDITION (none today: the frustration heads left this service with #104). An
# overlay manifest handed in at build time is the other shape this gate knows. So it branches on
# which shape it finds:
#
#   --edition open             the manifest must bind exactly the public heads (OPEN_HEADS below).
#                              Any other set under this flag is a hard failure: a private pin in the
#                              open manifest would publish it. Every unbacked head carries no
#                              model/revision/dtype, and asking to serve one returns the literal
#                              token UNAVAILABLE_IN_OPEN_EDITION rather than `unknown classify head`.
#   --edition all   (default)  whichever manifest is present is asserted exactly, and the result is
#                              printed. Any other manifest: exactly groundedness, with model +
#                              40-hex revision + dtype + score. Open: the same
#                              assertions as --edition open, plus a printed line naming the
#                              assertion that did not run and why.
#
# `all` dispatches on the manifest rather than the flag, because a flag-only `all` arm would turn
# `task check` red on a checkout where the open manifest is the correct state. Dispatching on the
# manifest keeps the gate honest either way, and printing which arm ran keeps the open result from
# reading as silent compliance.
#
# Branching rather than widening the exact-set lists below: each list is exact by design (see its
# own comment, a head appearing without anyone updating the line is what it exists to catch), and
# a list widened to "one head, or none, or some" would catch nothing. Two exact assertions, one
# per manifest shape, keeps that property in both.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

EDITION=all
while [ $# -gt 0 ]; do
  case "$1" in
    --edition)
      EDITION="${2:-}"
      case "$EDITION" in
        open|all) ;;
        *) echo "check-classify-service: --edition takes 'open' or 'all', got '${EDITION:-}'" >&2; exit 1 ;;
      esac
      shift 2
      ;;
    *) echo "check-classify-service: unknown argument '$1' (only --edition open|all)" >&2; exit 1 ;;
  esac
done

cd "$ROOT/classify-service"

pnpm install --frozen-lockfile --prod --ignore-scripts

for f in server.js classify.js embed.js download.js warmup.js queue.js queue.test.js windowing.test.js \
         embed.test.js embed.smoke.test.js; do
  node --check "$f"
done

# Concurrency-gate + windowing + /embed unit tests (node:test, no deps, no model
# download; embed.test.js pins the pooling arithmetic against the backend parity
# fixture, embed.smoke.test.js self-skips unless CONFORMANCE_ENCODER_MODEL_DIR is set).
node --test queue.test.js windowing.test.js groundedness.test.js embed.test.js embed.smoke.test.js

# Loading classify.js enforces the models.json <-> scorer consistency contract and
# validates the manifest shape; HEADS must expose exactly the built-in heads.
#
# The heads whose weights are public, i.e. the exact set the OPEN manifest binds. Exact, not "at
# least": a private pin landing here is what this line exists to catch.
OPEN_HEADS="groundedness"
MANIFEST_KEYS="$(node -e "process.stdout.write(Object.keys(require('./models.json')).sort().join(' '))")"

if [ "$EDITION" = open ] && [ "$MANIFEST_KEYS" != "$OPEN_HEADS" ]; then
  echo "check-classify-service: --edition open but classify-service/models.json binds '$MANIFEST_KEYS'," >&2
  echo "  not the public set '$OPEN_HEADS'. A private head here means the overlay's copy was materialised" >&2
  echo "  into the open build context and would publish its checkpoint pin with it." >&2
  exit 1
fi

if [ "$MANIFEST_KEYS" = "$OPEN_HEADS" ]; then
  # The open manifest's assertions, in the order a reader would ask them.
  #
  # (1) The manifest binds exactly the public heads, read as JSON, not grepped, each with a 40-hex
  #     revision and not marked gated; a private entry here would publish that checkpoint's pin.
  # (2) Every other head is unbacked and carries no weight fields. `unbacked: true` alone is not enough:
  #     a spec that kept `model`/`revision` and merely gained a flag would still publish the pin.
  # (3) Asking to serve one produces the literal token. This is the half that actually matters to
  #     the backend, which still calls these heads by name (BuiltInClassifierCatalog carries their
  #     thresholds), so `/classify` must answer "I know this head, this edition cannot serve it"
  #     and not the generic `unknown classify head`, which reads as a caller bug and sends someone
  #     hunting a typo that is not there. Driven through the exported classify() with a minimal
  #     valid payload per head shape, because requireResident() sits deliberately behind
  #     request-shape validation; a malformed probe would get the shape error and prove nothing.
  OPEN_HEADS="$OPEN_HEADS" node -e "
const fs = require('node:fs');
const raw = JSON.parse(fs.readFileSync('./models.json', 'utf8'));
const publicHeads = process.env.OPEN_HEADS.split(' ').sort();
if (JSON.stringify(Object.keys(raw).sort()) !== JSON.stringify(publicHeads)) {
  throw new Error('open edition: models.json binds ' + Object.keys(raw).join(', ') + ', not the public set ' + publicHeads.join(', '));
}
for (const head of publicHeads) {
  const spec = raw[head];
  if (spec.gated) throw new Error('open edition: public head ' + head + ' is marked gated — its weights are public and a keyless build must bake them');
  if (!/^[0-9a-f]{40}$/.test(spec.revision || '')) throw new Error('open edition: public head ' + head + ' has no 40-hex revision pin');
}
const { HEADS, PAIR_HEADS, TOKEN_HEADS, classify } = require('./classify');
const heads = Object.keys(HEADS).sort();
const unbacked = heads.filter((h) => !publicHeads.includes(h));
for (const head of unbacked) {
  const spec = HEADS[head];
  if (spec.unbacked !== true) throw new Error('open edition: head ' + head + ' is not marked unbacked');
  for (const field of ['model', 'revision', 'dtype']) {
    if (spec[field] !== undefined) {
      throw new Error('open edition: head ' + head + ' still carries ' + field + ' — an unbacked head must publish no checkpoint pin');
    }
  }
  if (typeof spec.score !== 'function') throw new Error('open edition: head ' + head + ' has no scorer');
}
for (const head of publicHeads) {
  const spec = HEADS[head];
  if (spec.unbacked) throw new Error('open edition: public head ' + head + ' registered unbacked');
  if (typeof spec.score !== 'function') throw new Error('open edition: head ' + head + ' has no scorer');
}
(async () => {
  for (const head of unbacked) {
    // Each head gets its own request shape: shape validation runs before the edition check.
    const payload = TOKEN_HEADS.has(head)
      ? { head, responses: [{ passages: ['x'], answer: 'y' }] }
      : PAIR_HEADS.has(head)
        ? { head, pairs: [{ premise: 'x', claim: 'y' }] }
        : { head, texts: ['x'] };
    let msg = null;
    try {
      await classify(payload);
    } catch (e) {
      msg = e && e.message ? e.message : String(e);
    }
    if (msg === null) throw new Error('open edition: head ' + head + ' SERVED a request with no manifest entry');
    if (!msg.startsWith('UNAVAILABLE_IN_OPEN_EDITION')) {
      throw new Error('open edition: head ' + head + ' failed with ' + JSON.stringify(msg) + ', not the UNAVAILABLE_IN_OPEN_EDITION token the backend contract names');
    }
  }
  console.log('classify-service check OK (open manifest): public head(s) ' + publicHeads.join(', ') + ' bound; ' + unbacked.length + ' unbacked head(s) reporting UNAVAILABLE_IN_OPEN_EDITION: ' + unbacked.join(', '));
})().catch((e) => { console.error(e.message); process.exit(1); });
"
  if [ "$EDITION" != open ]; then
    echo "classify-service: NOTE — models.json is the OPEN manifest in this build context, so the"
    echo "  other-manifest exact-set assertion did NOT run. That is expected in an open checkout; an"
    echo "  overlay image build hands its own manifest in as the \`manifest\` build context and this"
    echo "  gate then takes the other arm against that file."
  fi
else
  node -e "
const { HEADS } = require('./classify');
const expected = ['groundedness'];
const actual = Object.keys(HEADS).sort();
if (JSON.stringify(actual) !== JSON.stringify([...expected].sort())) {
  throw new Error('HEADS mismatch: ' + actual.join(','));
}
for (const [head, spec] of Object.entries(HEADS)) {
  if (!spec.model || !/^[0-9a-f]{40}$/.test(spec.revision) || !spec.dtype || typeof spec.score !== 'function') {
    throw new Error('head ' + head + ' has an incomplete spec (model/revision-sha/dtype/score)');
  }
}
console.log('classify-service check OK: ' + actual.join(', '));
"
fi

# Loading embed.js enforces the embedders.json manifest shape (model + 40-hex revision +
# fp32). On top of that, the conformance encoder must stay declared. That part is config,
# present in every image: an empty or differently-keyed manifest is not a harmless default, it
# is a 400 on every /embed call, which parks a compile-service fit and fails a conformance
# sweep, and nothing else in the gate touches the registry. The expected key is read from the
# backend parity fixture rather than hardcoded, so it tracks the checkpoint the engine
# actually fits and names in a bundle manifest (encoder_smoke.checkpoint == ENCODERS['gte']).
#
# The weights behind that declaration are a separate, opt-in thing (BAKE_EMBEDDERS=1), so
# they are only asserted when this run is one that claims to have them, which is how the same
# script passes on a laptop with no model cache and inside a baked image.
node -e "
const fs = require('node:fs');
const { EMBEDDERS, checkpointResidency } = require('./embed');
const fixture = JSON.parse(fs.readFileSync('$ROOT/backend/analysis/src/test/resources/conformance_parity.json', 'utf8'));
const expected = fixture.encoder_smoke && fixture.encoder_smoke.checkpoint;
if (!expected) throw new Error('conformance_parity.json has no encoder_smoke.checkpoint to check the registry against');
if (!Object.hasOwn(EMBEDDERS, expected)) {
  throw new Error(
    'embedders.json does not register the conformance encoder ' + JSON.stringify(expected) +
    ' (registered: ' + (Object.keys(EMBEDDERS).join(', ') || '(none)') + ') — /embed would 400 every fit and every sweep'
  );
}
const { resident, missing } = checkpointResidency();
if (process.env.BAKE_EMBEDDERS === '1' && missing.length > 0) {
  throw new Error(
    'BAKE_EMBEDDERS=1 but no weights under ' + (process.env.HF_CACHE_DIR || '/models') +
    ' for: ' + missing.join(', ')
  );
}
console.log(
  'classify-service embed manifest OK: ' + Object.keys(EMBEDDERS).join(', ') +
  ' (weights baked: ' + (resident.join(', ') || 'none — /embed 400s, the production default') + ')'
);
"
