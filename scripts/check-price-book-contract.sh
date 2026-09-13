#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The vendored price book's path and shape are a published contract. This gate keeps them fixed.
#
# WHO DEPENDS ON IT. tessaryai/tessary-home's publish-pricing.yml downloads this file from its raw
# GitHub URL on `main`, hashes the bytes, and serves them at home.tessary.ai/v1/pricing/ for running
# instances to price against:
#
#   https://raw.githubusercontent.com/tessaryai/tessary/main/backend/substrate/src/main/resources/pricing/litellm-model-prices.json
#
# Nothing in this repository calls that URL, so no build or test here notices when it stops
# resolving. A move or rename makes home's fetch 404; a file that is no longer a LiteLLM-shaped
# object makes home's validation refuse it. Either way home keeps serving the last good book and
# every instance's prices silently stop moving. This gate is the only place that failure is visible
# from inside this repository, on the PR that causes it.
#
# WHAT IT ASSERTS
#   1. The file exists at PINNED and is tracked by git.
#   2. It parses as a JSON object (not an array, not an HTML error page a proxy returned with a 200).
#   3. At least MIN_PRICED_MODELS entries carry a non-zero input or output token rate, the rule
#      PriceSnapshot.pricesTokens applies, so a file that passes is one an instance can price from.
#      The floor sits far below the real count so normal upstream churn never trips it; it catches a
#      truncated, emptied, or reshaped file. tessary-home's publisher applies the same floor.
#   4. Every in-repo writer and reader still names PINNED: PriceSnapshot.LITELLM_RESOURCE,
#      refresh-model-prices.sh's OUT, and each path literal in price-book-refresh.yml. A rename done
#      consistently across all of them would pass 1-3 and still break home, so the literals are
#      pinned too.
#
# CHANGING THE PATH ANYWAY means changing tessary-home's TESSARY_PRICE_BOOK_URL first and waiting for
# it to publish from the new path, then changing PINNED here in the same PR as the move.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PINNED="backend/substrate/src/main/resources/pricing/litellm-model-prices.json"
RESOURCE="pricing/litellm-model-prices.json"
MIN_PRICED_MODELS=1000

SNAPSHOT_JAVA="backend/substrate/src/main/java/ai/tessary/pricing/PriceSnapshot.java"
REFRESH_SCRIPT="scripts/refresh-model-prices.sh"
REFRESH_WORKFLOW=".github/workflows/price-book-refresh.yml"

fail=0
err() {
    echo "price-book-contract: $1" >&2
    fail=1
}

# --- 1. present and tracked ---
if [ ! -f "$PINNED" ]; then
    err "$PINNED is missing. tessary-home fetches exactly this path; see this script's header."
elif ! git ls-files --error-unmatch -- "$PINNED" >/dev/null 2>&1; then
    err "$PINNED exists but is not tracked by git, so it is not on main for tessary-home to fetch."
fi

# --- 2 and 3. a JSON object that prices tokens ---
if [ -f "$PINNED" ]; then
    if ! python3 - "$PINNED" "$MIN_PRICED_MODELS" <<'PY'
import json
import sys

path, floor = sys.argv[1], int(sys.argv[2])
try:
    with open(path, encoding="utf-8") as fh:
        book = json.load(fh)
except (OSError, ValueError) as exc:
    print(f"price-book-contract: {path} is not parseable JSON: {exc}", file=sys.stderr)
    sys.exit(1)

if not isinstance(book, dict):
    print(
        f"price-book-contract: {path} must be a JSON object keyed by model, got {type(book).__name__}",
        file=sys.stderr,
    )
    sys.exit(1)

RATE_FIELDS = ("input_cost_per_token", "output_cost_per_token")


def is_rate(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value != 0


priced = sum(
    1 for entry in book.values()
    if isinstance(entry, dict) and any(is_rate(entry.get(f)) for f in RATE_FIELDS)
)
if priced < floor:
    print(
        f"price-book-contract: {path} has {priced} entries with a token rate, below the floor of {floor}",
        file=sys.stderr,
    )
    sys.exit(1)

print(f"price-book-contract: {len(book)} entries, {priced} with a token rate (floor {floor})")
PY
    then
        fail=1
    fi
fi

# --- 4. every in-repo writer and reader names the pinned path ---
if ! grep -qF "LITELLM_RESOURCE = \"$RESOURCE\";" "$SNAPSHOT_JAVA"; then
    err "$SNAPSHOT_JAVA must declare LITELLM_RESOURCE = \"$RESOURCE\"."
fi

if ! grep -qxF "OUT=\"$PINNED\"" "$REFRESH_SCRIPT"; then
    err "$REFRESH_SCRIPT must write OUT=\"$PINNED\"."
fi

workflow_paths="$(grep -oE '[^[:space:]"]*litellm-model-prices\.json' "$REFRESH_WORKFLOW" || true)"
if [ -z "$workflow_paths" ]; then
    err "$REFRESH_WORKFLOW names no litellm-model-prices.json path; it must diff and commit $PINNED."
else
    stray="$(printf '%s\n' "$workflow_paths" | grep -vxF "$PINNED" || true)"
    if [ -n "$stray" ]; then
        err "$REFRESH_WORKFLOW names a price book path other than $PINNED:"
        printf '%s\n' "$stray" | sort -u | sed 's/^/  /' >&2
    fi
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "price-book-contract check OK"
