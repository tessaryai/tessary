#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# The groundedness model server, classifiers/groundedness/serve.py, stays runnable as one file.
#
# Setup runs it straight from its URL with `uv run`, so nothing in this repository can break it at
# import time and nothing else would notice. classifiers/tests/test_groundedness_serve.py checks that
# it imports alone in an empty directory, that its PEP 723 header lists every third-party import,
# that its copy of the training encoding matches the answer key, that the request and response in
# classifiers/groundedness/contract/ still fit it, and how the HTTP handler answers.
#
# Only pytest and transformers are installed, not the classifiers project and not torch: serve.py
# imports torch inside the functions that load the model. transformers is pinned to the version the
# answer key was made with, and the encoding test loads the pinned revision's tokenizer from
# classifiers/tests/fixtures/groundedness_tokenizer/, so it runs offline and costs seconds, not a
# model download. GROUNDEDNESS_SERVE_GATE=1 makes that test fail rather than skip without
# transformers; the classifiers gate runs the same file without it and skips that one test.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/classifiers"

if ! command -v uv >/dev/null 2>&1; then
  echo "check-groundedness-serve: needs uv (https://docs.astral.sh/uv/) to run the serve.py tests" >&2
  exit 1
fi

GROUNDEDNESS_SERVE_GATE=1 uv run --no-project --python '>=3.11' --with 'pytest>=8,<10' \
  --with 'transformers==4.57.6' pytest -q -p no:cacheprovider tests/test_groundedness_serve.py
echo "ok groundedness serve.py runs alone and keeps its contract"
