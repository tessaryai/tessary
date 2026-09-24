#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# classifiers/ gate: pytest over classifiers/tests, the open eval framework's tests and serve.py's.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/classifiers"

command -v uv >/dev/null 2>&1 || {
    echo "classifiers: uv is required (https://docs.astral.sh/uv/)" >&2
    exit 1
}

# `quality` carries scikit-learn, cleanlab and statsmodels, which test_agreement.py and test_audit.py
# import; `dev` carries pytest. --frozen: the lock is the dependency set, never re-resolved here.
uv sync --frozen --group dev --extra quality
uv run --frozen pytest tests -q -p no:cacheprovider
