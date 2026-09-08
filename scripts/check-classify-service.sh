#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# classify-service gate. Single source of truth: invoked by both the Taskfile
# (`task classify:check`) and CI (.github/workflows/ci.yml). Fast static checks only —
# no model download (the image build's offline warmup validation covers the heads).
#
# EDITIONS (#1293). The scoring code in classify.js is open source; models.json — the manifest
# binding each head to a pinned checkpoint and revision — is not, and moved into the paid overlay.
# The open edition therefore ships `classify-service/models.json` as literally `{}` and every head
# is registered UNBACKED. That is a different assertion, not a weaker one, so this gate BRANCHES:
#
#   --edition open             the manifest MUST be empty. A populated models.json under this flag
#                              is a leak, not a nicety, so it is a hard failure. Then: every head
#                              in HEADS is unbacked and carries no model/revision/dtype, and asking
#                              to serve one produces the literal token UNAVAILABLE_IN_OPEN_EDITION
#                              rather than `unknown classify head`.
#   --edition all   (default)  whichever manifest is in THIS build context, asserted exactly, and
#                              SAID OUT LOUD. Populated => the pre-#1293 exact-set assertion,
#                              unchanged: exactly frustration/groundedness/attribution, each with
#                              model + 40-hex revision + dtype + score. Empty => the open
#                              assertions, plus a printed line naming the assertion that did NOT
#                              run and why.
#
# WHY `all` DISPATCHES ON THE MANIFEST AND NOT ON THE FLAG. `classify-service/` is the build context
# for the dev image AND for the paid deploy (both COPY models.json from it), so in a full checkout
# with the overlay on disk that path still holds `{}` — the populated manifest sits in the overlay
# and is copied over this one at image-build time. A flag-only `all` arm would therefore turn
# `task check` red in every developer checkout for a condition that is correct. Dispatching on the
# manifest keeps the gate honest in both places; printing which arm ran is what keeps the empty arm
# from being the absence-looks-like-compliance skip this repo has been bitten by before. This file
# cannot simply go read the overlay's copy instead: check-open-boundary.sh rule 5 forbids any
# scripts/*.sh outside a four-name allowlist from naming that directory at all.
#
# Branching rather than widening the exact-set list below. That list is exact BY DESIGN (see its
# own comment) — a head appearing without anyone updating the line is what it exists to catch — and
# a list widened to "three heads, or none, or some" catches nothing. Two exact assertions, one per
# manifest shape, keeps that property in both.
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
# download — embed.test.js pins the pooling arithmetic against the backend parity
# fixture; embed.smoke.test.js self-skips unless CONFORMANCE_ENCODER_MODEL_DIR is set).
node --test queue.test.js windowing.test.js embed.test.js embed.smoke.test.js

# Loading classify.js enforces the models.json <-> scorer consistency contract and
# validates the manifest shape; HEADS must expose exactly the built-in heads.
#
# `attribution` is the second head of the FRUSTRATION signal, not a signal of its own: the backend
# scores it only for turns the emotion head already put in the HIGH band, and demotes them when it
# disagrees. It is listed here because this gate is an exact-set assertion by design — a head
# appearing in the service without anyone updating this line is precisely what it exists to catch.
MANIFEST_HEADS="$(node -e "process.stdout.write(String(Object.keys(require('./models.json')).length))")"

if [ "$EDITION" = open ] && [ "$MANIFEST_HEADS" != 0 ]; then
  echo "check-classify-service: --edition open but classify-service/models.json binds $MANIFEST_HEADS head(s)." >&2
  echo "  The open edition ships an EMPTY manifest; a populated one here means the overlay's copy was" >&2
  echo "  materialised into the open build context and would publish the checkpoint pins with it." >&2
  exit 1
fi

if [ "$MANIFEST_HEADS" = 0 ]; then
  # The open manifest's three assertions, in the order a reader would ask them.
  #
  # (1) The manifest is EMPTY, read as JSON, not grepped. `{}` and `{"frustration": {...}}` differ
  #     by more than a byte count, and a half-emptied manifest is exactly the state that would
  #     otherwise publish one checkpoint pin and hide the other two.
  # (2) Every head is UNBACKED and carries no weight fields. `unbacked: true` alone is not enough:
  #     a spec that kept `model`/`revision` and merely gained a flag would still publish the pin.
  # (3) Asking to serve one produces the LITERAL token. This is the half that actually matters to
  #     the open backend, which still calls these heads by name (BuiltInClassifierCatalog carries
  #     their thresholds), so `/classify` must answer "I know this head, this edition cannot serve
  #     it" and not the generic `unknown classify head` — which reads as a caller bug and sends
  #     someone hunting a typo that is not there. Driven through the exported classify() with a
  #     minimal VALID payload per head shape, because requireResident() sits deliberately behind
  #     request-shape validation; a malformed probe would get the shape error and prove nothing.
  node -e "
const fs = require('node:fs');
const raw = JSON.parse(fs.readFileSync('./models.json', 'utf8'));
if (Object.keys(raw).length !== 0) {
  throw new Error('open edition: models.json is not empty — it binds ' + Object.keys(raw).join(', '));
}
const { HEADS, PAIR_HEADS, classify } = require('./classify');
const heads = Object.keys(HEADS).sort();
if (heads.length === 0) {
  throw new Error('open edition: HEADS is empty — the scorers must still be REGISTERED so the backend gets UNAVAILABLE_IN_OPEN_EDITION and not \'unknown classify head\'');
}
for (const [head, spec] of Object.entries(HEADS)) {
  if (spec.unbacked !== true) throw new Error('open edition: head ' + head + ' is not marked unbacked');
  for (const field of ['model', 'revision', 'dtype']) {
    if (spec[field] !== undefined) {
      throw new Error('open edition: head ' + head + ' still carries ' + field + ' — an unbacked head must publish no checkpoint pin');
    }
  }
  if (typeof spec.score !== 'function') throw new Error('open edition: head ' + head + ' has no scorer');
}
(async () => {
  for (const head of heads) {
    const payload = PAIR_HEADS.has(head)
      ? { head, pairs: [{ premise: 'x', claim: 'y' }] }
      : { head, texts: ['x'] };
    let msg = null;
    try {
      await classify(payload);
    } catch (e) {
      msg = e && e.message ? e.message : String(e);
    }
    if (msg === null) throw new Error('open edition: head ' + head + ' SERVED a request with an empty manifest');
    if (!msg.startsWith('UNAVAILABLE_IN_OPEN_EDITION')) {
      throw new Error('open edition: head ' + head + ' failed with ' + JSON.stringify(msg) + ', not the UNAVAILABLE_IN_OPEN_EDITION token the backend contract names');
    }
  }
  console.log('classify-service check OK (empty manifest): ' + heads.length + ' unbacked head(s) reporting UNAVAILABLE_IN_OPEN_EDITION: ' + heads.join(', '));
})().catch((e) => { console.error(e.message); process.exit(1); });
"
  if [ "$EDITION" != open ]; then
    echo "classify-service: NOTE — models.json is empty in this build context, so the exact-set head"
    echo "  assertion (exactly frustration/groundedness/attribution, each with a 40-hex revision) did"
    echo "  NOT run. That is expected in an open checkout and in a full checkout before the overlay's"
    echo "  manifest is copied in at image-build time; it is NOT expected in a paid image build, where"
    echo "  the copy happens first and this gate then takes the populated arm."
  fi
else
  node -e "
const { HEADS } = require('./classify');
const expected = ['frustration', 'groundedness', 'attribution'];
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

# Loading embed.js enforces the embedders.json manifest shape (model + 40-hex revision
# + fp32). On top of that: the conformance encoder MUST stay DECLARED. That part is config,
# present in every image — an empty or differently-keyed manifest is not a harmless default,
# it is a 400 on every /embed call, which parks a compile-service fit and fails a conformance
# sweep, and nothing else in the gate touches the registry. The expected key is read from the
# backend parity fixture rather than hardcoded, so it tracks the checkpoint the engine
# actually fits and names in a bundle manifest (encoder_smoke.checkpoint == ENCODERS['gte']).
#
# The WEIGHTS behind that declaration are a separate, opt-in thing (BAKE_EMBEDDERS=1), so
# they are only asserted when this run is one that claims to have them — which is how the
# same script passes on a laptop with no model cache and inside a baked image.
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
