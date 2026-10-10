#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# =============================================================================
# Refresh the vendored models.dev copy
# =============================================================================
# Pulls models.dev's api.json (MIT, the catalog OpenCode itself prices from) and
# vendors it VERBATIM into the backend jar.
#
# Sandbox runs are billed at models.dev's rates: the backend reads them through
# llm/ModelsDevRates, live from models.dev first, and from this copy when the live
# fetch fails. So this file is the fallback, not the only source, and a stale copy
# only matters while models.dev is unreachable. It is vendored anyway so a run never
# goes unpriced because one HTTP request failed, and so CI can check every sandbox
# model against it offline (ModelsDevCatalogTest).
#
# This is not the price book. The price book (refresh-model-prices.sh, LiteLLM)
# still prices ingested spans and Jev calls direct on TypeSafe.
#
# Usage:  scripts/refresh-models-dev.sh
# Then:   review the diff, commit it.
#
# Also invoked daily by .github/workflows/price-book-refresh.yml.

set -euo pipefail

UPSTREAM="https://models.dev/api.json"
OUT="backend/llm-runtime/src/main/resources/models-dev/api.json"
# Far below the real count (about 7,300), so normal churn never trips it; it catches
# an emptied, truncated or reshaped file.
MIN_PRICED_MODELS=1000

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp="$(mktemp -t models-dev.XXXXXX)"
trap 'rm -f "$tmp"' EXIT

echo "fetching $UPSTREAM" >&2
curl -sSfL "$UPSTREAM" -o "$tmp"

# Parse and count before publishing: a CDN error page served with a 200 must not
# replace the fallback every sandbox run relies on.
python3 - "$tmp" "$MIN_PRICED_MODELS" <<'PY'
import json, sys

with open(sys.argv[1]) as fh:
    data = json.load(fh)
if not isinstance(data, dict):
    sys.exit("models.dev answered something other than a JSON object")
priced = sum(
    1
    for provider in data.values() if isinstance(provider, dict)
    for model in (provider.get("models") or {}).values() if isinstance(model, dict)
    if any((model.get("cost") or {}).get(k) for k in ("input", "output"))
)
if priced < int(sys.argv[2]):
    sys.exit(f"only {priced} priced models; refusing to vendor (floor {sys.argv[2]})")
print(f"{priced} priced models", file=sys.stderr)
PY

mkdir -p "$(dirname "$OUT")"
cp "$tmp" "$OUT"
echo "wrote $OUT" >&2
