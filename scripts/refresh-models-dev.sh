#!/usr/bin/env bash
# |# SPDX-License-Identifier: Apache-2.0
# Vendors models.dev's api.json verbatim into the backend jar: the fallback when llm/ModelsDevRates cannot
# reach models.dev, and what ModelsDevCatalogTest checks offline. Also run by price-book-refresh.yml.

set -euo pipefail

UPSTREAM="https://models.dev/api.json"
OUT="backend/llm-runtime/src/main/resources/models-dev/api.json"
# Far below the real count (about 7,300); catches an emptied or reshaped file.
MIN_PRICED_MODELS=1000

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

tmp="$(mktemp -t models-dev.XXXXXX)"
trap 'rm -f "$tmp"' EXIT

echo "fetching $UPSTREAM" >&2
curl -sSfL "$UPSTREAM" -o "$tmp"

# A CDN error page served with a 200 must not replace the fallback.
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
