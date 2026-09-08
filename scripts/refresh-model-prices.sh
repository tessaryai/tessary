#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# =============================================================================
# Refresh the vendored model price book from LiteLLM
# =============================================================================
# Pulls BerriAI/litellm's model_prices_and_context_window.json (MIT, community
# maintained, day-0 pricing for the major providers) and vendors it VERBATIM.
#
# Why vendored rather than fetched at runtime: these become customer-visible
# dollar figures. A community-maintained file is the right source for COVERAGE,
# but a bad upstream edit must not be able to silently change what a customer is
# told they spent. Vendoring makes every price change arrive as a reviewable
# diff, and keeps the pricing path offline and deterministic.
#
# WHY VERBATIM, AND WHAT THAT COST. This script used to trim each entry down to
# the four token-cost buckets we price against, on the theory that a smaller file
# is a more reviewable diff. That trim was dropped on 2026-09-02: we take LiteLLM
# at its word on pricing, so filtering their file bought a smaller diff at the
# price of a second, undocumented transformation between upstream and what we
# ship. A byte-for-byte copy is the honest artifact — what you review is exactly
# what upstream published.
#
# The trim was quietly doing one other job, and dropping it needed a real fix
# rather than a shrug: it also discarded any entry with NEITHER an input nor an
# output token rate (about 740 of upstream's 3,500 — image and audio models like
# dall-e and stable-diffusion, priced per image rather than per token, plus
# LiteLLM's own `sample_spec` documentation stub). Carried into the price book
# unguarded, those entries make a lookup SUCCEED into an all-zero cost, which
# reads as "this was free" rather than "we have no rate for this". That guard now
# lives where it belongs, in the two parsers themselves — see the shared
# `hasTokenRate` contract documented on PriceSnapshot and TokenPriceBook — so the
# invariant holds no matter how the file was produced, instead of depending on a
# shell script having filtered it first.
#
# The output is loaded into the database by PriceBookImporter, which versions a
# snapshot by the hash of its bytes: a refreshed file is a NEW price_book version,
# picked up at the next boot or the next daily tick, and rows already priced keep
# pointing at the book that priced them. No migration, and no repricing of history.
# There is no hand-maintained correction file layered over this one (#1032 retired
# manual-overrides.json): a stale or wrong row here is a bug to raise with LiteLLM
# upstream, and a model priced under a different spelling than a producer reports it
# needs a producer-side fix (see BedrockModelProfile.MANTLE_ROUTE_PREFIX for the
# precedent), not a hand-edit to this file.
#
# Usage:  scripts/refresh-model-prices.sh
# Then:   review the diff, commit it.
#
# Also invoked daily by .github/workflows/price-book-refresh.yml — this file stays
# the single source of truth for how the price book gets refreshed.

set -euo pipefail

UPSTREAM="https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
OUT="backend/substrate/src/main/resources/pricing/litellm-model-prices.json"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp="$(mktemp -t litellm-prices.XXXXXX)"
trap 'rm -f "$tmp"' EXIT

echo "fetching $UPSTREAM" >&2
curl -sSfL "$UPSTREAM" -o "$tmp"

# Parse before publishing. curl reports transport failures, not a proxy or CDN that
# hands back an HTML error page with a 200 — and an unparseable price book is one
# PriceSnapshot logs a warning for and then boots with every model unpriced. Cheaper
# to refuse to write it than to ship it and read the warning later.
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp"

mkdir -p "$(dirname "$OUT")"
cp "$tmp" "$OUT"

python3 - "$OUT" <<'PY'
import json, sys

with open(sys.argv[1]) as fh:
    out = json.load(fh)

RATE_FIELDS = ("input_cost_per_token", "output_cost_per_token")
priced = sum(
    1 for v in out.values()
    if isinstance(v, dict) and any(v.get(f) for f in RATE_FIELDS)
)
print(
    f"wrote {len(out)} entries verbatim ({priced} carry a token rate; "
    f"{len(out) - priced} read as unpriced by design)",
    file=sys.stderr,
)
PY

echo "wrote $OUT" >&2
