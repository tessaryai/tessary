#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The groundedness model server, classifiers/groundedness/serve.py, stays runnable as one file.
#
# Setup runs it straight from its URL with `uv run`, so nothing in this repository can break it at
# import time and nothing else would notice. classifiers/tests/test_groundedness_serve.py checks that
# it imports alone in an empty directory, that its PEP 723 header lists every third-party import,
# that its copy of the training encoding matches the answer key, that the request and response in
# classifiers/groundedness/contract/ still fit it, and what `/healthz` reports.
#
# Only pytest is installed, not the classifiers project and not torch: serve.py imports torch and
# transformers inside the functions that load the model, and the one test that needs the tokenizer
# is marked `network` and skips without transformers. So this costs seconds, not a model download.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/classifiers"

if ! command -v uv >/dev/null 2>&1; then
  echo "check-groundedness-serve: needs uv (https://docs.astral.sh/uv/) to run the serve.py tests" >&2
  exit 1
fi

uv run --no-project --python '>=3.11' --with 'pytest>=8,<10' \
  pytest -q -p no:cacheprovider tests/test_groundedness_serve.py
echo "ok groundedness serve.py runs alone and keeps its contract"
