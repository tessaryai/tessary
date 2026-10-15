#!/usr/bin/env bash
# |# SPDX-License-Identifier: Apache-2.0
# Vendors LiteLLM's model_prices_and_context_window.json verbatim, so every price change is a reviewed
# diff. PriceSnapshot.pricesTokens ignores entries with no token rate, so a lookup never reads as free.
# PriceBookImporter versions by content hash; history is not repriced. Also run by price-book-refresh.yml.

set -euo pipefail

UPSTREAM="https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
OUT="backend/substrate/src/main/resources/pricing/litellm-model-prices.json"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp="$(mktemp -t litellm-prices.XXXXXX)"
trap 'rm -f "$tmp"' EXIT

echo "fetching $UPSTREAM" >&2
curl -sSfL "$UPSTREAM" -o "$tmp"

# curl misses a 200 HTML error page, and an unparseable book would boot with every model unpriced.
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
